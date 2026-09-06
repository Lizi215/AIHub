package com.aihub.ai.agent.guard;

import com.aihub.common.exception.BizException;
import com.aihub.common.result.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 循环检测（docs/AGENT.md §7「相同 Tool + 相同参数重复调用需检测并中断」）。
 *
 * <p>只判「工具 + 参数」完全相同的情况，而不是「同一工具调了两次」：
 * 连续搜索两个不同关键字是完全正常的，把它当成循环会让正常任务被误杀。
 *
 * <p>参数做规范化后再比较：{@code {"a":1,"b":2}} 与 {@code {"b":2,"a":1}}
 * 是同一件事，按字面串比较会被绕过。
 */
public final class LoopDetectionGuard {

    private LoopDetectionGuard() {
    }

    /**
     * 生成调用指纹。键排序后再序列化，保证字段顺序不影响判定。
     */
    public static String fingerprint(String tool, Map<String, Object> args, ObjectMapper objectMapper) {
        Map<String, Object> normalized = args == null ? Map.of() : new TreeMap<>(args);
        String serialized;
        try {
            serialized = objectMapper.writeValueAsString(normalized);
        } catch (Exception e) {
            // 序列化失败不该让任务崩掉：退回字面串，最坏情况是循环检测更严格
            serialized = String.valueOf(normalized);
        }
        return tool + "|" + serialized;
    }

    /**
     * 校验本次调用是否为重复调用。
     *
     * @param seenFingerprints 已执行调用的指纹集合
     * @throws BizException {@link ErrorCode#AGENT_LOOP_DETECTED}
     */
    public static void assertNotRepeated(Set<String> seenFingerprints, String fingerprint) {
        if (seenFingerprints.contains(fingerprint)) {
            throw new BizException(ErrorCode.AGENT_LOOP_DETECTED,
                    "agent repeated the same tool call: " + fingerprint);
        }
    }
}
