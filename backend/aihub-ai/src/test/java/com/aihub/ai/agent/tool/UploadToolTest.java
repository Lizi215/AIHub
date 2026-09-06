package com.aihub.ai.agent.tool;

import com.aihub.api.asset.AssetQueryApi;
import com.aihub.api.dto.AssetQueryItem;
import com.aihub.common.result.PageResult;
import com.aihub.common.result.Result;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link UploadTool} 批量分析（analyze_upload_batch）的行为。
 */
class UploadToolTest {

    private AssetQueryApi assetQueryApi;
    private UploadTool tool;
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @BeforeEach
    void setUp() {
        assetQueryApi = mock(AssetQueryApi.class);
        tool = new UploadTool(assetQueryApi);
    }

    private void stubSearchEmpty() {
        when(assetQueryApi.search(any(), isNull(), isNull(), anyInt(), anyInt()))
                .thenReturn(Result.ok(PageResult.of(List.of(), 0L, 1, 10)));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> suggestions(ToolResult result) {
        return (List<Map<String, Object>>) ((Map<String, Object>) result.data()).get("suggestions");
    }

    @Nested
    @DisplayName("analyze_upload_batch")
    class Batch {

        @Test
        @DisplayName("多候选：逐个给出分类/标签建议，readme 原样透传并标注 ai-draft 来源")
        void shouldAnalyzeEachCandidate() {
            stubSearchEmpty();

            ToolResult result = tool.analyzeUploadBatch(new UploadTool.AnalyzeUploadBatchArgs(List.of(
                    new UploadTool.BatchSkillArgs("code-review skill", null, null, "# code-review\n\n用法……", null),
                    new UploadTool.BatchSkillArgs("deploy-hook", "SKILL", "部署钩子资源简介", null, null))));

            List<Map<String, Object>> rows = suggestions(result);
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).get("name")).isEqualTo("code-review skill");
            // "skill" 命中关键词规则 → SKILL
            assertThat(rows.get(0).get("inferredCategory")).isEqualTo("SKILL");
            assertThat(rows.get(0).get("readmeSource")).isEqualTo("ai-draft");
            assertThat((String) rows.get(0).get("readme")).contains("code-review");
            // 无 readme → 来源 none，不造一个出来
            assertThat(rows.get(1).get("readmeSource")).isEqualTo("none");
            assertThat(rows.get(1).get("readme")).isNull();
            assertThat((Boolean) rows.get(0).get("batchDuplicate")).isFalse();
        }

        @Test
        @DisplayName("与自己已发布资源同名 → exactNameDuplicate=true")
        void shouldFlagExactDuplicate() {
            com.aihub.common.trace.UserContext.set(1L, "USER");
            try {
                when(assetQueryApi.search(any(), isNull(), isNull(), anyInt(), anyInt()))
                        .thenReturn(Result.ok(PageResult.of(List.of(
                                new AssetQueryItem(900L, 1L, "spring skill", null, "SKILL", "PUBLISHED", "1.0.0")),
                                1L, 1, 10)));

                ToolResult result = tool.analyzeUploadBatch(new UploadTool.AnalyzeUploadBatchArgs(List.of(
                        new UploadTool.BatchSkillArgs("spring skill", null, null, null, null))));

                assertThat((Boolean) suggestions(result).get(0).get("exactNameDuplicate")).isTrue();
                assertThat((Boolean) suggestions(result).get(0).get("otherAuthorSameName")).isFalse();
            } finally {
                com.aihub.common.trace.UserContext.clear();
            }
        }

