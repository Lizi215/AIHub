package com.aihub.ai.agent.orchestrator;

import com.aihub.ai.agent.plan.AgentPlanStep;
import com.aihub.ai.agent.plan.AgentStepRecord;
import com.aihub.ai.agent.tool.AgentToolCatalog;
import com.aihub.ai.agent.tool.ToolResult;
import com.aihub.ai.config.AgentProperties;
import com.aihub.common.exception.BizException;
import com.aihub.common.result.ErrorCode;
import com.aihub.common.trace.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Tool 调用的唯一入口：守卫 → 确认门禁 → 超时执行 → 记录。
 *
 * <p>把这几件事绑在一起，保证没有任何路径能绕过它们：只要调 Tool 就必须经过这里
 * （规划阶段的只读收集、确认后的执行阶段都一样）。两类失败在这里明确区分：
 * 守卫与门禁（步数、循环、Token、超时、未确认就调需确认工具）抛异常，由 Orchestrator
 * 结束整个任务；Tool 自身失败（参数不合法、下游报错、success=false、单次超时）记成一条
 * FAILED 步骤并继续，不牵连整份计划（docs/AGENT.md §5 要求区分已完成 / 失败 / 失败原因）。
 */
@Component
public class AgentToolInvoker {

    private static final Logger log = LoggerFactory.getLogger(AgentToolInvoker.class);

    private final AgentToolCatalog catalog;
    private final AgentProperties properties;
    private final ObjectMapper objectMapper;
    private final ExecutorService agentToolExecutor;

    public AgentToolInvoker(AgentToolCatalog catalog,
                            AgentProperties properties,
                            ObjectMapper objectMapper,
                            @Qualifier("agentToolExecutor") ExecutorService agentToolExecutor) {
        this.catalog = catalog;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.agentToolExecutor = agentToolExecutor;
    }

    /**
     * 执行一步。
     *
     * @param context 任务上下文
     * @param step    计划中的一步
     */
    public AgentStepRecord invoke(AgentExecutionContext context, AgentPlanStep step) {
        String tool = step.tool();
        Map<String, Object> args = step.args() == null ? Map.of() : step.args();

        // 守卫与确认门禁：这两类问题必须中断任务，而不是记成一条失败步骤
        context.assertCanInvoke(tool, args);
        assertConfirmationSatisfied(context, tool);

        // 执行（带单次调用超时）
        context.remember(tool, args);
        long startNanos = System.nanoTime();
        AgentStepRecord record;
        try {
            ToolResult result = callWithTimeout(tool, args);
            long durationMs = elapsedMs(startNanos);
            if (result.success()) {
                record = AgentStepRecord.succeeded(tool, args, step.intent(), result.message(),
                        result.data(), result.warnings(), durationMs, context.isConfirmed());
            } else {
                // Tool 明确报告失败：原因原样保留，禁止粉饰成成功
                record = AgentStepRecord.failed(tool, args, step.intent(), result.message(),
                        durationMs, context.isConfirmed());
            }
        } catch (BizException e) {
            record = AgentStepRecord.failed(tool, args, step.intent(),
                    e.getErrorCode().getCode() + ": " + e.getMessage(),
                    elapsedMs(startNanos), context.isConfirmed());
        } catch (Exception e) {
            log.error("agent tool invocation failed, tool={}", tool, e);
            record = AgentStepRecord.failed(tool, args, step.intent(),
                    ErrorCode.AGENT_TOOL_FAILED.getCode() + ": " + e.getMessage(),
                    elapsedMs(startNanos), context.isConfirmed());
        }

        context.record(record);
        log.info("agent step executed, taskId={}, tool={}, status={}, durationMs={}",
                context.taskId(), tool, record.status(), record.durationMs());
        return record;
    }

    /** 需确认的 Tool 在用户确认前一律拒绝执行（docs/AGENT.md §5）。 */
    private void assertConfirmationSatisfied(AgentExecutionContext context, String tool) {
        if (catalog.requiresConfirmation(tool) && !context.isConfirmed()) {
            throw new BizException(ErrorCode.AGENT_TOOL_CONFIRMATION_REQUIRED,
                    "tool '" + tool + "' requires user confirmation before execution");
        }
    }

    /**
     * 把调用方线程的身份带进工具线程。
     *
     * <p>工具为了做单次超时跑在独立线程池里，而 {@code UserContext} 是 ThreadLocal，
     * 不显式传递的话工具线程里身份是 null，owner 视角的工具（list_my_assets、同名判定）
     * 会失效，或把自己的同名资源误判成他人同名。身份来自任务线程（任务行的 user_id），
     * 模型无法伪造；执行完在工具线程里清理，避免被池化线程的下一个任务继承。
     */
    <T> java.util.concurrent.Callable<T> withCallerIdentity(java.util.concurrent.Callable<T> action) {
        Long callerUserId = UserContext.getUserId();
        String callerRole = UserContext.getRole();
        return () -> {
            if (callerUserId != null) {
                UserContext.set(callerUserId, callerRole);
            }
            try {
                return action.call();
            } finally {
                if (callerUserId != null) {
                    UserContext.clear();
                }
            }
        };
    }

    /** 单次 Tool 调用超时（docs/AGENT.md §7）。超时按步骤失败处理并如实记录原因。 */
    private ToolResult callWithTimeout(String tool, Map<String, Object> args) {
        // 工具跑在独立线程池，UserContext 是 ThreadLocal，须显式把调用方身份传进去
        Future<ToolResult> future = agentToolExecutor.submit(
                withCallerIdentity(() -> catalog.invoke(tool, objectMapper.valueToTree(args))));
        try {
            return future.get(properties.getToolTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 中断是尽力而为：Feign 已在飞的请求不一定立刻停下，但任务不会再等它
            future.cancel(true);
            throw new BizException(ErrorCode.AGENT_TOOL_TIMEOUT,
                    "tool '" + tool + "' timed out after " + properties.getToolTimeout().toSeconds() + "s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.AGENT_TOOL_TIMEOUT, "tool '" + tool + "' was interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof BizException bizException) {
                throw bizException;
            }
            throw new BizException(ErrorCode.AGENT_TOOL_FAILED,
                    "tool '" + tool + "' failed: " + (cause == null ? "unknown" : cause.getMessage()));
        }
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** 供 Orchestrator 判断某一步是否属于需确认操作。 */
    public boolean requiresConfirmation(String tool) {
        return catalog.requiresConfirmation(tool);
    }

    /** 供规划器了解当前有哪些 Tool 可用（只读工具集合）。 */
    public List<String> readOnlyTools() {
        return catalog.names().stream().filter(name -> !catalog.requiresConfirmation(name)).sorted().toList();
    }
}
