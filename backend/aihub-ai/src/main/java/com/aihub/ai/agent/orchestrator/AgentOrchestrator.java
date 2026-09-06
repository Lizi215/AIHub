package com.aihub.ai.agent.orchestrator;

import com.aihub.ai.agent.chat.AgentChatHistoryService;
import com.aihub.common.trace.UserContext;
import com.aihub.ai.agent.chat.HistoryTurn;
import com.aihub.ai.agent.guard.TokenBudgetGuard;
import com.aihub.ai.agent.plan.AgentPlan;
import com.aihub.ai.agent.plan.AgentPlanningState;
import com.aihub.ai.agent.plan.AgentPlanStep;
import com.aihub.ai.agent.plan.AgentStepRecord;
import com.aihub.ai.agent.plan.PlannerDecision;
import com.aihub.ai.agent.tool.AgentToolCatalog;
import com.aihub.ai.config.AgentProperties;
import com.aihub.ai.entity.AgentTaskEntity;
import com.aihub.ai.enums.AgentScene;
import com.aihub.ai.enums.AgentTaskStatus;
import com.aihub.ai.service.AgentTaskStore;
import com.aihub.common.exception.BizException;
import com.aihub.common.result.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent Orchestrator：把「理解意图 → 制定计划 → 调用 Tool → 分析结果 → 汇总」串起来
 * （docs/AGENT.md §1）。
 *
 * <p>三条不变量由本类保证，不依赖模型自觉：含需确认 Tool 的计划停在 {@code WAITING_CONFIRM}，
 * 一个步骤都不执行，确认后才继续；每个步骤是否需确认一律以 Tool 注册表为准，覆写模型自报值；
 * 部分失败时如实区分已完成 / 失败 / 失败原因，不做成功化处理（docs/AGENT.md §5）。
 *
 * <p>执行拆成两段异步运行，不做成挂起的 HTTP 请求：{@link #runPlanning} 跑到计划产出即返回，
 * 确认后由 {@link #runAfterConfirm} 续跑。
 */
@Component
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    private final AgentTaskStore store;
    private final AgentPlanner planner;
    private final AgentToolInvoker invoker;
    private final AgentToolCatalog catalog;
    private final AgentProperties properties;
    private final ObjectMapper objectMapper;
    private final AgentChatHistoryService chatHistory;

    public AgentOrchestrator(AgentTaskStore store,
                             AgentPlanner planner,
                             AgentToolInvoker invoker,
                             AgentToolCatalog catalog,
                             AgentProperties properties,
                             ObjectMapper objectMapper,
                             AgentChatHistoryService chatHistory) {
        this.store = store;
        this.planner = planner;
        this.invoker = invoker;
        this.catalog = catalog;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.chatHistory = chatHistory;
    }

    /**
     * 规划阶段：收集信息 → 产出计划。
     *
     * <p>不需要确认的计划在同一段里直接执行完；需要确认的则挂起为 {@code WAITING_CONFIRM}。
     */
    public void runPlanning(Long taskId) {
        AgentTaskEntity task = store.requireById(taskId);
        if (AgentTaskStateMachine.parse(task.getStatus()).isTerminal()) {
            log.info("agent task already finished, skip planning, taskId={}", taskId);
            return;
        }
        // 后台线程没有请求上下文，显式注入任务归属者身份，「我的资源」这类 owner 视角工具依赖它
        UserContext.set(task.getUserId(), "USER");
        try {
            runPlanning0(task);
        } finally {
            UserContext.clear();
        }
    }

    private void runPlanning0(AgentTaskEntity task) {
        store.transition(task, AgentTaskStatus.RUNNING);

        // 多轮会话历史（Redis，有界裁剪）进提示词，让模型知道之前聊过什么；Redis 不可用时返回空列表
        List<HistoryTurn> history = chatHistory.load(task.getSessionId(), task.getUserId());

        AgentExecutionContext context = newContext(task, false);
        context.beginPhase();

        AgentPlan plan;
        try {
            plan = planUntilDecided(context, history);
        } catch (BizException e) {
            fail(task, context, e);
            return;
        } catch (RuntimeException e) {
            log.error("agent planning failed unexpectedly, taskId={}", task.getId(), e);
            fail(task, context, new BizException(ErrorCode.SYSTEM_ERROR, String.valueOf(e.getMessage())));
            return;
        }

        plan = normalize(plan);
        if (plan.requiresConfirmation()) {
            // 一个步骤都不执行：先让用户看到计划
            store.saveProgress(task, plan, context.steps(), null, null,
                    context.tokenUsed(), context.injectionDetected(), planner.mode());
            store.transition(task, AgentTaskStatus.WAITING_CONFIRM);
            log.info("agent task waiting for confirmation, taskId={}, steps={}", task.getId(), plan.steps().size());
            return;
        }
        executeAndFinish(task, context, plan);
    }

    /**
     * 确认之后的续跑：用户已确认，需确认的 Tool 现在才允许执行。
     *
     * <p>接受 {@code WAITING_CONFIRM} 与 {@code RUNNING} 两种状态：正常路径下 Service 层已在
     * 返回前把状态推进到 RUNNING，直接调用本方法时任务仍停在 WAITING_CONFIRM。其余状态一律拒绝，
     * 尤其是终态，避免「已取消的任务又被跑起来」。
     */
    public void runAfterConfirm(Long taskId) {
        AgentTaskEntity task = store.requireById(taskId);
        UserContext.set(task.getUserId(), "USER");
        try {
            runAfterConfirm0(task);
        } finally {
            UserContext.clear();
        }
    }

    private void runAfterConfirm0(AgentTaskEntity task) {
        AgentTaskStatus current = AgentTaskStateMachine.parse(task.getStatus());
        if (current != AgentTaskStatus.WAITING_CONFIRM && current != AgentTaskStatus.RUNNING) {
            throw new BizException(ErrorCode.AGENT_TASK_NOT_AWAITING_CONFIRM,
                    "agent task is not awaiting confirmation: " + task.getId());
        }
        AgentPlan plan = store.readPlan(task);
        if (current == AgentTaskStatus.WAITING_CONFIRM) {
            store.transition(task, AgentTaskStatus.RUNNING);
        }

        AgentExecutionContext context = newContext(task, true);
        context.beginPhase();
        executeAndFinish(task, context, normalize(plan));
    }

    /* ------------------------------------------------------------------ 规划 */

    private AgentPlan planUntilDecided(AgentExecutionContext context, List<HistoryTurn> history) {
        AgentPlanningState state = new AgentPlanningState(context.scene(), context.userRequest(),
                context.steps(), context.stepCount(), history);

        for (int round = 0; round < properties.getMaxSteps(); round++) {
            assertStillActive(context.taskId());
            PlannerDecision decision = planner.next(state);
            context.addTokens(decision.tokenUsed());
            TokenBudgetGuard.assertWithinBudget(context.tokenUsed(), properties.getMaxTokens());
            context.assertNotExpired();

            if (decision.plan() != null) {
                return decision.plan();
            }
            if (decision.toolCalls().isEmpty()) {
                throw new BizException(ErrorCode.AGENT_TOOL_FAILED,
                        "planner returned neither tool calls nor a plan");
            }
            if (decision.toolCalls().stream()
                    .anyMatch(call -> catalog.requiresConfirmation(call.tool()))) {
                // 规划阶段不执行需确认操作：转成待确认计划让用户看到，而不是直接执行或让任务失败
                return new AgentPlan("模型请求执行需确认的操作，已转为待确认计划", decision.toolCalls());
            }
            for (AgentPlanStep call : decision.toolCalls()) {
                invoker.invoke(context, call);
            }
            state = new AgentPlanningState(context.scene(), context.userRequest(),
                    context.steps(), context.stepCount(), history);
        }
        throw new BizException(ErrorCode.AGENT_STEP_LIMIT_EXCEEDED,
                "planning did not converge within " + properties.getMaxSteps() + " steps");
    }

    /* ------------------------------------------------------------------ 执行与汇总 */

    private void executeAndFinish(AgentTaskEntity task, AgentExecutionContext context, AgentPlan plan) {
        try {
            for (AgentPlanStep step : plan.steps()) {
                assertStillActive(task.getId());
                invoker.invoke(context, step);
            }
        } catch (BizException e) {
            fail(task, context, e);
            return;
        } catch (RuntimeException e) {
            log.error("agent execution failed unexpectedly, taskId={}", task.getId(), e);
            fail(task, context, new BizException(ErrorCode.SYSTEM_ERROR, String.valueOf(e.getMessage())));
            return;
        }

        Map<String, Object> result = summarize(plan, context.steps());
        List<AgentStepRecord> failed = context.steps().stream().filter(AgentStepRecord::hasFailed).toList();
        if (failed.isEmpty()) {
            store.saveProgress(task, plan, context.steps(), result, "", context.tokenUsed(),
                    context.injectionDetected(), planner.mode());
            store.transition(task, AgentTaskStatus.SUCCEEDED);
            appendHistory(task, historySummary(plan, context.steps()), AgentTaskStatus.SUCCEEDED.name());
            log.info("agent task succeeded, taskId={}, steps={}", task.getId(), context.stepCount());
            return;
        }
        String reason = failed.size() + "/" + context.stepCount() + " steps failed: "
                + failed.stream().map(r -> r.tool() + " -> " + r.message()).reduce((a, b) -> a + "; " + b).orElse("");
        // 有失败步骤 → 任务整体判为 FAILED，但 result 里逐条区分已完成与失败（docs/AGENT.md §5）
        store.saveProgress(task, plan, context.steps(), result, reason, context.tokenUsed(),
                context.injectionDetected(), planner.mode());
        store.transition(task, AgentTaskStatus.FAILED);
        appendHistory(task, reason, AgentTaskStatus.FAILED.name());
        log.warn("agent task finished with failed steps, taskId={}, reason={}", task.getId(), reason);
    }

    /* ------------------------------------------------------------------ 会话历史 */

    /** 终态后把本轮对话追加进会话历史（Redis 内部失败已被服务吞掉，不影响任务结果）。 */
    private void appendHistory(AgentTaskEntity task, String summary, String status) {
        chatHistory.append(task.getSessionId(), task.getUserId(), task.getRequest(), summary, status);
    }

    /** 历史里这轮的摘要：计划概要 + 各步骤结果，越短越好，模型只需知道当时做了什么、结论是什么。 */
    private String historySummary(AgentPlan plan, List<AgentStepRecord> steps) {
        String stepText = steps.stream()
                .map(r -> r.tool() + " " + r.status().name().toLowerCase() + ": " + r.message())
                .reduce((a, b) -> a + "; " + b)
                .orElse("");
        return "plan: " + plan.summary() + "; " + stepText;
    }

    /** 汇总结果给前端展示：明确列出已完成 / 失败 / 失败原因。 */
    private Map<String, Object> summarize(AgentPlan plan, List<AgentStepRecord> steps) {
        List<Map<String, Object>> detail = new ArrayList<>(steps.size());
        int succeeded = 0;
        int failed = 0;
        for (AgentStepRecord step : steps) {
            if (step.hasFailed()) {
                failed++;
            } else {
                succeeded++;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tool", step.tool());
            row.put("intent", step.intent());
            row.put("status", step.status().name());
            row.put("message", step.message());
            row.put("data", step.data());
            detail.add(row);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("planSummary", plan.summary());
        summary.put("totalSteps", steps.size());
        summary.put("succeededSteps", succeeded);
        summary.put("failedSteps", failed);
        summary.put("steps", detail);
        return summary;
    }

    /* ------------------------------------------------------------------ 内部方法 */

    /**
     * 步骤是否需确认一律以注册表为准，覆写模型/脚本自报的值。否则一句「这一步不需要确认」
     * 就能跳过用户确认。
     */
    private AgentPlan normalize(AgentPlan plan) {
        List<AgentPlanStep> normalized = new ArrayList<>();
        for (AgentPlanStep step : plan.steps()) {
            if (!catalog.contains(step.tool())) {
                throw new BizException(ErrorCode.AGENT_TOOL_NOT_FOUND,
                        "plan refers to unknown tool: " + step.tool());
            }
            normalized.add(step.withConfirmation(catalog.requiresConfirmation(step.tool())));
        }
        return new AgentPlan(plan.summary(), normalized);
    }

    private AgentExecutionContext newContext(AgentTaskEntity task, boolean confirmed) {
        AgentExecutionContext context = new AgentExecutionContext(
                task.getId(), task.getUserId(), AgentScene.parse(task.getScene()), task.getRequest(),
                store.readSteps(task), task.getTokenUsed() == null ? 0 : task.getTokenUsed(),
                properties, objectMapper);
        if (confirmed) {
            context.markConfirmed();
        }
        return context;
    }

    /** 取消检查：任务被用户取消后，正在跑的步骤不再继续。 */
    private void assertStillActive(Long taskId) {
        AgentTaskStatus current = AgentTaskStateMachine.parse(store.requireById(taskId).getStatus());
        if (current == AgentTaskStatus.CANCELLED) {
            throw new BizException(ErrorCode.AGENT_TASK_STATUS_TRANSITION_INVALID,
                    "agent task was cancelled by user");
        }
    }

    private void fail(AgentTaskEntity task, AgentExecutionContext context, BizException e) {
        String reason = e.getErrorCode().getCode() + ": " + e.getMessage();
        log.warn("agent task failed, taskId={}, reason={}", task.getId(), reason);
        // 状态可能已被取消等操作改掉，先看当前状态再决定是否流转，避免非法流转抛二次异常
        AgentTaskStatus current = AgentTaskStateMachine.parse(store.requireById(task.getId()).getStatus());
        store.saveProgress(task, null, context.steps(), null, reason, context.tokenUsed(),
                context.injectionDetected(), planner.mode());
        if (!current.isTerminal()) {
            task.setStatus(current.name());
            store.transition(task, AgentTaskStatus.FAILED);
        }
        appendHistory(task, reason, AgentTaskStatus.FAILED.name());
    }
}
