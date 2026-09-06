package com.aihub.ai.agent.context;

import java.util.List;

/**
 * 清洗后的不可信文本。
 *
 * @param text     可直接进入模型上下文的文本（已截断、已隔离在定界符内、已中和指令特征）
 * @param flagged  是否命中 Prompt 注入特征
 * @param findings 命中特征的可读编号，例如 {@code prompt_injection:instruction_override}
 */
public record SanitizedText(String text, boolean flagged, List<String> findings) {

    public static SanitizedText empty() {
        return new SanitizedText("", false, List.of());
    }
}