        @Test
        @DisplayName("模型撰写的 suggestedTags 优先于关键词启发式，并清洗（去全角空格/去重/限 3 个）")
        void shouldPreferModelSuggestedTags() {
            when(assetQueryApi.search(any(), isNull(), isNull(), anyInt(), anyInt()))
                    .thenReturn(Result.ok(PageResult.of(java.util.Collections.<com.aihub.api.dto.AssetQueryItem>emptyList(), 0L, 1, 10)));
            ToolResult result = tool.analyzeUploadBatch(new UploadTool.AnalyzeUploadBatchArgs(List.of(
                    new UploadTool.BatchSkillArgs("memory-mcp", "MCP", "记忆与上下文管理工具", null,
                            List.of("　记忆 ", "记忆", "上下文", "跨会话", "多余")))));

            Map<String, Object> row = suggestions(result).get(0);
            @SuppressWarnings("unchecked")
            List<String> tags = (List<String>) row.get("suggestedTags");
            // 全角空格清洗后与"记忆"去重；最多 3 个
            assertThat(tags).containsExactly("记忆", "上下文", "跨会话");
            assertThat(row.get("suggestedTagsSource")).isEqualTo("ai");
        }

        @Test
        @DisplayName("自己有同名【草稿】也算同名（search 查不到草稿，靠 mine 兜底）→ sameNameAssetId 指向草稿")
        void shouldDetectOwnDraftSameName() {
            com.aihub.common.trace.UserContext.set(1L, "USER");
            try {
                when(assetQueryApi.search(any(), isNull(), isNull(), anyInt(), anyInt()))
                        .thenReturn(Result.ok(PageResult.of(java.util.Collections
                                .<com.aihub.api.dto.AssetQueryItem>emptyList(), 0L, 1, 10)));
                // 自己的草稿（DRAFT 不进 ES，只有 mine 能看到）
                when(assetQueryApi.mine(1L))
                        .thenReturn(Result.ok(List.of(new AssetQueryItem(
                                950L, 1L, "my-draft-skill", null, "SKILL", "DRAFT", "1.0.0"))));

                ToolResult result = tool.analyzeUploadBatch(new UploadTool.AnalyzeUploadBatchArgs(List.of(
                        new UploadTool.BatchSkillArgs("my-draft-skill", null, null, null, null))));

                Map<String, Object> row = suggestions(result).get(0);
                assertThat(row.get("sameNameAssetId")).isEqualTo(950L);
                assertThat((Boolean) row.get("exactNameDuplicate")).isTrue();
                // 状态透传，前端据此区分「同名草稿」与「已发布同名」
                assertThat(row.get("sameNameStatus")).isEqualTo("DRAFT");
            } finally {
                com.aihub.common.trace.UserContext.clear();
            }
        }

        @Test
        @DisplayName("模型没给标签 → 回退关键词启发式，source=keyword")
        void shouldFallbackToKeywordTags() {
            when(assetQueryApi.search(any(), isNull(), isNull(), anyInt(), anyInt()))
                    .thenReturn(Result.ok(PageResult.of(java.util.Collections.<com.aihub.api.dto.AssetQueryItem>emptyList(), 0L, 1, 10)));
            ToolResult result = tool.analyzeUploadBatch(new UploadTool.AnalyzeUploadBatchArgs(List.of(
                    new UploadTool.BatchSkillArgs("memory-mcp", "MCP", null, null, null))));

            Map<String, Object> row = suggestions(result).get(0);
            assertThat((List<?>) row.get("suggestedTags")).isNotEmpty();
            assertThat(row.get("suggestedTagsSource")).isEqualTo("keyword");
        }

        @Test
        @DisplayName("与自己同名时返回 sameNameAssetId：前端据此追加新版本而不是新建重复资源")
        void shouldExposeOwnSameNameAssetId() {
            com.aihub.common.trace.UserContext.set(1L, "USER");
            try {
                when(assetQueryApi.search(any(), isNull(), isNull(), anyInt(), anyInt()))
                        .thenReturn(Result.ok(PageResult.of(List.of(
                                new AssetQueryItem(900L, 1L, "spring skill", null, "SKILL", "PUBLISHED", "1.0.0")),
                                1L, 1, 10)));

                ToolResult result = tool.analyzeUploadBatch(new UploadTool.AnalyzeUploadBatchArgs(List.of(
                        new UploadTool.BatchSkillArgs("spring skill", null, null, null, null))));

                Map<String, Object> row = suggestions(result).get(0);
                assertThat(row.get("sameNameAssetId")).isEqualTo(900L);
                assertThat(row.get("sameNameLatestVersion")).isEqualTo("1.0.0");
                assertThat(row.get("sameNameStatus")).isEqualTo("PUBLISHED");
            } finally {
                com.aihub.common.trace.UserContext.clear();
            }
        }

