package com.aihub.api.search;

import com.aihub.api.dto.AssetQueryItem;
import com.aihub.common.result.PageResult;
import com.aihub.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Search Service 的内部查询契约，供 AI Service 的 Agent 检索工具使用。
 *
 * <p>AI Service 的 {@code search_assets} 工具先前经 {@code AssetQueryApi} 走 MySQL {@code LIKE}，
 * 只能匹配名称与简介、没有相关度排序；Phase 3 之后 Search Service 与 ES 已就绪，检索应走这里。
 *
 * <p>不让 AI Service 直连 {@code /api/v1/search/**}：那是面向浏览器的公开路由（返回高亮片段与
 * search_after 游标），Agent 需要稳定的服务间 DTO（见 {@link AssetQueryItem}）。跨服务调用一律走
 * {@code aihub-api} 契约，路径挂 {@code /internal/v1} 且不配 Gateway 路由。
 *
 * <p>以匿名视角检索（docs/ARCHITECTURE.md 权限模型），结果只含已发布资源，草稿不进 Agent 上下文。
 */
@FeignClient(name = "aihub-search", contextId = "searchQueryApi")
public interface SearchQueryApi {

    /**
     * 按关键字 / 分类 / 标签全文检索已发布资源（ES，相关度排序）。
     *
     * <p>匹配范围是 ES 索引的全部文本字段：名称、简介与 README 正文，这是 LIKE 路径做不到的。
     *
     * @param keyword  关键字，可为空（为空表示不加关键字过滤）
     * @param category 分类，可为空
     * @param tag      标签精确匹配，可为空
     * @param page     页码，从 1 开始
     * @param size     每页条数
     */
    @GetMapping("/internal/v1/query/assets")
    Result<PageResult<AssetQueryItem>> search(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "tag", required = false) String tag,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size);
}
