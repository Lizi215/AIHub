package com.aihub.ai.service.impl;

import com.aihub.ai.agent.orchestrator.AgentOrchestrator;
import com.aihub.ai.agent.orchestrator.AgentTaskStateMachine;
import com.aihub.ai.agent.plan.AgentPlan;
import com.aihub.ai.agent.plan.AgentPlanStep;
import com.aihub.ai.agent.plan.AgentStepRecord;
import com.aihub.ai.dto.CreateAgentTaskRequest;
import com.aihub.ai.entity.AgentTaskEntity;
import com.aihub.ai.enums.AgentScene;
import com.aihub.ai.enums.AgentTaskStatus;
import com.aihub.ai.service.AgentTaskService;
import com.aihub.ai.service.AgentTaskStore;
import com.aihub.ai.vo.AgentPlanStepVo;
import com.aihub.ai.vo.AgentPlanVo;
import com.aihub.ai.vo.AgentStepVo;
import com.aihub.ai.vo.AgentTaskVo;
import com.aihub.common.exception.BizException;
import com.aihub.common.result.ErrorCode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

/**
 * Agent 任务服务实现。
 *
 * <p>职责边界：本类只做任务生命周期（创建并投递、查询、确认、取消），具体的计划与执行在
 * {@link AgentOrchestrator}。分开是因为编排逻辑要被独立单测，而这里大量依赖持久化与线程池。
 */
@Service
public class AgentTaskServiceImpl implements AgentTaskService {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskServiceImpl.class);

    private final AgentTaskStore store;
    private final AgentOrchestrator orchestrator;
    private final TaskExecutor taskExecutor;
    private final ObjectMapper objectMapper;

    public AgentTaskServiceImpl(AgentTaskStore store,
                                AgentOrchestrator orchestrator,
                                @Qualifier("agentTaskExecutor") TaskExecutor taskExecutor,
                                ObjectMapper objectMapper) {
        this.store = store;
        this.orchestrator = orchestrator;
        this.taskExecutor = taskExecutor;
        this.objectMapper = objectMapper;
    }

    @Override
    public Long create(Long currentUserId, CreateAgentTaskRequest request) {
        if (currentUserId == null) {
            // 创建必须登录：任务归属决定后续谁能确认与查看
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        AgentScene scene = AgentScene.parse(request.scene());
        // sessionId 可空，不带会话的请求不关联历史
        AgentTaskEntity task = store.create(currentUserId, scene, request.request().trim(), request.sessionId());

        try {
            Long taskId = task.getId();
            taskExecutor.execute(() -> orchestrator.runPlanning(taskId));
        } catch (RejectedExecutionException e) {
            // 队列满时明确告诉用户"现在忙"，并把任务置为失败，而不是让它永远停在 PENDING
            log.warn("agent task rejected by executor, taskId={}", task.getId(), e);
            failRejected(task);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "agent is busy, please retry later");
        }
        return task.getId();
    }

    @Override
    public AgentTaskVo detail(Long taskId, Long currentUserId) {
        return toVo(store.requireOwned(taskId, currentUserId));
    }

    @Override
    public AgentTaskVo confirm(Long taskId, Long currentUserId) {
        AgentTaskEntity task = store.requireOwned(taskId, currentUserId);
        if (AgentTaskStateMachine.parse(task.getStatus()) != AgentTaskStatus.WAITING_CONFIRM) {
            throw new BizException(ErrorCode.AGENT_TASK_NOT_AWAITING_CONFIRM,
                    "agent task is not awaiting confirmation: " + taskId);
        }
        store.markConfirmed(task);
        // 先把状态推进到 RUNNING 再提交后台执行：这样确认接口返回「已开始执行」，且第二次重复确认
        // 会因状态不再是 WAITING_CONFIRM 而被拒（91003）
        store.transition(task, AgentTaskStatus.RUNNING);
        // 执行继续在后台线程里跑：确认接口本身必须立刻返回，不能挂住
        taskExecutor.execute(() -> orchestrator.runAfterConfirm(taskId));
        return toVo(store.requireById(taskId));
    }

    @Override
    public AgentTaskVo cancel(Long taskId, Long currentUserId) {
        AgentTaskEntity task = store.requireOwned(taskId, currentUserId);
        // 终态任务不可再取消：走状态机校验，非法流转直接报错而不是静默成功
        store.transition(task, AgentTaskStatus.CANCELLED);
        return toVo(store.requireById(taskId));
    }

    /* ------------------------------------------------------------------ 内部方法 */

    private void failRejected(AgentTaskEntity task) {
        try {
            store.saveProgress(task, null, List.of(), null,
                    ErrorCode.SYSTEM_ERROR.getCode() + ": agent executor rejected the task",
                    0, false, null);
            store.transition(task, AgentTaskStatus.FAILED);
        } catch (RuntimeException e) {
            log.error("failed to mark rejected agent task as failed, taskId={}", task.getId(), e);
        }
    }

    private AgentTaskVo toVo(AgentTaskEntity task) {
        AgentTaskStatus status = AgentTaskStateMachine.parse(task.getStatus());
        List<AgentStepRecord> records = store.readSteps(task);
        return new AgentTaskVo(
                task.getId(),
                task.getScene(),
                task.getRequest(),
                status.name(),
                status == AgentTaskStatus.WAITING_CONFIRM,
                readPlan(task),
                records.stream().map(this::toStepVo).toList(),
                readJson(task.getResult()),
                task.getError(),
                task.getStepCount() == null ? 0 : task.getStepCount(),
                task.getTokenUsed() == null ? 0 : task.getTokenUsed(),
                task.getInjectionDetected() != null && task.getInjectionDetected() == 1,
                task.getModel(),
                task.getCreateTime());
    }

    private AgentPlanVo readPlan(AgentTaskEntity task) {
        if (task.getPlan() == null || task.getPlan().isBlank()) {
            return null;
        }
        try {
            AgentPlan plan = objectMapper.readValue(task.getPlan(), AgentPlan.class);
            List<AgentPlanStepVo> steps = plan.steps().stream()
                    .map(this::toPlanStepVo)
                    .toList();
            return new AgentPlanVo(plan.summary(), plan.requiresConfirmation(), steps);
        } catch (Exception e) {
            log.warn("failed to parse agent task plan, taskId={}", task.getId(), e);
            return null;
        }
    }

    private AgentPlanStepVo toPlanStepVo(AgentPlanStep step) {
        return new AgentPlanStepVo(step.tool(), step.intent(), step.requiresConfirmation(), step.args());
    }

    private AgentStepVo toStepVo(AgentStepRecord record) {
        return new AgentStepVo(record.tool(), record.intent(), record.status().name(), record.message(),
                record.warnings(), record.durationMs(), record.confirmedAt());
    }

    private Object readJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return null;
        }
    }
}
