package com.aihub.asset.controller;

import com.aihub.asset.dto.CreateAssetRequest;
import com.aihub.asset.dto.DeleteAssetVersionsRequest;
import com.aihub.asset.dto.UpdateAssetRequest;
import com.aihub.asset.service.AssetDependencyService;
import com.aihub.asset.service.AssetMetricsService;
import com.aihub.asset.service.AssetService;
import com.aihub.asset.vo.AssetDependencyVo;
import com.aihub.asset.vo.AssetDetailVo;
import com.aihub.asset.vo.AssetDownload;
import com.aihub.asset.vo.AssetVersionVo;
import com.aihub.asset.vo.AssetVo;
import com.aihub.asset.vo.DependencyNodeVo;
import com.aihub.asset.vo.HotAssetVo;
import com.aihub.common.auth.RequireLogin;
import com.aihub.common.exception.BizException;
import com.aihub.common.result.ErrorCode;
import com.aihub.common.result.PageQuery;
import com.aihub.common.result.PageResult;
import com.aihub.common.result.Result;
import com.aihub.common.trace.UserContext;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 资源接口。
 *
 * <p>写操作统一声明 {@link RequireLogin}：Gateway 已做第一道校验，这里是纵深防御。
 * 读取接口允许匿名，但会读取 {@link UserContext} 以便归属方看到自己的草稿。
 */
@RestController
@RequestMapping("/api/v1/assets")
public class AssetController {

    /** README 下载响应的内容类型。显式带 charset，避免中文 README 被当成非 UTF-8 */
    private static final MediaType MARKDOWN_UTF8 = MediaType.parseMediaType("text/markdown;charset=UTF-8");

    private final AssetService assetService;
    private final AssetDependencyService dependencyService;
    private final AssetMetricsService assetMetricsService;

    public AssetController(AssetService assetService,
                           AssetDependencyService dependencyService,
                           AssetMetricsService assetMetricsService) {
        this.assetService = assetService;
        this.dependencyService = dependencyService;
        this.assetMetricsService = assetMetricsService;
    }

    /* ------------------------------------------------------------ 资源主体 */

    /** 上传阶段①：创建草稿。 */
    @PostMapping
    @RequireLogin
    public Result<Long> create(@Valid @RequestBody CreateAssetRequest request) {
        return Result.ok(assetService.createDraft(request, UserContext.getUserId()));
    }

    /** 分页列表。page 从 1 开始（docs/API.md §4）。 */
    @GetMapping
    public Result<PageResult<AssetVo>> list(@RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer size,
                                            @RequestParam(required = false) String category,
                                            @RequestParam(required = false) Boolean onlyMine) {
        return Result.ok(assetService.list(new PageQuery(page, size), category,
                UserContext.getUserId(), onlyMine));
    }

    @GetMapping("/{id}")
    public Result<AssetDetailVo> detail(@PathVariable Long id) {
        return Result.ok(assetService.detail(id, UserContext.getUserId()));
    }

    /* ------------------------------------------------------------ 版本 */

    /** 上传阶段②：上传文件。字节经本服务中转给 File Service。 */
    @PostMapping(value = "/{id}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequireLogin
    public Result<AssetVersionVo> uploadVersion(@PathVariable Long id,
                                                @RequestPart("file") MultipartFile file,
                                                @RequestParam(required = false) String version,
                                                @RequestParam(required = false) String changelog,
                                                @RequestParam(required = false) String readme) {
        byte[] content = readBytes(file);
        return Result.ok(assetService.uploadVersion(
                id, UserContext.getUserId(), content, file.getContentType(), version, changelog, readme));
    }

    /** 上传阶段③：发布。 */
    @PutMapping("/{id}/publish")
    @RequireLogin
    public Result<Void> publish(@PathVariable Long id) {
        assetService.publish(id, UserContext.getUserId());
        return Result.ok();
    }

    /** 编辑元数据（名称 / 简介 / 分类）。owner only，越权由服务层判定。 */
    @PutMapping("/{id}")
    @RequireLogin
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody UpdateAssetRequest request) {
        assetService.update(id, UserContext.getUserId(), request);
        return Result.ok();
    }

    /** 删除资源。owner only。软删除，索引经事件移除。 */
    @DeleteMapping("/{id}")
    @RequireLogin
    public Result<Void> delete(@PathVariable Long id) {
        assetService.delete(id, UserContext.getUserId());
        return Result.ok();
    }

    /** 删除指定版本。owner only；latest_version 重算为剩余版本的语义最大者。 */
    @DeleteMapping("/{id}/versions/{version}")
    @RequireLogin
    public Result<String> deleteVersion(@PathVariable Long id, @PathVariable String version) {
        return Result.ok(assetService.deleteVersions(id, List.of(version), UserContext.getUserId()));
    }

