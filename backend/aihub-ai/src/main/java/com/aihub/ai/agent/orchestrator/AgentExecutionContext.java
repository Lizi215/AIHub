package com.aihub.ai.agent.orchestrator;

import com.aihub.ai.agent.guard.LoopDetectionGuard;
import com.aihub.ai.agent.guard.StepLimitGuard;
import com.aihub.ai.agent.guard.TokenBudgetGuard;
import com.aihub.ai.agent.plan.AgentStepRecord;
import com.aihub.ai.config.AgentProperties;
import com.aihub.ai.enums.AgentScene;
import com.aihub.common.exception.BizException;
import com.aihub.common.result.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一次任务运行的上下文，由 Orchestrator 单线程驱动，不涉及并发。
 *
 * <p>它承担三件事：累积状态（已执行步骤、Token、注入命中）；执行守卫（步数、循环、
 * 预算、超时，都在调用 Tool 之前判定）；确认状态（未确认前拒绝执行需确认的 Tool）。
 *
 * <p>跨阶段（规划 → 等待确认 → 执行）的状态通过构造函数注入：步骤与 Token 是累积值，
 * 若重新计数，步数上限与预算限额会形同虚设。
 */
public class AgentExecutionContext {

    private final Long taskId;
    private final Long userId;
    private final AgentScene scene;
    private final String userRequest;
    private final List<AgentStepRecord> steps;
    private final Set<String> fingerprints = new LinkedHashSet<>();
    private final Set<String> injectionFindings = new LinkedHashSet<>();
    private final AgentProperties properties;
    private final ObjectMapper objectMapper;

    private int tokenUsed;
    private boolean confirmed;
    private Instant deadline;

    public AgentExecutionContext(Long taskId, Long userId, AgentScene scene, String userRequest,
                                 List<AgentStepRecord> existingSteps, int tokenUsed,
                                 AgentProperties properties, ObjectMapper objectMapper) {
        this.taskId = taskId;
        this.userId = userId;
        this.scene = scene;
        this.userRequest = userRequest;
        this.steps = new ArrayList<>(existingSteps == null ? List.of() : existingSteps);
        this.tokenUsed = tokenUsed;
        this.properties = properties;
        this.objectMapper = objectMapper;
        // 重新登记历史调用指纹：跨阶段的循环检测要连着看，否则「确认后重复同样的调用」检测不出来
        for (AgentStepRecord record : this.steps) {
            fingerprints.add(LoopDetectionGuard.fingerprint(record.tool(), record.args(), objectMapper));
            injectionFindings.addAll(record.warnings());
        }
    }

    /** 开始一个新阶段（规划 / 确认后执行），重置该阶段的超时窗口。 */
    public void beginPhase() {
        this.deadline = Instant.now().plus(properties.getTaskTimeout());
    }

    /** 检查本次调用是否被允许，不合法直接抛异常（由 Orchestrator 转为任务失败）。 */
    public void assertCanInvoke(String tool, java.util.Map<String, Object> args) {
        assertNotExpired();
        StepLimitGuard.assertWithinLimit(steps.size(), properties.getMaxSteps());
        TokenBudgetGuard.assertWithinBudget(tokenUsed, properties.getMaxTokens());
        LoopDetectionGuard.assertNotRepeated(fingerprints,
                LoopDetectionGuard.fingerprint(tool, args, objectMapper));
    }

    /** 记录一次调用指纹（在真正执行后登记，避免"失败即忘记"导致无限重试同一调用）。 */
    public void remember(String tool, java.util.Map<String, Object> args) {
        fingerprints.add(LoopDetectionGuard.fingerprint(tool, args, objectMapper));
    }

    public void record(AgentStepRecord record) {
        steps.add(record);
        injectionFindings.addAll(record.warnings());
    }

    public void addTokens(int tokens) {
        this.tokenUsed += Math.max(tokens, 0);
    }

    public void markConfirmed() {
        this.confirmed = true;
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public List<AgentStepRecord> steps() {
        return List.copyOf(steps);
    }

    public int stepCount() {
        return steps.size();
    }

    public int tokenUsed() {
        return tokenUsed;
    }

    public Set<String> injectionFindings() {
        return Set.copyOf(injectionFindings);
    }

    public boolean injectionDetected() {
        return !injectionFindings.isEmpty();
    }

    public Long taskId() {
        return taskId;
    }

    public Long userId() {
        return userId;
    }

    public AgentScene scene() {
        return scene;
    }

    public String userRequest() {
        return userRequest;
    }

    public AgentProperties properties() {
        return properties;
    }

    /** 任务总时长校验（docs/AGENT.md §7「超时」的第二个维度）。 */
    public void assertNotExpired() {
        if (deadline != null && Instant.now().isAfter(deadline)) {
            throw new BizException(ErrorCode.AGENT_TASK_TIMEOUT,
                    "agent task exceeded task timeout: " + properties.getTaskTimeout().toSeconds() + "s");
        }
    }

    public Duration remaining() {
        return deadline == null ? Duration.ZERO : Duration.between(Instant.now(), deadline);
    }
}
