package com.aihub.community.support;

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
 * 作者昵称解析：把 authorId 批量换成昵称，供 Issue / 评论列表展示。
 *
 * <p>批量取避免逐个 id 跨服务调用造成 N+1；User Service 抖动时降级返回空 Map，
 * 由前端用「用户#后 6 位」兜底，不让整条讨论渲染不出来。
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

    /**
     * 批量解析作者昵称。
     *
     * @param authorIds 作者 id 集合，可为 null / 空 / 含重复
     * @return id → 昵称；查不到的 id 不出现在结果中（调用方自行兜底）
     */
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
            // 降级：昵称只是展示增强，不能因为依赖抖动让主链路失败
            log.warn("resolve author nicknames failed, fallback to id-only. requested={}, cause={}",
                    distinct.size(), e.toString());
        }
        return result;
    }
}
