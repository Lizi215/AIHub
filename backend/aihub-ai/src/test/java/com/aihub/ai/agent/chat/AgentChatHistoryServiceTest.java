package com.aihub.ai.agent.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentChatHistoryService} 的行为：读写形态、有界裁剪、Redis 异常降级。
 * Redis 本身被 mock，这里验证的是怎么用 Redis，以及它坏了怎么办。
 */
class AgentChatHistoryServiceTest {

    private StringRedisTemplate redisTemplate;
    private ListOperations<String, String> listOps;
    private AgentChatHistoryService service;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        listOps = mock(ListOperations.class);
        lenient().when(redisTemplate.opsForList()).thenReturn(listOps);
        service = new AgentChatHistoryService(redisTemplate, new ObjectMapper());
    }

    private static final Long USER_ID = 42L;
    private static final String SESSION = "11111111-2222-3333-4444-555555555555";
    private static final String KEY = "agent:chat:" + USER_ID + ":" + SESSION;

    @Test
    @DisplayName("读取：Redis 里 newest-first 的轮次被反转为时间正序")
    void shouldReturnTurnsInChronologicalOrder() throws Exception {
        HistoryTurn older = new HistoryTurn("第一轮", "结论A", "SUCCEEDED");
        HistoryTurn newer = new HistoryTurn("第二轮", "结论B", "SUCCEEDED");
        // LPUSH 使 Redis 里 newest-first
        when(listOps.range(eq(KEY), eq(0L), eq(2L)))
                .thenReturn(List.of(
                        new ObjectMapper().writeValueAsString(newer),
                        new ObjectMapper().writeValueAsString(older)));

        List<HistoryTurn> turns = service.load(SESSION, USER_ID);

        assertThat(turns).hasSize(2);
        assertThat(turns.get(0).request()).isEqualTo("第一轮");
        assertThat(turns.get(1).request()).isEqualTo("第二轮");
    }

    @Test
    @DisplayName("写入：LPUSH + LTRIM 只留最近 5 轮 + 刷新 2h TTL")
    void shouldAppendWithTrimAndExpire() {
        service.append(SESSION, USER_ID, "帮我找资源", "plan: 搜索; search_assets succeeded: 找到 1 条", "SUCCEEDED");

        verify(listOps).leftPush(eq(KEY), anyString());
        verify(listOps).trim(KEY, 0, AgentChatHistoryService.STORED_TURNS - 1L);
        verify(redisTemplate).expire(eq(KEY), eq(Duration.ofHours(2)));
    }

    @Test
    @DisplayName("写入内容被截断：request ≤200 字、summary ≤400 字")
    void shouldTruncateLongTexts() throws Exception {
        StringBuilder longRequest = new StringBuilder();
        longRequest.append("长".repeat(500));
        StringBuilder longSummary = new StringBuilder();
        longSummary.append("细".repeat(800));

        service.append(SESSION, USER_ID, longRequest.toString(), longSummary.toString(), "SUCCEEDED");

        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(listOps).leftPush(eq(KEY), captor.capture());
        HistoryTurn saved = new ObjectMapper().readValue(captor.getValue(), HistoryTurn.class);
        assertThat(saved.request().length()).isLessThanOrEqualTo(AgentChatHistoryService.MAX_REQUEST_CHARS + 5);
        assertThat(saved.summary().length()).isLessThanOrEqualTo(AgentChatHistoryService.MAX_SUMMARY_CHARS + 5);
        assertThat(saved.request()).endsWith("[...]");
    }

    @Test
    @DisplayName("降级：Redis 读异常 → 返回空列表而不是抛出")
    void shouldDegradeToEmptyOnReadFailure() {
        when(listOps.range(anyString(), anyLong(), anyLong())).thenThrow(new RuntimeException("redis down"));

        List<HistoryTurn> turns = service.load(SESSION, USER_ID);

        assertThat(turns).isEmpty();
    }

    @Test
    @DisplayName("降级：Redis 写异常 → 静默跳过，不影响调用方")
    void shouldSwallowWriteFailure() {
        when(listOps.leftPush(anyString(), anyString())).thenThrow(new RuntimeException("redis down"));

        service.append(SESSION, USER_ID, "request", "summary", "SUCCEEDED");

        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("无会话（sessionId 为空）→ 完全不触碰 Redis")
    void shouldSkipRedisWithoutSession() {
        assertThat(service.load(null, USER_ID)).isEmpty();
        assertThat(service.load("  ", USER_ID)).isEmpty();
        service.append(null, USER_ID, "r", "s", "SUCCEEDED");

        verify(redisTemplate, never()).opsForList();
    }
}
