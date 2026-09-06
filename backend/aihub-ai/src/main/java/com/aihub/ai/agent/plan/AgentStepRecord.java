package com.aihub.ai.agent.plan;

import java.util.List;
import java.util.Map;

/**
 * 步骤执行记录。
 *
 * <p>对应 docs/SECURITY.md §4「每次执行的步骤、选用的 Tool、参数与结果必须落
 * {@code agent_task}，否则出错无法复现」，因此连参数都留下来。
 *
 * <p>{@code data} 是 Tool 的结构化输出（其中的不可信文本已被清洗与截断），
 * 规划器要靠它决定下一步（例如从搜索结果里取 assetId）。
 *
 * @param tool        Tool 名
 * @param args        实际传入的参数（原样留存，便于复现）
 * @param intent      该步骤的意图
 * @param status      成功 / 失败
 * @param message     结果说明；失败时是失败原因
 * @param data        Tool 的结构化输出，失败时为 null
 * @param warnings    安全提示（注入特征命中、内容截断等）
 * @param durationMs  实际耗时
 * @param confirmedAt 该步骤是否在用户确认之后执行
 */
public record AgentStepRecord(
        String tool,
        Map<String, Object> args,
        String intent,
        AgentStepStatus status,
        String message,
        Object data,
        List<String> warnings,
        long durationMs,
        boolean confirmedAt) {

    public static AgentStepRecord succeeded(String tool, Map<String, Object> args, String intent,
                                            String message, Object data, List<String> warnings,
                                            long durationMs, boolean confirmedAt) {
        return new AgentStepRecord(tool, args, intent, AgentStepStatus.SUCCEEDED, message, data,
                warnings == null ? List.of() : List.copyOf(warnings), durationMs, confirmedAt);
    }

    public static AgentStepRecord failed(String tool, Map<String, Object> args, String intent,
                                         String reason, long durationMs, boolean confirmedAt) {
        return new AgentStepRecord(tool, args, intent, AgentStepStatus.FAILED, reason, null,
                List.of(), durationMs, confirmedAt);
    }

    /**
     * 方法名刻意是 {@code hasFailed} 而不是 {@code isFailed}：Jackson 会把 {@code isXxx()}
     * 当成名为 {@code failed} 的属性序列化进 JSON，而 record 里没有这个字段，
     * 反序列化时会因未知属性失败，表现为步骤记录读回来是空列表。
     */
    public boolean hasFailed() {
        return status == AgentStepStatus.FAILED;
    }
}
