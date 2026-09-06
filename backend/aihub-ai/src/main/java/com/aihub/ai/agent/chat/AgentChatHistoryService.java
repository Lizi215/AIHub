package com.aihub.ai.agent.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 多轮会话历史（docs/AGENT.md §6）：按原文落 Redis，List + TTL，不持久化。
 *
 * <p>Key = {@code agent:chat:{userId}:{sessionId}}，Value 为 LIST，每轮一个 JSON
 * （{@link HistoryTurn}），LPUSH 新轮在前，LTRIM 只留最近 5 轮，写入刷新 TTL 2h；
 * 进提示词只取最近 3 轮（有界裁剪）。
 *
 * <p>历史只是增强，不是主链路：Redis 不可用时读出空、写入静默跳过，任务照常执行。
 * 这里吞掉所有 Redis 异常，只打 warn 日志。
 */
@Component
public class AgentChatHistoryService {

    private static final Logger log = LoggerFactory.getLogger(AgentChatHistoryService.class);

    /** 进提示词的轮数上限（更早的轮次留在 Redis 里但不进上下文） */
    static final int PROMPT_TURNS = 3;
    /** Redis 里保留的轮数上限（比提示词多留 2 轮，代价可忽略） */
    static final int STORED_TURNS = 5;
    private static final Duration TTL = Duration.ofHours(2);
    /** request 进历史的截断长度 */
    static final int MAX_REQUEST_CHARS = 200;
    /** summary 进历史的截断长度 */
    static final int MAX_SUMMARY_CHARS = 400;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public AgentChatHistoryService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 读取最近几轮历史，按时间正序返回（最老在前）。
     * sessionId 为空或 Redis 异常一律返回空列表。
     */
    public List<HistoryTurn> load(String sessionId, Long userId) {
        if (sessionId == null || sessionId.isBlank() || userId == null) {
            return List.of();
        }
        try {
            List<String> raw = redisTemplate.opsForList().range(key(sessionId, userId), 0, PROMPT_TURNS - 1L);
            if (raw == null || raw.isEmpty()) {
                return List.of();
            }
            // LPUSH 使列表 newest-first，读出来反转成时间正序
            List<HistoryTurn> turns = new ArrayList<>();
            for (String json : raw) {
                turns.add(objectMapper.readValue(json, HistoryTurn.class));
            }
            List<HistoryTurn> ordered = new ArrayList<>();
            for (int i = turns.size() - 1; i >= 0; i--) {
                ordered.add(turns.get(i));
            }
            log.debug("agent chat history loaded, sessionId={}, turns={}", sessionId, ordered.size());
            return List.copyOf(ordered);
        } catch (Exception e) {
            log.warn("failed to load agent chat history, degrade to empty, sessionId={}", sessionId, e);
            return List.of();
        }
    }

    /** 追加一轮对话；Redis 异常只告警，不影响任务本身。 */
    public void append(String sessionId, Long userId, String request, String summary, String status) {
        if (sessionId == null || sessionId.isBlank() || userId == null) {
            return;
        }
        try {
            HistoryTurn turn = new HistoryTurn(
                    truncate(request, MAX_REQUEST_CHARS),
                    truncate(summary, MAX_SUMMARY_CHARS),
                    status);
            String key = key(sessionId, userId);
            redisTemplate.opsForList().leftPush(key, objectMapper.writeValueAsString(turn));
            redisTemplate.opsForList().trim(key, 0, STORED_TURNS - 1L);
            redisTemplate.expire(key, TTL);
        } catch (Exception e) {
            log.warn("failed to append agent chat history, sessionId={}", sessionId, e);
        }
    }

    private String key(String sessionId, Long userId) {
        return "agent:chat:" + userId + ":" + sessionId;
    }

    private String truncate(String text, int limit) {
        String t = String.valueOf(text == null ? "" : text).replaceAll("\\s+", " ").trim();
        return t.length() <= limit ? t : t.substring(0, limit) + "[...]";
    }
}
