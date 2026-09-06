package com.aihub.ai.agent.orchestrator;

import com.aihub.common.trace.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具线程的身份传递（回归测试）。
 *
 * <p>工具为了做单次调用超时跑在独立线程池里，而 {@code UserContext} 是 ThreadLocal：
 * 不显式传递的话，owner 视角的工具在工具线程里身份为 null，{@code list_my_assets}
 * 会报「无法识别用户身份」，同名判定把自己的资源误判成他人同名。
 */
class AgentToolInvokerTest {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        UserContext.clear();
        executor.shutdownNow();
    }

    private AgentToolInvoker invoker() {
        return new AgentToolInvoker(null, null, null, executor);
    }

    @Test
    @DisplayName("工具线程能拿到调用方身份；执行完在工具线程清理，不污染池化线程")
    void shouldPropagateIdentityIntoWorkerThread() throws Exception {
        UserContext.set(42L, "USER");

        Callable<Long> wrapped = invoker().withCallerIdentity(UserContext::getUserId);
        Long seenInWorker = executor.submit(wrapped).get(5, TimeUnit.SECONDS);

        assertThat(seenInWorker).isEqualTo(42L);
        // 调用线程身份不受影响
        assertThat(UserContext.getUserId()).isEqualTo(42L);
        // 工具线程执行后已清理：同一池化线程的下一个任务不应看到残留身份
        assertThat(executor.submit(UserContext::getUserId).get(5, TimeUnit.SECONDS)).isNull();
    }

    @Test
    @DisplayName("调用方没有身份时（如后台任务）工具线程也保持无身份，不臆造")
    void shouldNotInventIdentityWhenCallerHasNone() throws Exception {
        UserContext.clear();

        Callable<Long> wrapped = invoker().withCallerIdentity(UserContext::getUserId);

        assertThat(executor.submit(wrapped).get(5, TimeUnit.SECONDS)).isNull();
    }
}
