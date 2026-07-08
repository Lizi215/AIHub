package com.aihub.common.auth;

import com.aihub.common.exception.BizException;
import com.aihub.common.result.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JWT 签发与校验契约。
 */
class JwtSupportTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final long USER_ID = 1945678901234567890L;

    private JwtSupport newSupport(Duration accessTtl) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);
        properties.setIssuer("aihub-test");
        properties.setAccessTokenTtl(accessTtl);
        properties.setRefreshTokenTtl(Duration.ofDays(7));
        return new JwtSupport(properties);
    }

    @Test
    void shouldCarryUserIdAndRoleInAccessToken() {
        JwtSupport support = newSupport(Duration.ofMinutes(30));

        String token = support.createAccessToken(USER_ID, "USER");
        AuthenticatedUser user = support.verifyAndParse(token);

        assertThat(user.userId()).isEqualTo(USER_ID);
        assertThat(user.role()).isEqualTo("USER");
        assertThat(user.jwtId()).isNotBlank();
    }

    @Test
    void shouldExposeExpiryCloseToConfiguredTtl() {
        JwtSupport support = newSupport(Duration.ofMinutes(30));

        AuthenticatedUser user = support.verifyAndParse(support.createAccessToken(USER_ID, "USER"));

        // Hutool 可能把 exp 截断到秒，因此允许数秒误差
        assertThat(user.expiresAt())
                .isAfter(Instant.now().plus(29, ChronoUnit.MINUTES))
                .isBefore(Instant.now().plus(31, ChronoUnit.MINUTES));
    }

    @Test
    void refreshTokenShouldNotCarryRole() {
        JwtSupport support = newSupport(Duration.ofMinutes(30));

        AuthenticatedUser user = support.verifyAndParse(support.createRefreshToken(USER_ID));

        assertThat(user.userId()).isEqualTo(USER_ID);
        assertThat(user.role()).isNull();
    }

    @Test
    void shouldRejectExpiredTokenAsExpired() throws InterruptedException {
        JwtSupport support = newSupport(Duration.ofMillis(1));
        String token = support.createAccessToken(USER_ID, "USER");

        Thread.sleep(1100);

        assertThatThrownBy(() -> support.verifyAndParse(token))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOKEN_EXPIRED);
    }

    @Test
    void shouldRejectTamperedToken() {
        JwtSupport support = newSupport(Duration.ofMinutes(30));
        String token = support.createAccessToken(USER_ID, "USER");
        // 篡改载荷最后一段签名
        String tampered = token.substring(0, token.length() - 2) + "xy";

        assertThatThrownBy(() -> support.verifyAndParse(tampered))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOKEN_INVALID);
    }

    @Test
    void shouldRejectTokenSignedWithAnotherSecret() {
        JwtProperties other = new JwtProperties();
        other.setSecret("ffffffffffffffffffffffffffffffff");
        JwtSupport foreign = new JwtSupport(other);

        String foreignToken = foreign.createAccessToken(USER_ID, "USER");

        JwtSupport support = newSupport(Duration.ofMinutes(30));
        assertThatThrownBy(() -> support.verifyAndParse(foreignToken))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOKEN_INVALID);
    }

    @Test
    void shouldRejectMalformedToken() {
        JwtSupport support = newSupport(Duration.ofMinutes(30));

        assertThatThrownBy(() -> support.verifyAndParse("not-a-jwt"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void shouldRejectBlankTokenAsUnauthorized() {
        JwtSupport support = newSupport(Duration.ofMinutes(30));

        assertThatThrownBy(() -> support.verifyAndParse("  "))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test
    void shouldRefuseToStartWithoutSecret() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("  ");

        // 空密钥必须启动即失败，否则任何人都能伪造 token
        assertThatThrownBy(() -> new JwtSupport(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("aihub.jwt.secret");
    }

    /**
     * 并发校验回归测试。
     *
     * <p>JwtSupport 是单例，被所有请求线程共享；Hutool 的 signer 内部持有
     * {@code javax.crypto.Mac}，它不是线程安全的，并发签名/校验会相互交错。
     * 这里用多线程反复校验同一个 token，把这种交错暴露出来。
     */
    @Test
    void shouldVerifyConcurrentlyWithoutFalseRejection() throws Exception {
        int threads = 16;
        int iterations = 300;
        JwtSupport support = newSupport(Duration.ofMinutes(30));
        String token = support.createAccessToken(USER_ID, "USER");

        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        AtomicReference<String> firstFailure = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < iterations; i++) {
                            try {
                                AuthenticatedUser user = support.verifyAndParse(token);
                                // 校验通过还不够，必须连载荷也解析正确
                                if (!Long.valueOf(USER_ID).equals(user.userId())) {
                                    failures.incrementAndGet();
                                    firstFailure.compareAndSet(null, "userId mismatch: " + user.userId());
                                }
                            } catch (BizException e) {
                                failures.incrementAndGet();
                                firstFailure.compareAndSet(null, e.getErrorCode() + " " + e.getMessage());
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(failures.get())
                .as("并发校验失败次数，首个失败原因：%s", firstFailure.get())
                .isZero();
    }
}
