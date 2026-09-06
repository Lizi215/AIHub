package com.aihub.ai.agent.tool;

import com.aihub.ai.agent.context.PromptInjectionSanitizer;
import com.aihub.ai.config.AgentProperties;
import com.aihub.api.asset.AssetQueryApi;
import com.aihub.api.community.CommunityQueryApi;
import com.aihub.api.search.SearchQueryApi;
import com.aihub.api.dto.AssetQueryItem;
import com.aihub.common.exception.BizException;
import com.aihub.common.result.ErrorCode;
import com.aihub.common.result.PageResult;
import com.aihub.common.result.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tool 注册表：性质登记的完整性、未知工具拒绝、参数校验。
 *
 * <p>这里用真实的 Tool Bean（只 mock 它们背后的 Feign 契约），因为要验证的正是
 * 真实工具集合与注册表、校验规则是否一致。
 */
class AgentToolCatalogTest {

    private AssetQueryApi assetQueryApi;
    private SearchQueryApi searchQueryApi;
    private AgentToolCatalog catalog;

    @BeforeEach
    void setUp() {
        assetQueryApi = mock(AssetQueryApi.class);
        searchQueryApi = mock(SearchQueryApi.class);
        CommunityQueryApi communityQueryApi = mock(CommunityQueryApi.class);
        AgentProperties properties = new AgentProperties();
        PromptInjectionSanitizer sanitizer = new PromptInjectionSanitizer();
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        List<AgentToolBean> beans = List.of(
                new SearchTool(searchQueryApi),
                new AssetTool(assetQueryApi, sanitizer, properties),
                new DownloadTool(assetQueryApi),
                new WorkspaceTool(assetQueryApi),
                new UploadTool(assetQueryApi),
                new TagTool(mock(com.aihub.api.asset.AssetTagCommandApi.class), assetQueryApi),
                new CommunityTool(communityQueryApi, sanitizer, properties));
        catalog = new AgentToolCatalog(beans, new ObjectMapper(), validator);
    }

    @Test
    @DisplayName("注册表覆盖全部初始 Tool（docs/AGENT.md §2）")
    void shouldRegisterAllInitialTools() {
        assertThat(catalog.names()).containsExactlyInAnyOrder(
                "search_assets", "get_asset_detail", "prepare_download",
                "build_workspace_draft", "analyze_upload_candidate", "analyze_upload_batch",
                "list_my_assets", "update_asset_tags", "list_issues", "list_posts");
    }

    @Test
    @DisplayName("同一份定义也能交给 Spring AI：@Tool 方法可被解析成 ToolCallback")
    void shouldExposeSpringAiCallbacks() {
        assertThat(catalog.springAiCallbacks()).hasSize(catalog.names().size());
        assertThat(catalog.promptToolCatalog())
                .contains("search_assets").contains("prepare_download").contains("inputSchema");
    }

    @Test
    @DisplayName("性质登记：只有下载类工具需要确认，只读工具不需要")
    void shouldDeclareConfirmationNatures() {
        assertThat(catalog.requiresConfirmation("prepare_download")).isTrue();
        for (String readOnly : List.of("search_assets", "get_asset_detail", "list_issues",
                "list_posts", "analyze_upload_candidate", "build_workspace_draft")) {
            assertThat(catalog.requiresConfirmation(readOnly))
                    .as("%s should not require confirmation", readOnly)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("模型编造的工具名必须被拒绝，而不是被忽略")
    void shouldRejectUnknownTool() {
        assertThatThrownBy(() -> catalog.invoke("rm_rf_everything", new ObjectMapper().createObjectNode()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.AGENT_TOOL_NOT_FOUND);
    }

    @Test
    @DisplayName("参数越界被拒绝：模型给的 size 超出上限")
    void shouldRejectOutOfRangeParam() {
        assertThatThrownBy(() -> catalog.invoke("search_assets",
                new ObjectMapper().valueToTree(Map.of("size", 999))))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("必填参数缺失被拒绝")
    void shouldRejectMissingRequiredParam() {
        assertThatThrownBy(() -> catalog.invoke("get_asset_detail",
                new ObjectMapper().createObjectNode()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("参数类型不合法被拒绝（字符串当作数字）")
    void shouldRejectWrongParamType() {
        assertThatThrownBy(() -> catalog.invoke("get_asset_detail",
                new ObjectMapper().valueToTree(Map.of("assetId", "not-a-number"))))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("分类枚举非法取值被拒绝（避免拿垃圾值去查下游）")
    void shouldRejectUnknownCategory() {
        assertThatThrownBy(() -> catalog.invoke("search_assets",
                new ObjectMapper().valueToTree(Map.of("category", "NOT_A_CATEGORY"))))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("正常调用：返回 ToolResult 且结构化数据里有资源条目")
    void shouldInvokeToolSuccessfully() {
        when(searchQueryApi.search(eq("spring"), any(), any(), anyInt(), anyInt()))
                .thenReturn(Result.ok(PageResult.of(
                        List.of(new AssetQueryItem(100L, 1L, "Spring Skill", "summary", "SKILL", "PUBLISHED", "1.0.0")),
                        1, 1, 5)));

        ToolResult result = catalog.invoke("search_assets",
                new ObjectMapper().valueToTree(Map.of("keyword", "spring")));

        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("1 条资源");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.data();
        assertThat(data).containsKey("items");
    }

    @Test
    @DisplayName("下游业务失败被转换为 AI 域错误码，不透出下游错误码")
    void shouldConvertDownstreamFailure() {
        when(searchQueryApi.search(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Result.fail(ErrorCode.ASSET_NOT_FOUND, "asset not found"));

        assertThatThrownBy(() -> catalog.invoke("search_assets",
                new ObjectMapper().valueToTree(Map.of("keyword", "x"))))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.AGENT_TOOL_FAILED);
    }

    @Test
    @DisplayName("注册表启动校验：漏登记性质的 Tool 会让应用直接起不来，而不是默认可执行")
    void shouldFailFastWhenNatureMissing() {
        List<AgentToolBean> withRogue = new java.util.ArrayList<>(List.of(
                new SearchTool(searchQueryApi), new DownloadTool(assetQueryApi), new RogueTool()));
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        assertThatThrownBy(() -> new AgentToolCatalog(withRogue, new ObjectMapper(), validator))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rogue_tool");
    }

    /** 用于验证启动校验的「未登记性质」工具。 */
    static class RogueTool implements AgentToolBean {

        public record Args(String value) {
        }

        @org.springframework.ai.tool.annotation.Tool(name = "rogue_tool", description = "未在性质表中登记的测试工具")
        public ToolResult run(Args args) {
            return ToolResult.success("never reached", null);
        }
    }
}
