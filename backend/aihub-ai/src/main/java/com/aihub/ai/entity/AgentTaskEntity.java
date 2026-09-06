package com.aihub.ai.entity;

import com.aihub.common.mybatis.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * Agent 任务（{@code agent_task}）。
 *
 * <p>AI Service 唯一持有的表。业务数据（资源 / 用户 / 社区）一律不落这里，
 * 那是各自服务的职责（架构约定：禁止跨服务访问数据）。
 *
 * <p>{@code plan} / {@code steps} / {@code result} 存 JSON 文本，
 * 由 {@code AgentTaskStore} 统一读写，避免各处自行拼字符串导致格式不一致。
 */
@TableName("agent_task")
public class AgentTaskEntity extends BaseEntity {

    private Long userId;

    /**
     * 所属会话（前端生成的 UUID，可空）。会话历史本身在 Redis（agent:chat:{userId}:{sessionId}，
     * TTL 2h 不持久化），这里落一份只为编排异步执行时能拿到 Key 后半段：Orchestrator 只有 taskId，
     * 不落库的话确认后续跑阶段就拿不回 sessionId 了。
     */
    private String sessionId;

    /** 场景：SEARCH / DOWNLOAD / WORKSPACE / UPLOAD / BUG_ANALYSIS */
    private String scene;

    /** 用户请求原文 */
    private String request;

    /** PENDING / RUNNING / WAITING_CONFIRM / SUCCEEDED / FAILED / CANCELLED */
    private String status;

    /** 计划 JSON */
    private String plan;

    /** 已执行步骤 JSON（含参数与结果，docs/SECURITY.md §4 要求可复现） */
    private String steps;

    /** 汇总结果 JSON */
    private String result;

    /** 失败原因（终态为 FAILED 时） */
    private String error;

    private Integer stepCount;

    private Integer tokenUsed;

    /** 上下文中是否命中 Prompt 注入特征（0/1） */
    private Integer injectionDetected;

    /** 实际执行模式：{@code mock} 或模型名 */
    private String model;

    private LocalDateTime confirmedTime;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getScene() {
        return scene;
    }

    public void setScene(String scene) {
        this.scene = scene;
    }

    public String getRequest() {
        return request;
    }

    public void setRequest(String request) {
        this.request = request;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPlan() {
        return plan;
    }

    public void setPlan(String plan) {
        this.plan = plan;
    }

    public String getSteps() {
        return steps;
    }

    public void setSteps(String steps) {
        this.steps = steps;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public Integer getStepCount() {
        return stepCount;
    }

    public void setStepCount(Integer stepCount) {
        this.stepCount = stepCount;
    }

    public Integer getTokenUsed() {
        return tokenUsed;
    }

    public void setTokenUsed(Integer tokenUsed) {
        this.tokenUsed = tokenUsed;
    }

    public Integer getInjectionDetected() {
        return injectionDetected;
    }

    public void setInjectionDetected(Integer injectionDetected) {
        this.injectionDetected = injectionDetected;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public LocalDateTime getConfirmedTime() {
        return confirmedTime;
    }

    public void setConfirmedTime(LocalDateTime confirmedTime) {
        this.confirmedTime = confirmedTime;
    }
}
