package com.aihub.api.user;

import com.aihub.api.dto.UserBrief;
import com.aihub.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * User Service 的只读查询契约（内部接口，不经 Gateway 暴露到公网）。
 *
 * <p>用途：社区内容（Issue / 评论）只存 authorId，展示时要显示昵称。
 * 昵称属于 User Service 的数据，其他服务不得直接查它的表，
 * 因此按 docs/BACKEND.md 的约定走 OpenFeign 契约。
 *
 * <p>批量接口而不是单个查询：一次列表渲染通常涉及多个作者，
 * 逐个查会产生 N+1 次跨服务调用。
 */
@FeignClient(name = "aihub-user", contextId = "userQueryApi")
public interface UserQueryApi {

    /**
     * 批量查询用户简要信息。
     *
     * <p>不存在的 id 会被静默忽略（软删除的用户不应让整条讨论渲染失败），
     * 调用方按「查不到就降级」处理。
     *
     * @param ids 用户 id，调用方需自行控制批量大小（本接口约定不超过 200 个）
     */
    @GetMapping("/internal/v1/users/briefs")
    Result<List<UserBrief>> briefs(@RequestParam("ids") List<Long> ids);
}