        @Test
        @DisplayName("同名判断大小写不敏感且忽略首尾空白：「 SPRING SKILL 」命中已发布 spring skill")
        void shouldMatchSameNameCaseInsensitivelyAndTrimmed() {
            com.aihub.common.trace.UserContext.set(1L, "USER");
            try {
                when(assetQueryApi.search(any(), isNull(), isNull(), anyInt(), anyInt()))
                        .thenReturn(Result.ok(PageResult.of(List.of(
                                new AssetQueryItem(900L, 1L, "spring skill", null, "SKILL", "PUBLISHED", "1.0.0")),
                                1L, 1, 10)));
                when(assetQueryApi.mine(1L)).thenReturn(Result.ok(List.of()));

                ToolResult result = tool.analyzeUploadBatch(new UploadTool.AnalyzeUploadBatchArgs(List.of(
                        new UploadTool.BatchSkillArgs("  SPRING SKILL  ", null, null, null, null))));

                Map<String, Object> row = suggestions(result).get(0);
                assertThat((Boolean) row.get("exactNameDuplicate")).isTrue();
                assertThat(row.get("sameNameAssetId")).isEqualTo(900L);
                assertThat(row.get("sameNameStatus")).isEqualTo("PUBLISHED");
            } finally {
                com.aihub.common.trace.UserContext.clear();
            }
        }

        @Test
        @DisplayName("其他作者同名资源不算冲突：exactNameDuplicate=false, otherAuthorSameName=true")
        void shouldTreatOtherAuthorSameNameAsNormal() {
            com.aihub.common.trace.UserContext.set(1L, "USER");
            try {
                when(assetQueryApi.search(any(), isNull(), isNull(), anyInt(), anyInt()))
                        .thenReturn(Result.ok(PageResult.of(List.of(
                                // 同名但 owner 是别人（999）
                                new AssetQueryItem(900L, 999L, "spring skill", null, "SKILL", "PUBLISHED", "1.0.0")),
                                1L, 1, 10)));
                // analyzeName 也会查自己的资源（mine），该用例里自己没有同名
                when(assetQueryApi.mine(1L))
                        .thenReturn(Result.ok(List.of()));

                ToolResult result = tool.analyzeUploadBatch(new UploadTool.AnalyzeUploadBatchArgs(List.of(
                        new UploadTool.BatchSkillArgs("spring skill", null, null, null, null))));

                assertThat((Boolean) suggestions(result).get(0).get("exactNameDuplicate")).isFalse();
                assertThat((Boolean) suggestions(result).get(0).get("otherAuthorSameName")).isTrue();
            } finally {
                com.aihub.common.trace.UserContext.clear();
            }
        }

        @Test
        @DisplayName("批内重名：后者 batchDuplicate=true")
        void shouldFlagBatchDuplicate() {
            stubSearchEmpty();

            ToolResult result = tool.analyzeUploadBatch(new UploadTool.AnalyzeUploadBatchArgs(List.of(
                    new UploadTool.BatchSkillArgs("same-name", null, null, null, null),
                    new UploadTool.BatchSkillArgs("Same-Name", null, null, null, null))));

            assertThat((Boolean) suggestions(result).get(0).get("batchDuplicate")).isFalse();
            assertThat((Boolean) suggestions(result).get(1).get("batchDuplicate")).isTrue();
        }

        @Test
        @DisplayName("超 10 条：Jakarta 校验拒绝（校验发生在 catalog 调用层）")
        void shouldRejectMoreThanTen() {
            List<UploadTool.BatchSkillArgs> skills = new ArrayList<>();
            for (int i = 0; i < 11; i++) {
                skills.add(new UploadTool.BatchSkillArgs("skill-" + i, null, null, null, null));
            }
            assertThat(validator.validate(new UploadTool.AnalyzeUploadBatchArgs(skills))).isNotEmpty();
        }
    }
}
