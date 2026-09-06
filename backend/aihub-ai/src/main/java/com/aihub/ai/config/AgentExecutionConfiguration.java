package com.aihub.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent 执行的线程基础设施。
 *
 * <p>两套线程池刻意分开。任务池（{@code agentTaskExecutor}）跑整条 Agent 任务，数量与队列
 * 都有上限，队列满时直接拒绝并让任务失败，避免把任务无限堆在队列里、给用户「看起来在跑、
 * 实际永远轮不到」的假象。Tool 池（{@code agentToolExecutor}）只为「单次 Tool 调用超时」服务，
 * 若与任务池共用，任务线程等待 Tool 结果时会占着池子，形成死锁式饥饿。
 *
 * <p>另有 SSE 轮询用的单线程调度器：轮询极轻量但持续，独占一个线程即可。
 */
@Configuration(proxyBeanMethods = false)
public class AgentExecutionConfiguration {

    @Bean("agentTaskExecutor")
    public ThreadPoolTaskExecutor agentTaskExecutor(AgentProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getExecutorThreads());
        executor.setMaxPoolSize(properties.getExecutorThreads());
        executor.setQueueCapacity(properties.getExecutorQueueCapacity());
        executor.setThreadNamePrefix("aihub-agent-task-");
        // 默认拒绝策略就是 AbortPolicy：明确拒绝，由调用方转成业务错误
        executor.initialize();
        return executor;
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService agentToolExecutor() {
        AtomicInteger counter = new AtomicInteger();
        return Executors.newFixedThreadPool(8, runnable -> {
            Thread thread = new Thread(runnable, "aihub-agent-tool-" + counter.incrementAndGet());
            // 守护线程：应用关闭时不因为一次卡住的 Tool 调用而挂住整个进程
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService agentSseScheduler() {
        AtomicInteger counter = new AtomicInteger();
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "aihub-agent-sse-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }
}
