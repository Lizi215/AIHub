package com.aihub.ai.agent.tool;

import com.aihub.api.asset.AssetQueryApi;
import com.aihub.api.asset.AssetTagCommandApi;
import com.aihub.api.dto.TagUpdateCommand;
import com.aihub.api.dto.TagUpdateResult;
import com.aihub.common.result.Result;
import com.aihub.common.trace.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TagTool}：写操作的目标解析与标签落库语义。
 *
 * <p>重点是两类误用：给别人的资源打标签（无身份或越权必须拒绝），以及推断标签时
 * 每个资源拿到不同标签（assignments 形态）。
 */
class TagToolTest {

    private static final long USER_ID = 7L;
    private static final long ASSET_A = 100L;
    private static final long ASSET_B = 200L;

    private AssetTagCommandApi assetTagCommandApi;
    private AssetQueryApi assetQueryApi;
    private TagTool tool;

    @BeforeEach
    void setUp() {
        assetTagCommandApi = mock(AssetTagCommandApi.class);
        assetQueryApi = mock(AssetQueryApi.class);
        tool = new TagTool(assetTagCommandApi, assetQueryApi);
        UserContext.set(USER_ID, "USER");
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private void stubApply(Long assetId, List<String> finalTags) {
        when(assetTagCommandApi.applyTags(eq(assetId), any(TagUpdateCommand.class)))
                .thenReturn(Result.ok(new TagUpdateResult(assetId, "asset-" + assetId, finalTags)));
    }

    @Test
    @DisplayName("按资源分别指定标签：每个资源拿到自己的标签（推断场景）")
    void shouldApplyPerAssetAssignments() {
        stubApply(ASSET_A, List.of("ai", "工具"));
        stubApply(ASSET_B, List.of("文档"));

        ToolResult result = tool.updateAssetTags(new TagTool.UpdateAssetTagsArgs(
                null, null, null,
                List.of(new TagTool.AssignmentArgs(ASSET_A, List.of("ai", "工具")),
                        new TagTool.AssignmentArgs(ASSET_B, List.of("文档"))),
                "APPEND"));

        assertThat(result.success()).isTrue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> updated = (List<Map<String, Object>>) ((Map<String, Object>) result.data()).get("updated");
        assertThat(updated).hasSize(2);

        ArgumentCaptor<TagUpdateCommand> captor = ArgumentCaptor.forClass(TagUpdateCommand.class);
        verify(assetTagCommandApi, times(2)).applyTags(any(), captor.capture());
        List<TagUpdateCommand> commands = captor.getAllValues();
        // ownerId 来自任务线程身份，不是模型参数
        assertThat(commands).allSatisfy(c -> assertThat(c.ownerId()).isEqualTo(USER_ID));
        assertThat(commands).anySatisfy(c -> assertThat(c.tags()).containsExactly("ai", "工具"));
        assertThat(commands).anySatisfy(c -> assertThat(c.tags()).containsExactly("文档"));
    }

    @Test
    @DisplayName("统一标签 + allMine：按我的资源逐个调用，且不带标签时不写库")
    void shouldApplyUniformTagsToMyAssets() {
        when(assetQueryApi.mine(USER_ID)).thenReturn(Result.ok(List.of(
                new com.aihub.api.dto.AssetQueryItem(ASSET_A, USER_ID, "a", null, "SKILL", "PUBLISHED", "1.0.0"),
                new com.aihub.api.dto.AssetQueryItem(ASSET_B, USER_ID, "b", null, "SKILL", "PUBLISHED", "1.0.0"))));
        stubApply(ASSET_A, List.of("demo"));
        stubApply(ASSET_B, List.of("demo"));

        tool.updateAssetTags(new TagTool.UpdateAssetTagsArgs(
                null, true, List.of("demo"), null, null));

        verify(assetTagCommandApi, times(2)).applyTags(any(), any());

        ToolResult empty = tool.updateAssetTags(new TagTool.UpdateAssetTagsArgs(null, true, List.of(), null, "APPEND"));
        assertThat(empty.message()).contains("没有提供标签");
        verify(assetTagCommandApi, never()).applyTags(eq(9999L), any());
    }

    @Test
    @DisplayName("没有身份时不写库：如实说明，而不是猜一个 owner")
    void shouldRefuseWithoutIdentity() {
        UserContext.clear();

        ToolResult result = tool.updateAssetTags(new TagTool.UpdateAssetTagsArgs(
                List.of(ASSET_A), null, List.of("ai"), null, "APPEND"));

        assertThat(result.message()).contains("无法识别用户身份");
        verify(assetTagCommandApi, never()).applyTags(any(), any());
    }
}
