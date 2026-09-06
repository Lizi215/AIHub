package com.aihub.ai.agent.chat;

/**
 * 会话历史里的一轮对话（有界裁剪后的形态）。
 *
 * @param request 用户该轮请求的截断原文（≤200 字）
 * @param summary 该轮任务结果的摘要（≤400 字）
 * @param status  该轮任务终态（SUCCEEDED / FAILED 等）
 */
public record HistoryTurn(String request, String summary, String status) {
}
