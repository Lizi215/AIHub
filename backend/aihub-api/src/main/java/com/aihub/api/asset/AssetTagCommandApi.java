package com.aihub.api.asset;

import com.aihub.api.dto.TagUpdateCommand;
import com.aihub.api.dto.TagUpdateResult;
import com.aihub.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Asset Service 的内部写契约：给资源打标签（供 AI Agent 在用户确认后调用）。
 *
 * <p>这里破例提供写接口：Agent 没有用户 JWT，走不了公开的 {@code PUT /api/v1/assets/{id}/tags}，
 * 但落库责任仍在 Asset Service。安全边界靠两条：调用方只用任务行里的 user_id 作 ownerId
 * （模型不可控），Asset 侧据此做归属校验；工具性质登记为 CONFIRM，未经确认 Orchestrator
 * 会硬拒绝执行（docs/AGENT.md §5）。
 *
 * <p>其余内部查询契约（{@link AssetQueryApi}）保持只读。
 */
@FeignClient(name = "aihub-asset", contextId = "assetTagCommandApi")
public interface AssetTagCommandApi {

    /** 给指定资源打标签；mode 见 {@link TagUpdateCommand}。 */
    @PostMapping("/internal/v1/commands/assets/{assetId}/tags")
    Result<TagUpdateResult> applyTags(@PathVariable("assetId") Long assetId,
                                      @RequestBody TagUpdateCommand command);
}
