package com.aihub.ai.agent.tool;

import java.util.List;

/**
 * Tool 的统一返回体。
 *
 * <p>包一层是为了给模型明确的成功 / 失败信号：失败若表现为抛异常，异常会被框架
 * 转成一段文本，模型未必识别；显式 {@code success=false} 才能让模型停止重试同一调用。
 * {@code message} 给人看，{@code data} 给模型看，{@code warnings} 承载注入检测与截断提示，
 * 由 Orchestrator 汇总到 {@code agent_task.injection_detected}（docs/AGENT.md §7）。
 *
 * @param success  Tool 是否成功完成
 * @param message  结果说明；失败时必须说明原因
 * @param data     结构化结果，可为 null
 * @param warnings 安全相关提示（注入特征命中、内容被截断等），可为空列表
 */
public record ToolResult(boolean success, String message, Object data, List<String> warnings) {

    public static ToolResult success(String message, Object data) {
        return new ToolResult(true, message, data, List.of());
    }

    public static ToolResult success(String message, Object data, List<String> warnings) {
        return new ToolResult(true, message, data, warnings == null ? List.of() : List.copyOf(warnings));
    }

    public static ToolResult failure(String message) {
        return new ToolResult(false, message, null, List.of());
    }

    public static ToolResult failure(String message, List<String> warnings) {
        return new ToolResult(false, message, null, warnings == null ? List.of() : List.copyOf(warnings));
    }
}
