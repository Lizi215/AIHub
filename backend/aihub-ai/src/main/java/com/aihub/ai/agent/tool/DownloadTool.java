package com.aihub.ai.agent.tool;

import com.aihub.api.asset.AssetQueryApi;
import com.aihub.api.dto.AssetQueryDetail;
import com.aihub.api.dto.AssetQueryVersion;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Download Tool：解析可下载目标（docs/AGENT.md §2）。
 *
 * <p>本工具不搬运文件字节，只向 Asset Service 取版本元数据，产出「下哪个资源、
 * 哪个版本、多大、从哪下」；字节由用户经浏览器 → Gateway → Asset → File → MinIO 获取，
 * AI 服务不驻留内容，也不在下载链路上。
 *
 * <p>性质是 {@link AgentToolNature#CONFIRM}：真实下载会消耗带宽、写入下载记录、
 * 影响热门榜，必须先经用户确认，因此只在确认之后才被 Orchestrator 放行。
 */
@Component
public class DownloadTool implements AgentToolBean {

    private final AssetQueryApi assetQueryApi;

    public DownloadTool(AssetQueryApi assetQueryApi) {
        this.assetQueryApi = assetQueryApi;
    }

    public record PrepareDownloadArgs(
            @ToolParam(description = "资源 ID")
            @NotNull(message = "assetId is required")
            @Positive(message = "assetId must be positive")
            Long assetId,

            @ToolParam(required = false, description = "版本号，例如 1.0.0。不传表示最新版本")
            @Size(max = 50, message = "version must be at most 50 characters")
            String version
    ) {
    }

    @Tool(name = "prepare_download",
            description = """
                    为某个资源解析出可下载目标（最新版或指定版本），返回下载地址与文件信息。
                    用途：用户要求下载资源时使用；本工具返回的是"下载目标"，文件字节由用户点击下载地址获取，本工具不传输字节。
                    参数：assetId 资源 ID（必填）；version 版本号（可空，不传取最新版本）。
                    返回：{assetId, name, version, size, checksum, downloadUrl, downloaded:false}；
                          失败时 success=false 并说明原因（版本不存在 / 该版本尚未发布 / 文件元数据缺失）。
                    不使用场景：用户只是浏览或比较资源时用 get_asset_detail，不要调用本工具；
                    本工具属于需确认操作，确认之前系统会拒绝执行，不要反复重试。
                    """)
    public ToolResult prepareDownload(PrepareDownloadArgs args) {
        AssetQueryDetail detail = RemoteResults.unwrap(assetQueryApi.detail(args.assetId()), "prepare download");

        Optional<AssetQueryVersion> target = selectVersion(detail, args.version());
        if (target.isEmpty()) {
            return ToolResult.failure(args.version() == null
                    ? "资源「" + detail.name() + "」没有任何版本，无法下载"
                    : "资源「" + detail.name() + "」不存在版本 " + args.version());
        }
        AssetQueryVersion version = target.get();
        if (!version.downloadable()) {
            // 计划阶段就把「不可下载」讲清楚，而不是让用户在确认之后才看到失败
            return ToolResult.failure("资源「" + detail.name() + "」版本 " + version.version()
                    + " 当前不可下载（资源未发布或该版本尚未发布）");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("assetId", detail.id());
        data.put("name", detail.name());
        data.put("version", version.version());
        data.put("size", version.size());
        data.put("checksum", version.checksum());
        data.put("downloadUrl", version.downloadUrl());
        // 明确写出来，避免模型以为"已下载完成"
        data.put("downloaded", false);

        return ToolResult.success("已解析下载目标：" + detail.name() + " " + version.version()
                + "（" + version.size() + " 字节）", data);
    }

    /** 指定版本优先；未指定时取资源的最新版本，最新版本不可下载则退回第一个可下载版本。 */
    private Optional<AssetQueryVersion> selectVersion(AssetQueryDetail detail, String requested) {
        if (requested != null && !requested.isBlank()) {
            String trimmed = requested.trim();
            return detail.versions().stream()
                    .filter(v -> trimmed.equals(v.version()))
                    .findFirst();
        }
        if (detail.latestVersion() != null) {
            Optional<AssetQueryVersion> latest = detail.versions().stream()
                    .filter(v -> detail.latestVersion().equals(v.version()))
                    .findFirst();
            if (latest.isPresent()) {
                return latest;
            }
        }
        return detail.versions().stream().filter(AssetQueryVersion::downloadable).findFirst();
    }
}
