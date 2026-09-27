package com.aihub.asset.controller;

import com.aihub.asset.service.TagService;
import com.aihub.common.auth.RequireLogin;
import com.aihub.common.result.Result;
import com.aihub.common.trace.UserContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 标签接口（docs/ARCHITECTURE.md §3：Tag 只服务 Asset）。
 *
 * <pre>
 * GET  /api/v1/assets/{id}/tags 资源的标签（公开读）
 * PUT  /api/v1/assets/{id}/tags 整体设置资源标签（owner，校验在 service 层）
 * </pre>
 */
@RestController
public class TagController {

    private final TagService tagService;

    public TagController(TagService tagService) {
        this.tagService = tagService;
    }

    public record SetTagsRequest(
            @Valid List<@Valid @NotBlank @Size(max = 20) String> tags
    ) {
    }

    @GetMapping("/api/v1/assets/{id}/tags")
    public Result<List<String>> tagsOf(@PathVariable Long id) {
        return Result.ok(tagService.tagsOf(id));
    }

    @PutMapping("/api/v1/assets/{id}/tags")
    @RequireLogin
    public Result<List<String>> setTags(@PathVariable Long id,
                                        @Valid @RequestBody SetTagsRequest request) {
        return Result.ok(tagService.setAssetTags(id, request.tags(), UserContext.getUserId()));
    }
}