    /** 批量删除版本。owner only；不存在的版本幂等跳过，返回重算后的 latest_version。 */
    @PostMapping("/{id}/versions/batch-delete")
    @RequireLogin
    public Result<String> batchDeleteVersions(@PathVariable Long id,
                                              @Valid @RequestBody DeleteAssetVersionsRequest request) {
        return Result.ok(assetService.deleteVersions(id, request.versions(), UserContext.getUserId()));
    }

    /** 下架（Admin only，docs/ARCHITECTURE.md §9.4）。Admin 校验在 service 层。 */
    @PutMapping("/{id}/suspend")
    @RequireLogin
    public Result<Void> suspend(@PathVariable Long id) {
        assetService.suspend(id, UserContext.getUserId());
        return Result.ok();
    }

    /** 版本列表。 */
    @GetMapping("/{id}/versions")
    public Result<List<AssetVersionVo>> listVersions(@PathVariable Long id) {
        return Result.ok(assetService.listVersions(id, UserContext.getUserId()));
    }

    /** 下载最新版本。 */
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        return toDownloadResponse(assetService.download(id, UserContext.getUserId()));
    }

    /** 下载指定版本。 */
    @GetMapping("/{id}/versions/{version}/download")
    public ResponseEntity<byte[]> downloadVersion(@PathVariable Long id, @PathVariable String version) {
        return toDownloadResponse(assetService.downloadVersion(id, version, UserContext.getUserId()));
    }

    /**
     * 下载指定版本的 README（.md）。
     *
     * <p>README 发布时是文本，这里以文件形式给出去，便于用户直接打开/归档；
     * 每个版本对应自己那份 README，老版本的也能单独下载。
     */
    @GetMapping("/{id}/versions/{version}/readme")
    public ResponseEntity<byte[]> downloadReadme(@PathVariable Long id, @PathVariable String version) {
        AssetDownload readme = assetService.downloadReadme(id, version, UserContext.getUserId());
        // text/markdown 而不是 octet-stream：浏览器下载后能按文本/预览方式处理
        return toAttachmentResponse(readme, MARKDOWN_UTF8);
    }

    /* ------------------------------------------------------------ 依赖 */

    /** 直接依赖列表。 */
    @GetMapping("/{id}/dependencies")
    public Result<List<AssetDependencyVo>> listDependencies(@PathVariable Long id) {
        return Result.ok(dependencyService.listDirect(id, UserContext.getUserId()));
    }

    /** 依赖树（展开传递依赖）。 */
    @GetMapping("/{id}/dependency-tree")
    public Result<List<DependencyNodeVo>> dependencyTree(@PathVariable Long id) {
        return Result.ok(dependencyService.dependencyTree(id, UserContext.getUserId()));
    }

    /**
     * 查「我自己的同名资源」：手动上传同名资源时，前端据此决定追加新版本还是新建资源。
     *
     * <p>literal 路径优先于 {@code /{id}} 模板，不会被当作 id 解析。
     */
    @GetMapping("/own-by-name")
    @RequireLogin
    public Result<com.aihub.asset.vo.OwnAssetLookupVo> ownByName(@RequestParam("name") String name) {
        return Result.ok(assetService.lookupOwnByName(UserContext.getUserId(), name));
    }

    /* ------------------------------------------------------------ 热门榜 */

    /**
     * 今日热门资源榜（docs/DATA.md §3）。公开读：Visitor 也能看（Gateway 规则
     * `GET /api/v1/assets/*` 已覆盖本路径，且精确路径优先于 `/{id}` 模板）。
     */
    @GetMapping("/hot")
    public Result<List<HotAssetVo>> hot(@RequestParam(required = false, defaultValue = "10") Integer limit) {
        return Result.ok(assetMetricsService.hotAssets(limit));
    }

    /* ------------------------------------------------------------ 内部方法 */

    /**
     * 二进制流不套统一响应体，是契约中唯一必要的例外（docs/API.md §3）。
     */
    private ResponseEntity<byte[]> toDownloadResponse(AssetDownload download) {
        return toAttachmentResponse(download, MediaType.APPLICATION_OCTET_STREAM);
    }

    /** 统一下载响应：附件文件名 + 指定 Content-Type。 */
    private ResponseEntity<byte[]> toAttachmentResponse(AssetDownload download, MediaType mediaType) {
        // 用 Spring 的 ContentDisposition 而不是 URLEncoder：后者是为表单编码设计的，
        // 空格会变成 `+`，对中文这类非 ASCII 文件名也不可靠。
        // 这里会同时给出 RFC 5987 的 filename*=UTF-8''... 与 ASCII 兜底。
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(download.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentLength(download.content().length)
                .body(download.content());
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new BizException(ErrorCode.FILE_UPLOAD_FAILED, "failed to read uploaded file");
        }
    }
}
