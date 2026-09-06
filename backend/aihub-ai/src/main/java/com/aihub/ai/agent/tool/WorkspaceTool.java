package com.aihub.ai.agent.tool;

import com.aihub.api.asset.AssetQueryApi;
import com.aihub.api.dto.AssetQueryDetail;
import com.aihub.api.dto.AssetQueryVersion;
import com.aihub.api.dto.DependencyNodeItem;
import com.aihub.common.exception.BizException;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Workspace Tool：把若干资源组合成一个 Workspace 草案（docs/AGENT.md §2 / §3）。
 *
 * <p>只出草案，不落库。Workspace 的数据归 User Service（docs/ARCHITECTURE.md §2），
 * AI Service 不经用户确认不得落库；User Service 侧的写入接口尚未实现，
 * 因此本工具只产出结构化草案。性质是 {@link AgentToolNature#READ}，不产生副作用；
 * 真正的「保存 Workspace」将来由独立的需确认 Tool 承担。
 */
@Component
public class WorkspaceTool implements AgentToolBean {

    private static final int MAX_ASSETS = 10;

    private final AssetQueryApi assetQueryApi;

    public WorkspaceTool(AssetQueryApi assetQueryApi) {
        this.assetQueryApi = assetQueryApi;
    }

    public record BuildWorkspaceDraftArgs(
            @ToolParam(description = "要纳入 Workspace 的资源 ID 列表，最多 10 个")
            @NotEmpty(message = "assetIds must not be empty")
            @Size(max = MAX_ASSETS, message = "assetIds must contain at most 10 items")
            List<@NotNull(message = "assetId must not be null") @Positive(message = "assetId must be positive") Long> assetIds,

            @ToolParam(required = false, description = "Workspace 名称，可为空")
            @Size(max = 100, message = "title must be at most 100 characters")
            String title,

            @ToolParam(required = false, description = "Workspace 简介：这套组合面向什么场景、包含什么、怎么用，一两句话（保存时会随 Workspace 落库展示）")
            @Size(max = 500, message = "description must be at most 500 characters")
            String description
    ) {
    }

    @Tool(name = "build_workspace_draft",
            description = """
                    根据一组资源 ID 生成 Workspace 草案：解析每个资源的可用版本、下载地址与依赖树。
                    用途：用户希望「把某些资源组合起来一起使用」时使用，为后续保存 Workspace 准备内容。
                    参数：assetIds 资源 ID 列表（必填，1-10 个）；title 草案名称（可空）；
                    description 简介一两句话（可空但强烈建议提供：面向场景 + 包含什么，保存后会展示在 Workspace 列表）。
                    返回：{title, items:[{assetId, name, version, size, downloadUrl}], dependencies:[{assetId, name, depth}],
                          failed:[{assetId, reason}], notes:[...]}。
                          failed 中的资源无法解析（不存在 / 无可用版本），必须如实告知用户，不要当成成功。
                    不使用场景：只是想下载单个资源时用 prepare_download；
                    本工具只生成草案、不保存（保存需要用户确认，尚未实现），因此不要声称 Workspace 已创建。
                    """)
    public ToolResult buildWorkspaceDraft(BuildWorkspaceDraftArgs args) {
        List<Map<String, Object>> items = new ArrayList<>();
        List<Map<String, Object>> dependencies = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        for (Long assetId : args.assetIds()) {
            try {
                AssetQueryDetail detail = RemoteResults.unwrap(assetQueryApi.detail(assetId), "build workspace draft");
                Map<String, Object> item = toItem(detail);
                if (item == null) {
                    failed.add(reason(assetId, "该资源没有可下载的版本"));
                    continue;
                }
                items.add(item);

                List<DependencyNodeItem> tree = RemoteResults.unwrap(
                        assetQueryApi.dependencyTree(assetId), "load dependency tree");
                collectDependencies(tree, 1, dependencies, notes);
            } catch (BizException e) {
                // 单个资源失败不放弃整份草案：docs/AGENT.md §5 要求区分「已完成 / 失败 / 失败原因」
                failed.add(reason(assetId, e.getMessage()));
            }
        }

        if (!dependencies.isEmpty()) {
            notes.add("依赖为传递依赖展开结果，depth 从 1 开始");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title", args.title());
        data.put("description", args.description());
        data.put("items", items);
        data.put("dependencies", dependencies);
        data.put("failed", failed);
        data.put("notes", notes);

        String message = "草案已生成：" + items.size() + " 个资源、" + dependencies.size() + " 项依赖"
                + (failed.isEmpty() ? "" : "，" + failed.size() + " 个资源无法解析");
        return ToolResult.success(message, data);
    }

    private Map<String, Object> toItem(AssetQueryDetail detail) {
        AssetQueryVersion chosen = null;
        if (detail.latestVersion() != null) {
            chosen = detail.versions().stream()
                    .filter(v -> detail.latestVersion().equals(v.version()) && v.downloadable())
                    .findFirst()
                    .orElse(null);
        }
        if (chosen == null) {
            chosen = detail.versions().stream().filter(AssetQueryVersion::downloadable).findFirst().orElse(null);
        }
        if (chosen == null) {
            return null;
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("assetId", detail.id());
        item.put("name", detail.name());
        item.put("version", chosen.version());
        item.put("size", chosen.size());
        item.put("downloadUrl", chosen.downloadUrl());
        return item;
    }

    private void collectDependencies(List<DependencyNodeItem> nodes, int depth,
                                     List<Map<String, Object>> collector, List<String> notes) {
        for (DependencyNodeItem node : nodes) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("assetId", node.assetId());
            row.put("name", node.name());
            row.put("depth", depth);
            if (node.cycle()) {
                // 依赖成环是数据异常，必须显式提示而不是画成一棵正常的树
                row.put("cycle", true);
                notes.add("检测到依赖成环：assetId=" + node.assetId() + "，该分支不再展开");
            }
            collector.add(row);
            if (!node.cycle() && node.children() != null && !node.children().isEmpty()) {
                collectDependencies(node.children(), depth + 1, collector, notes);
            }
        }
    }

    private Map<String, Object> reason(Long assetId, String reason) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("assetId", assetId);
        row.put("reason", reason);
        return row;
    }
}
