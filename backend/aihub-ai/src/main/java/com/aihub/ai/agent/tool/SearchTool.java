package com.aihub.ai.agent.tool;

import com.aihub.api.dto.AssetQueryItem;
import com.aihub.api.search.SearchQueryApi;
import com.aihub.common.result.PageResult;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Search Tool：检索资源（docs/AGENT.md §2）。
 *
 * <p>数据来源是 Search Service 的内部查询契约（ES 全文检索），以匿名视角执行，
 * 因此只会看到已发布资源。
 */
@Component
public class SearchTool implements AgentToolBean {

    private static final int MAX_PAGE_SIZE = 20;

    private final SearchQueryApi searchQueryApi;

    public SearchTool(SearchQueryApi searchQueryApi) {
        this.searchQueryApi = searchQueryApi;
    }

    /**
     * 检索参数。
     *
     * <p>所有字段都可空：关键字与分类是可选过滤条件，分页不传时按默认值处理。
     * 长度上限来自模型，可能被注入内容引导成超长字符串。
     */
    public record SearchAssetsArgs(
            @ToolParam(required = false, description = "搜索关键字，全文检索：匹配资源名称、简介与 README 正文。可为空表示不加关键字过滤")
            @Size(max = 100, message = "keyword must be at most 100 characters")
            String keyword,

            @ToolParam(required = false,
                    description = "资源分类，取值 SKILL / HOOK / PROMPT / MCP / RULES / TEMPLATE / OTHER。可为空表示不限分类")
            @Pattern(regexp = "(?i)SKILL|HOOK|PROMPT|MCP|RULES|TEMPLATE|OTHER",
                    message = "category must be one of SKILL, HOOK, PROMPT, MCP, RULES, TEMPLATE, OTHER")
            String category,

            @ToolParam(required = false, description = "标签筛选，精确匹配。可为空表示不限标签")
            @Size(max = 20, message = "tag must be at most 20 characters")
            String tag,

            @ToolParam(required = false, description = "页码，从 1 开始。默认 1")
            @Min(value = 1, message = "page must be greater than or equal to 1")
            Integer page,

            @ToolParam(required = false, description = "每页条数，1-20。默认 5")
            @Min(value = 1, message = "size must be greater than or equal to 1")
            @Max(value = MAX_PAGE_SIZE, message = "size must be less than or equal to 20")
            Integer size
    ) {
    }

    @Tool(name = "search_assets",
            description = """
                    在资源仓库中检索已发布的 AI 资源（Skill / Hook / Prompt / MCP / Rules / Template）。
                    用途：当用户描述「想找某类资源」时先调用本工具拿到候选列表，再对感兴趣的资源调用 get_asset_detail。
                    参数：keyword 关键字（可空，做全文检索：匹配名称、简介与 README 正文）；category 分类（可空）；tag 标签精确匹配（可空）；page 页码从 1 开始；size 每页条数 1-20。
                    返回：{total, page, size, items:[{assetId, name, summary, category, latestVersion}]}，按相关度排序；total 为 0 表示没有匹配资源。
                    不使用场景：已经知道 assetId 时直接用 get_asset_detail，不要先搜索；
                    想列出某个用户上传过哪些资源时本工具做不到（它没有按作者过滤的能力），请如实告知。
                    """)
    public ToolResult searchAssets(SearchAssetsArgs args) {
        int page = args.page() == null ? 1 : args.page();
        int size = args.size() == null ? 5 : args.size();
        PageResult<AssetQueryItem> result = RemoteResults.unwrap(
                searchQueryApi.search(args.keyword(), args.category(), args.tag(), page, size),
                "search assets");

        List<Map<String, Object>> items = new ArrayList<>(result.records().size());
        for (AssetQueryItem item : result.records()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("assetId", item.id());
            row.put("name", item.name());
            row.put("summary", item.summary());
            row.put("category", item.category());
            row.put("latestVersion", item.latestVersion());
            items.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", result.total());
        data.put("page", result.page());
        data.put("size", result.size());
        data.put("items", items);

        String message = items.isEmpty()
                ? "没有找到匹配的资源"
                : "找到 " + result.total() + " 条资源，本页返回 " + items.size() + " 条";
        return ToolResult.success(message, data);
    }
}
