package com.aihub.api.asset;

import com.aihub.api.dto.AssetQueryDetail;
import com.aihub.api.dto.AssetQueryItem;
import com.aihub.api.dto.DependencyNodeItem;
import com.aihub.common.result.PageResult;
import com.aihub.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * Asset Service 的内部只读查询契约（供 AI Service 的 Tool 调用）。
 *
 * <p>不让 Agent 调公开的 {@code /api/v1/assets}：Tool 需要跨服务契约，契约必须放在
 * {@code aihub-api}（docs/BACKEND.md §3）；内部接口统一挂 {@code /internal/v1} 且不配 Gateway 路由，
 * 不会暴露到公网（docs/SECURITY.md §3）。
 *
 * <p>查询默认以匿名视角执行，只返回已发布资源。唯一例外是 {@link #mine}（含草稿）：ownerId 来自
 * 任务创建时的登录态（服务端落库，模型不可控），Agent 能看到用户自己的草稿是合理边界。
 *
 * <p>全程只读，不提供任何写接口。
 */
@FeignClient(name = "aihub-asset", contextId = "assetQueryApi")
public interface AssetQueryApi {

    /**
     * 按名称 / 分类检索已发布资源。
     *
     * <p>用途是查重与名称定位（如 {@code analyze_upload_candidate} 检查同名资源），走 MySQL
     * {@code LIKE} 合适，因为查重关注「名称是否相同」而非「内容是否相关」。
     *
     * <p>它不是全文检索：搜不到 README 正文、没有相关度排序。全文检索走 {@code SearchQueryApi}，
     * 不要把两者混用。
     *
     * @param keyword  关键字，可为空
     * @param category 分类，可为空
     * @param tag      标签精确匹配，可为空
     */
    @GetMapping("/internal/v1/query/assets")
    Result<PageResult<AssetQueryItem>> search(@RequestParam(value = "keyword", required = false) String keyword,
                                             @RequestParam(value = "category", required = false) String category,
                                             @RequestParam(value = "tag", required = false) String tag,
                                             @RequestParam(value = "page", required = false) Integer page,
                                             @RequestParam(value = "size", required = false) Integer size);

    /** 资源详情（含最新版本 README 与全部版本）。 */
    @GetMapping("/internal/v1/query/assets/{assetId}")
    Result<AssetQueryDetail> detail(@PathVariable("assetId") Long assetId);

    /**
     * 查询某用户名下的全部资源（含草稿），按创建时间倒序，最多 50 条。
     *
     * <p>owner 身份来自任务创建时的登录态（服务端写入 {@code agent_task.user_id}，经 UserContext
     * 传入本方法），不是模型参数，否则任何人都能让 Agent 查别人的草稿。
     */
    @GetMapping("/internal/v1/query/assets/mine")
    Result<List<AssetQueryItem>> mine(@RequestParam("ownerId") Long ownerId);

    /** 依赖树（含传递依赖）。 */
    @GetMapping("/internal/v1/query/assets/{assetId}/dependency-tree")
    Result<List<DependencyNodeItem>> dependencyTree(@PathVariable("assetId") Long assetId);
}
