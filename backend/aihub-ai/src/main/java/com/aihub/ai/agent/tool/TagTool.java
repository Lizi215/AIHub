package com.aihub.ai.agent.tool;

import com.aihub.api.asset.AssetQueryApi;
import com.aihub.api.asset.AssetTagCommandApi;
import com.aihub.api.dto.AssetQueryItem;
import com.aihub.api.dto.TagUpdateCommand;
import com.aihub.api.dto.TagUpdateResult;
import com.aihub.common.trace.UserContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tag Tool：给资源打标签（docs/AGENT.md §2）。
 *
 * <p>这是写操作，性质登记为 CONFIRM（{@code AgentToolCatalog}）：未经用户确认
 * Orchestrator 会硬拒绝执行。落库在 Asset Service（AI 服务不写表），owner 身份
 * 取自任务行（UserContext），模型无法指定别人的资源。
 *
 * <p>目标是显式 assetIds（≤20）或 {@code allMine=true}（当前用户全部资源，≤20）。
 * 默认追加，用户说「加上某标签」时不应抹掉原有标签。
 */
@Component
public class TagTool implements AgentToolBean {

    /** 一次调用最多影响多少个资源，防止模型一句「给我的所有资源打标签」横扫全库 */
    private static final int MAX_ASSETS = 20;

    private final AssetTagCommandApi assetTagCommandApi;
    private final AssetQueryApi assetQueryApi;

    public TagTool(AssetTagCommandApi assetTagCommandApi, AssetQueryApi assetQueryApi) {
        this.assetTagCommandApi = assetTagCommandApi;
        this.assetQueryApi = assetQueryApi;
    }

    public record AssignmentArgs(Long assetId, List<String> tags) {
    }

    public record UpdateAssetTagsArgs(
            @ToolParam(required = false, description = "目标资源 ID 列表（来自 search_assets / list_my_assets 的返回），最多 20 个")
            List<Long> assetIds,

            @ToolParam(required = false, description = "true 表示当前用户的全部资源（最多处理 20 个）。与 assetIds 二选一")
            Boolean allMine,

            @ToolParam(required = false, description = "统一给所有目标资源打的标签（1-5 个）。与 assignments 二选一")
            List<String> tags,

            @ToolParam(required = false, description = """
                    按资源分别指定标签（推断场景用）：[{"assetId":123,"tags":["ai","工具"]}, ...]，
                    每个资源 1-5 个标签，最多 20 个资源。与 tags 二选一；
                    你自己分析标签时用这个，才能给不同资源不同的标签""")
            List<AssignmentArgs> assignments,

            @ToolParam(required = false, description = "APPEND（默认，并入已有标签）或 REPLACE（整组替换）。用户说\"改成\"用 REPLACE，说\"加上\"用 APPEND")
            String mode
    ) {
    }

