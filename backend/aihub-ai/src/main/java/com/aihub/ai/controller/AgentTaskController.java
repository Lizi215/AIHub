package com.aihub.ai.controller;

import com.aihub.ai.dto.CreateAgentTaskRequest;
import com.aihub.ai.service.AgentTaskService;
import com.aihub.ai.vo.AgentTaskVo;
import com.aihub.common.auth.RequireLogin;
import com.aihub.common.result.Result;
import com.aihub.common.trace.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Agent 任务接口（docs/AGENT.md §4）。
 *
 * <pre>
 * POST   /api/v1/agent/tasks              创建任务，立即返回 taskId
 * GET    /api/v1/agent/tasks/{id}         查询任务状态与结果
 * GET    /api/v1/agent/tasks/{id}/stream  SSE 订阅执行过程
 * POST   /api/v1/agent/tasks/{id}/confirm 提交用户确认
 * DELETE /api/v1/agent/tasks/{id}         取消任务
 * </pre>
 *
 * <p>全部需要登录：Gateway 侧 {@code /api/v1/agent/**} 不在公开规则里，服务侧再用
 * {@link RequireLogin} 做纵深防御。任务按 user 归属隔离，非发起者访问会被服务层拒绝（91004）。
 */
@RestController
@RequestMapping("/api/v1/agent")
public class AgentTaskController {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskController.class);

    /** SSE 推送间隔：状态变化是秒级的，500ms 足够"有过程感"又不至于把库读爆 */
    private static final long STREAM_INTERVAL_MS = 500;

    /** SSE 单次订阅的最长时长，超过就结束（客户端可重连） */
    private static final long STREAM_TIMEOUT_MS = 120_000;

    private final AgentTaskService agentTaskService;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService agentSseScheduler;

    public AgentTaskController(AgentTaskService agentTaskService,
                               ObjectMapper objectMapper,
                               ScheduledExecutorService agentSseScheduler) {
        this.agentTaskService = agentTaskService;
        this.objectMapper = objectMapper;
        this.agentSseScheduler = agentSseScheduler;
    }

    @PostMapping("/tasks")
    @RequireLogin
    public Result<Long> create(@Valid @RequestBody CreateAgentTaskRequest request) {
        return Result.ok(agentTaskService.create(UserContext.getUserId(), request));
    }

    @GetMapping("/tasks/{id}")
    @RequireLogin
    public Result<AgentTaskVo> detail(@PathVariable Long id) {
        return Result.ok(agentTaskService.detail(id, UserContext.getUserId()));
    }

    @PostMapping("/tasks/{id}/confirm")
    @RequireLogin
    public Result<AgentTaskVo> confirm(@PathVariable Long id) {
        return Result.ok(agentTaskService.confirm(id, UserContext.getUserId()));
    }

    @DeleteMapping("/tasks/{id}")
    @RequireLogin
    public Result<Void> cancel(@PathVariable Long id) {
        agentTaskService.cancel(id, UserContext.getUserId());
        return Result.ok();
    }

    /**
     * 订阅执行过程（SSE）。
     *
     * <p>定时轮询任务状态并推送快照，直到任务进入终态或订阅超时。不做成服务端事件总线，
     * 因为任务状态本身就是唯一事实来源，多一层内存事件总线只会多一份可能不一致的状态。
     *
     * <p>响应体不是统一响应体：SSE 是文本事件流，无法套 {@code Result} JSON，
     * 属于约定里必要的例外之一（另一个是二进制下载）。
     */
    @GetMapping(value = "/tasks/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RequireLogin
    public SseEmitter stream(@PathVariable Long id) {
        // 身份必须在请求线程里取：轮询发生在调度线程，那里没有 UserContext
        Long userId = UserContext.getUserId();
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        AtomicBoolean closed = new AtomicBoolean(false);
        AtomicReference<ScheduledFuture<?>> poller = new AtomicReference<>();

        // 客户端断开 / 超时 / 出错后必须停止轮询，否则会一直读库直到调度线程被占满。
        // SseEmitter 的 onCompletion 是覆盖式注册（不是追加），因此只注册一次
        Runnable stop = () -> {
            closed.set(true);
            ScheduledFuture<?> future = poller.get();
            if (future != null) {
                future.cancel(false);
            }
        };
        emitter.onCompletion(stop);
        emitter.onTimeout(stop);
        emitter.onError(e -> stop.run());

        ScheduledFuture<?> future = agentSseScheduler.scheduleWithFixedDelay(() -> {
            if (closed.get()) {
                return;
            }
            try {
                AgentTaskVo task = agentTaskService.detail(id, userId);
                emitter.send(SseEmitter.event().name("status")
                        .data(objectMapper.writeValueAsString(task), MediaType.APPLICATION_JSON));
                if (isTerminal(task.status())) {
                    closed.set(true);
                    emitter.complete();
                }
            } catch (IOException e) {
                // 客户端已断开是常态，不当成错误处理
                stop.run();
            } catch (Exception e) {
                log.warn("agent task stream failed, taskId={}", id, e);
                stop.run();
                emitter.completeWithError(e);
            }
        }, 0, STREAM_INTERVAL_MS, TimeUnit.MILLISECONDS);
        poller.set(future);
        return emitter;
    }

    private boolean isTerminal(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }
}
