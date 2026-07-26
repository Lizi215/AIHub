package com.aihub.asset.support;

import com.aihub.api.dto.UserBrief;
import com.aihub.api.user.UserQueryApi;
import com.aihub.common.result.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 作者昵称解析（资源列表 / 详情展示用）。
 *
 * <p>与 {@code aihub-community} 的同名类行为一致：一个展示辅助类，抽公共模块反而多一层依赖，
 * 复制成本更低。
 *
 * <p>批量取，避免逐行 N+1；User Service 抖动时降级为空 Map（前端用「用户#后 6 位」兜底），
 * 不能因此拖垮资源列表。分批上限与契约侧 {@code InternalUserQueryController.MAX_IDS} 一致。
 */
@Component
public class AuthorNameResolver {

    private static final Logger log = LoggerFactory.getLogger(AuthorNameResolver.class);

    /** 与 InternalUserQueryController.MAX_IDS 保持一致 */
    private static final int BATCH_SIZE = 200;

    private final UserQueryApi userQueryApi;

    public AuthorNameResolver(UserQueryApi userQueryApi) {
        this.userQueryApi = userQueryApi;
    }

    /** @return id → 昵称；查不到的 id 不出现在结果中（调用方自行兜底） */
    public Map<Long, String> resolve(Collection<Long> authorIds) {
        if (authorIds == null || authorIds.isEmpty()) {
            return Map.of();
        }
        List<Long> distinct = authorIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }

        Map<Long, String> result = new LinkedHashMap<>();
        try {
            for (int from = 0; from < distinct.size(); from += BATCH_SIZE) {
                int to = Math.min(from + BATCH_SIZE, distinct.size());
                Result<List<UserBrief>> response = userQueryApi.briefs(distinct.subList(from, to));
                if (response == null || response.data() == null) {
                    continue;
                }
                for (UserBrief brief : response.data()) {
                    if (brief != null && brief.id() != null) {
                        result.put(brief.id(), brief.nickname());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("resolve owner nicknames failed, fallback to id-only. requested={}, cause={}",
                    distinct.size(), e.toString());
        }
        return result;
    }
}
