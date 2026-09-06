package com.aihub.ai.agent.plan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 资源分类的确定性推断（按名字线索）。
 *
 * <p>分类是有客观线索的判断（包名后缀、关键词），适合用规则做；让模型判类型不可靠，
 * 即使提示词写了判据也可能一律给同一个类别。模型只负责写简介与 README。
 *
 * <p>后缀比关键词强（{@code filesystem-mcp} → MCP，但 {@code mcp-builder-skill} 是造 MCP
 * 的技能 → SKILL）；两者都没命中返回 {@code null}，由调用方决定回退。
 */
public final class CategoryInferrer {

    /** 后缀规则：最强的线索，越具体越靠前 */
    private static final Map<String, String> SUFFIX_RULES = new LinkedHashMap<>(Map.of(
            "-mcp", "MCP",
            "-skill", "SKILL",
            "-hook", "HOOK",
            "-prompt", "PROMPT",
            "-rules", "RULES",
            "-template", "TEMPLATE"
    ));

    /** 关键词规则：整体包含即可（顺序 = 判定优先级） */
    private static final Map<String, List<String>> KEYWORD_RULES = new LinkedHashMap<>();

    static {
        KEYWORD_RULES.put("MCP", List.of("mcp server", "mcp-server", "mcp ", "mcp"));
        KEYWORD_RULES.put("HOOK", List.of("hook", "钩子", "拦截"));
        KEYWORD_RULES.put("PROMPT", List.of("prompt", "提示词"));
        KEYWORD_RULES.put("RULES", List.of("rule", "rules", "规则", "规范"));
        KEYWORD_RULES.put("TEMPLATE", List.of("template", "脚手架", "模板"));
        KEYWORD_RULES.put("SKILL", List.of("skill", "技能"));
    }

    private CategoryInferrer() {
    }

    /**
     * @param text 资源名（可附带简介，简介里的线索同样有效）
     * @return 推断出的分类；无线索时返回 null（由调用方回退）
     */
    public static String infer(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String haystack = text.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> rule : SUFFIX_RULES.entrySet()) {
            if (haystack.endsWith(rule.getKey())) {
                return rule.getValue();
            }
        }
        for (Map.Entry<String, List<String>> rule : KEYWORD_RULES.entrySet()) {
            for (String keyword : rule.getValue()) {
                if (haystack.contains(keyword)) {
                    return rule.getKey();
                }
            }
        }
        return null;
    }
}
