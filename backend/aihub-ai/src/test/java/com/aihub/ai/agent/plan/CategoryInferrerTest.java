package com.aihub.ai.agent.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CategoryInferrer}：按名字线索确定性地推断资源分类。
 *
 * <p>这套规则存在的原因：让模型判类型不可靠（提示词写了判据也会一律给同一个值），
 * 分类是有客观线索的判断，就该用规则做。
 */
class CategoryInferrerTest {

    @Test
    @DisplayName("后缀规则最强：-mcp → MCP，-skill → SKILL")
    void shouldUseSuffixRules() {
        assertThat(CategoryInferrer.infer("filesystem-mcp")).isEqualTo("MCP");
        assertThat(CategoryInferrer.infer("pdf-skill")).isEqualTo("SKILL");
        assertThat(CategoryInferrer.infer("deploy-hook")).isEqualTo("HOOK");
        assertThat(CategoryInferrer.infer("code-review-prompt")).isEqualTo("PROMPT");
        assertThat(CategoryInferrer.infer("team-rules")).isEqualTo("RULES");
        assertThat(CategoryInferrer.infer("spring-template")).isEqualTo("TEMPLATE");
    }

    @Test
    @DisplayName("后缀优先于关键词：mcp-builder-skill 是「造 MCP 的技能」→ SKILL 而非 MCP")
    void suffixShouldBeatKeyword() {
        assertThat(CategoryInferrer.infer("mcp-builder-skill")).isEqualTo("SKILL");
    }

    @Test
    @DisplayName("关键词规则：中文与英文都认")
    void shouldUseKeywordRules() {
        assertThat(CategoryInferrer.infer("MCP Server 工具集")).isEqualTo("MCP");
        assertThat(CategoryInferrer.infer("自动拦截钩子")).isEqualTo("HOOK");
        assertThat(CategoryInferrer.infer("提示词大全")).isEqualTo("PROMPT");
        assertThat(CategoryInferrer.infer("团队编码规范")).isEqualTo("RULES");
        assertThat(CategoryInferrer.infer("项目脚手架")).isEqualTo("TEMPLATE");
        assertThat(CategoryInferrer.infer("my skill")).isEqualTo("SKILL");
    }

    @Test
    @DisplayName("大小写不敏感；无线索返回 null（交给调用方回退，而不是硬塞一个类别）")
    void shouldBeCaseInsensitiveAndReturnNullWhenNoClue() {
        assertThat(CategoryInferrer.infer("PDF-SKILL")).isEqualTo("SKILL");
        assertThat(CategoryInferrer.infer("demo-combo")).isNull();
        assertThat(CategoryInferrer.infer("")).isNull();
        assertThat(CategoryInferrer.infer(null)).isNull();
    }
}
