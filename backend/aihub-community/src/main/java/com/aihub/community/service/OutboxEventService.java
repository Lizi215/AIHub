package com.aihub.community.service;

import com.aihub.community.entity.PostEntity;

/**
 * 社区侧出站事件写入（outbox 模式，docs/SEARCH.md §1）。
 *
 * <p>与 Asset 侧同样要求：**必须在业务事务内调用**（实现上标注 MANDATORY），
 * 保证「帖子落库」与「事件落库」同生共死。
 */
public interface OutboxEventService {

    /**
     * 写入 `POST_CREATED` 事件（供 Search Service 建索引）。
     *
     * <p>帖子只有"创建"这一个事件（点赞/评论不产生索引变更），因此 opVersion 固定为 1。
     */
    void appendPostCreated(PostEntity post);
}
