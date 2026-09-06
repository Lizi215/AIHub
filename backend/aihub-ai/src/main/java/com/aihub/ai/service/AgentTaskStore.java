package com.aihub.ai.service;

import com.aihub.ai.agent.orchestrator.AgentTaskStateMachine;
import com.aihub.ai.agent.plan.AgentPlan;
import com.aihub.ai.agent.plan.AgentStepRecord;
import com.aihub.ai.entity.AgentTaskEntity;
import com.aihub.ai.enums.AgentScene;
import com.aihub.ai.enums.AgentTaskStatus;
import com.aihub.ai.mapper.AgentTaskMapper;
import com.aihub.common.exception.BizException;
import com.aihub.common.mybatis.BaseEntity;
import com.aihub.common.result.ErrorCode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code agent_task} 的持久化入口。
 *
 * <p>单独抽出来，是为了让「谁来写库」只有一处实现：Orchestrator 与对外的 Service 都通过这里
 * 读写，避免状态流转散落在多个类里各自 {@code updateById}，导致状态机校验被绕过。
 *
 * <p>所有状态变更都经过 {@link AgentTaskStateMachine} 校验，非法流转直接抛异常。
 */
@Component
public class AgentTaskStore {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskStore.class);

    private final AgentTaskMapper agentTaskMapper;
    private final ObjectMapper objectMapper;

    public AgentTaskStore(AgentTaskMapper agentTaskMapper, ObjectMapper objectMapper) {
        this.agentTaskMapper = agentTaskMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AgentTaskEntity create(Long userId, AgentScene scene, String request, String sessionId) {
        AgentTaskEntity entity = new AgentTaskEntity();
        entity.setUserId(userId);
        entity.setSessionId(sessionId);
        entity.setScene(scene.name());
        entity.setRequest(request);
        // 新建一律 PENDING，不经状态机：状态机管的是变更，不是初始值
        entity.setStatus(AgentTaskStatus.PENDING.name());
        entity.setStepCount(0);
        entity.setTokenUsed(0);
        entity.setInjectionDetected(0);
        entity.setDeleted(BaseEntity.NOT_DELETED);
        entity.setSteps(writeJson(List.of()));
        agentTaskMapper.insert(entity);
        log.info("agent task created, taskId={}, userId={}, scene={}", entity.getId(), userId, scene);
        return entity;
    }

    /** 读取任务，不存在抛 91001。 */
    public AgentTaskEntity requireById(Long taskId) {
        AgentTaskEntity entity = agentTaskMapper.selectOne(new LambdaQueryWrapper<AgentTaskEntity>()
                .eq(AgentTaskEntity::getId, taskId)
                .eq(AgentTaskEntity::getDeleted, BaseEntity.NOT_DELETED));
        if (entity == null) {
            throw new BizException(ErrorCode.AGENT_TASK_NOT_FOUND);
        }
        return entity;
    }

    /** 读取任务并校验归属；非发起者一律 91004。 */
    public AgentTaskEntity requireOwned(Long taskId, Long currentUserId) {
        AgentTaskEntity entity = requireById(taskId);
        if (currentUserId == null || !currentUserId.equals(entity.getUserId())) {
            throw new BizException(ErrorCode.AGENT_TASK_ACCESS_DENIED);
        }
        return entity;
    }

    /** 状态流转：先过状态机，再落库。 */
    @Transactional
    public void transition(AgentTaskEntity entity, AgentTaskStatus target) {
        AgentTaskStatus from = AgentTaskStateMachine.parse(entity.getStatus());
        AgentTaskStateMachine.assertCanTransit(from, target);
        AgentTaskEntity update = new AgentTaskEntity();
        update.setId(entity.getId());
        update.setStatus(target.name());
        agentTaskMapper.updateById(update);
        entity.setStatus(target.name());
        log.info("agent task status changed, taskId={}, {} -> {}", entity.getId(), from, target);
    }

    /**
     * 保存阶段结果（计划 / 步骤 / 汇总 / Token / 注入标记）。
     *
     * <p>只写非 null 字段：{@code null} 表示"本次不涉及"，避免把上一阶段的计划或结果擦掉。
     */
    @Transactional
    public void saveProgress(AgentTaskEntity entity, AgentPlan plan, List<AgentStepRecord> steps,
                             Object result, String error, int tokenUsed, boolean injectionDetected,
                             String model) {
        AgentTaskEntity update = new AgentTaskEntity();
        update.setId(entity.getId());
        update.setStepCount(steps == null ? 0 : steps.size());
        update.setTokenUsed(tokenUsed);
        update.setInjectionDetected(injectionDetected ? 1 : 0);
        if (plan != null) {
            update.setPlan(writeJson(plan));
        }
        if (steps != null) {
            update.setSteps(writeJson(steps));
        }
        if (result != null) {
            update.setResult(writeJson(result));
        }
        if (error != null) {
            update.setError(error.length() > 500 ? error.substring(0, 500) : error);
        }
        if (model != null) {
            update.setModel(model);
        }
        agentTaskMapper.updateById(update);
    }

    @Transactional
    public void markConfirmed(AgentTaskEntity entity) {
        AgentTaskEntity update = new AgentTaskEntity();
        update.setId(entity.getId());
        update.setConfirmedTime(LocalDateTime.now());
        agentTaskMapper.updateById(update);
        entity.setConfirmedTime(update.getConfirmedTime());
    }

    /** 读取历史步骤。JSON 损坏时按空列表处理并告警：任务详情不该因为一段脏数据就完全打不开。 */
    public List<AgentStepRecord> readSteps(AgentTaskEntity entity) {
        if (entity.getSteps() == null || entity.getSteps().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(entity.getSteps(), new TypeReference<List<AgentStepRecord>>() {
            });
        } catch (Exception e) {
            log.warn("failed to parse agent task steps, taskId={}", entity.getId(), e);
            return List.of();
        }
    }

    /** 读取已产出的计划；没有计划则抛状态异常（调用确认接口时说明任务并不在等待确认）。 */
    public AgentPlan readPlan(AgentTaskEntity entity) {
        if (entity.getPlan() == null || entity.getPlan().isBlank()) {
            throw new BizException(ErrorCode.AGENT_TASK_NOT_AWAITING_CONFIRM,
                    "agent task has no plan yet: " + entity.getId());
        }
        try {
            return objectMapper.readValue(entity.getPlan(), AgentPlan.class);
        } catch (Exception e) {
            throw new BizException(ErrorCode.AGENT_TASK_NOT_AWAITING_CONFIRM,
                    "agent task plan is unreadable: " + entity.getId());
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            // 序列化失败说明数据结构有问题，属于必须暴露的缺陷，直接用系统错误抛出
            throw new BizException(ErrorCode.SYSTEM_ERROR, "failed to serialize agent task payload");
        }
    }
}
