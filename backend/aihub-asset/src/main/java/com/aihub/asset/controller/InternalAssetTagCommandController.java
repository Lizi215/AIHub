package com.aihub.asset.controller;

import com.aihub.api.asset.AssetTagCommandApi;
import com.aihub.api.dto.TagUpdateCommand;
import com.aihub.api.dto.TagUpdateResult;
import com.aihub.asset.service.TagService;
import com.aihub.common.result.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * 内部标签写接口（AI Agent 在用户确认后调用）。
 *
 * <p>归属校验在服务层（{@code TagService} 用 ownerId 判资源归属），这里不信任调用方，
 * 越权请求会被直接拒绝。
 */
@RestController
public class InternalAssetTagCommandController implements AssetTagCommandApi {

    private static final Logger log = LoggerFactory.getLogger(InternalAssetTagCommandController.class);

    private final TagService tagService;

    public InternalAssetTagCommandController(TagService tagService) {
        this.tagService = tagService;
    }

    @Override
    @PostMapping("/internal/v1/commands/assets/{assetId}/tags")
    public Result<TagUpdateResult> applyTags(@PathVariable("assetId") Long assetId,
                                             @RequestBody TagUpdateCommand command) {
        if (command == null || command.ownerId() == null) {
            // ownerId 缺失说明调用方没按契约从任务行取身份：宁可失败也不猜
            return Result.fail(com.aihub.common.result.ErrorCode.PARAM_INVALID, "ownerId is required");
        }
        boolean replace = "REPLACE".equalsIgnoreCase(
                command.mode() == null ? "" : command.mode().trim().toUpperCase(Locale.ROOT));
        List<String> finalTags = replace
                ? tagService.setAssetTags(assetId, command.tags(), command.ownerId())
                : tagService.addAssetTags(assetId, command.tags(), command.ownerId());
        log.info("internal tag command applied, assetId={}, ownerId={}, mode={}, tags={}",
                assetId, command.ownerId(), replace ? "REPLACE" : "APPEND", finalTags);
        return Result.ok(new TagUpdateResult(assetId, tagService.nameOf(assetId), finalTags));
    }
}
