package com.aihub.search.support;

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
 * 作者昵称解析（搜索结果展示用）。
 *
 * <p>与 aihub-community / aihub-asset 的同名类行为一致，展示辅助类，复制成本低于抽公共模块。
 *
 * <p>解析点在查询响应组装时而不是写索引时：ES 里只有 authorId，昵称在返回前补上，
 * 不动映射、无需重建索引。批量取避免 N+1；User Service 抖动时取不到就返回空 Map，
 * 前端用「用户#后 6 位」兜底，不能拖垮资源列表。
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

    /** @return id → 昵称；查不到的 id 不出现在结果中，调用方自行兜底 */
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
