package com.aihub.ai.enums;

/**
 * Agent 场景（docs/AGENT.md §3 的重点功能）。
 *
 * <p>场景决定允许调用哪些 Tool、是否需要进入确认流程。它是服务端判定的结果，
 * 不采信模型自报的场景，越权部分由 {@code AgentOrchestrator} 兜住。
 */
public enum AgentScene {

    /** AI 搜索：搜索资源 → 获取详情 → 分析内容 → 返回结果 */
    SEARCH,
    /** AI 下载助手：搜索 → 判断可用版本 → 生成计划 → 用户确认 → 执行下载 → 返回结果 */
    DOWNLOAD,
    /** Workspace Builder：搜索 → 分析 → 处理依赖 → 生成草案 → 用户确认 → 保存 */
    WORKSPACE,
    /** AI 上传助手：识别类型 → 分析内容 → 生成 README → 自动标签 → 查重 */
    UPLOAD,
    /** AI Bug 助手：获取资源与版本 → 搜索历史 Issue → 分析问题 → 给出可能方案 */
    BUG_ANALYSIS;

    /**
     * 解析场景字符串，非法取值直接拒绝而不是落到默认场景。
     *
     * <p>猜错场景会让本该需要确认的任务直接执行，宁可报错。
     */
    public static AgentScene parse(String scene) {
        if (scene == null || scene.isBlank()) {
            throw new com.aihub.common.exception.BizException(
                    com.aihub.common.result.ErrorCode.PARAM_INVALID, "agent scene is required");
        }
        try {
            return valueOf(scene.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new com.aihub.common.exception.BizException(
                    com.aihub.common.result.ErrorCode.AGENT_SCENE_UNSUPPORTED,
                    "unsupported agent scene: " + scene);
        }
    }
}