    @Tool(name = "update_asset_tags",
            description = """
                    给资源打标签（写操作，需用户确认后才会真正执行）。
                    用途：用户要求「给某个/某些/我全部的资源配置标签」时使用。
                    参数：assetIds 指定资源（≤20，来自 search_assets 或 list_my_assets 的返回）；
                          或 allMine=true 表示"我的全部资源"（二选一，都缺省则视为 allMine）；
                          标签二选一：tags（统一一组）；assignments（**按资源分别给**，
                          你推断标签时用这个，例如 [{assetId:1,tags:["ai"]},{assetId:2,tags:["文档"]}]）；
                          tags 标签列表（1-5 个）；mode 默认 APPEND（并入），用户说「改成」时用 REPLACE。
                    返回：{updated:[{assetId, name, tags}], note}，其中 tags 是**最终**标签列表。
                    标签来源有两种，都允许：
                    ① 用户明确给了标签 → 照用（可在不改变语义的前提下做规范化，如去空格）；
                    ② 用户让你自己判断（「你分析一下适合什么标签」）→ **只用 list_my_assets
                       返回的名称与简介推断**，给每个资源 1-5 个标签，
                       并在 intent 里写清依据（如"简介提到工作流自动化 → 标签：自动化"）。
                       ⚠️ 禁止为打标签逐个调用 get_asset_detail：每资源一步会耗尽步数预算、
                       导致整个任务失败——简介里的信息已足够推断标签。
                    底线：标签必须来自**读到的真实内容**，禁止凭想象发明与内容无关的标签
                    （标签会进索引、参与搜索与筛选，是用户可见数据）。
                    不使用场景：只是查标签用 get_asset_detail；不要用它改资源名/简介。
                    """)
    public ToolResult updateAssetTags(UpdateAssetTagsArgs args) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return ToolResult.success("当前无法识别用户身份，未修改任何标签", Map.of("updated", List.of()));
        }
        boolean hasUniform = args.tags() != null && !args.tags().isEmpty();
        boolean hasAssignments = args.assignments() != null && !args.assignments().isEmpty();
        if (!hasUniform && !hasAssignments) {
            return ToolResult.success("没有提供标签，未做修改", Map.of("updated", List.of()));
        }

        // 按资源分别指定优先于统一标签，两种形态最终都归一成 assetId → tags
        Map<Long, List<String>> tagsByAsset = new LinkedHashMap<>();
        if (hasAssignments) {
            for (AssignmentArgs assignment : args.assignments().stream().limit(MAX_ASSETS).toList()) {
                if (assignment == null || assignment.assetId() == null
                        || assignment.tags() == null || assignment.tags().isEmpty()) {
                    continue;
                }
                tagsByAsset.put(assignment.assetId(),
                        assignment.tags().stream().limit(5).toList());
            }
        } else {
            for (AssetQueryItem item : resolveTargets(userId, args)) {
                tagsByAsset.put(item.id(), args.tags());
            }
        }
        if (tagsByAsset.isEmpty()) {
            return ToolResult.success("没有找到可打标签的资源（确认资源 id 或先用 list_my_assets 查一下）",
                    Map.of("updated", List.of()));
        }
        List<AssetQueryItem> targets = tagsByAsset.keySet().stream()
                .map(id -> new AssetQueryItem(id, userId, null, null, null, null, null))
                .toList();

        String mode = args.mode() == null || args.mode().isBlank()
                ? "APPEND" : args.mode().trim().toUpperCase(Locale.ROOT);
        List<Map<String, Object>> updated = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (AssetQueryItem item : targets) {
            try {
                TagUpdateResult result = RemoteResults.unwrap(
                        assetTagCommandApi.applyTags(item.id(),
                                new TagUpdateCommand(userId, tagsByAsset.get(item.id()), mode)),
                        "update asset tags");
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("assetId", result.assetId());
                row.put("name", result.assetName() == null ? item.name() : result.assetName());
                row.put("tags", result.tags());
                updated.add(row);
            } catch (Exception e) {
                // 单个资源失败不牵连其它资源：如实记下原因继续
                failures.add((item.name() == null ? String.valueOf(item.id()) : item.name())
                        + "：" + e.getMessage());
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("updated", updated);
        data.put("failures", failures);
        String message = "已为 " + updated.size() + " 个资源" + ("REPLACE".equals(mode) ? "替换" : "添加") + "标签"
                + (hasAssignments ? "（按资源分别指定）" : "；标签：" + String.join("、", args.tags()))
                + (failures.isEmpty() ? "" : "；失败 " + failures.size() + " 个：" + String.join(" | ", failures));
        return ToolResult.success(message, data);
    }

    /** 解析目标资源：显式 ids 优先，否则 allMine（缺省也按 allMine，这是唯一安全的默认范围）。 */
    private List<AssetQueryItem> resolveTargets(Long userId, UpdateAssetTagsArgs args) {
        if (args.assetIds() != null && !args.assetIds().isEmpty()) {
            List<Long> ids = args.assetIds().stream().filter(java.util.Objects::nonNull)
                    .distinct().limit(MAX_ASSETS).toList();
            List<AssetQueryItem> items = new ArrayList<>();
            for (Long id : ids) {
                // 不信任模型给的 id：逐个回查，确认资源存在且属于当前用户
                RemoteResults.unwrap(assetQueryApi.detail(id), "resolve tag target").id();
                items.add(new AssetQueryItem(id, userId, null, null, null, null, null));
            }
            return items;
        }
        List<AssetQueryItem> mine = RemoteResults.unwrap(assetQueryApi.mine(userId), "resolve my assets");
        return mine.stream().limit(MAX_ASSETS).toList();
    }
}
