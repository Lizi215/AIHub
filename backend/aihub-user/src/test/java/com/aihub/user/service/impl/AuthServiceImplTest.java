package com.aihub.user.service.impl;

import cn.hutool.crypto.digest.BCrypt;
import com.aihub.common.auth.JwtProperties;
import com.aihub.common.auth.JwtSupport;
import com.aihub.common.exception.BizException;
import com.aihub.common.redis.RedisKeys;
import com.aihub.common.result.ErrorCode;
import com.aihub.user.dto.LoginRequest;
import com.aihub.user.dto.RegisterRequest;
import com.aihub.user.entity.UserEntity;
import com.aihub.user.mapper.UserMapper;
import com.aihub.user.service.UserService;
import com.aihub.user.vo.LoginVo;
import com.aihub.user.vo.UserVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 认证与令牌生命周期。
 *
 * <p>覆盖 refresh token 落 Redis（注销立即生效）、access token 无状态但注销进黑名单、
 * 黑名单 TTL 与 token 剩余有效期一致。
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    private static final long USER_ID = 1945678901234567890L;
    private static final String RAW_PASSWORD = "Passw0rd!23";
    private static final String CLIENT_IP = "203.0.113.7";

    @Mock
    private UserMapper userMapper;
    @Mock
    private UserService userService;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private JwtSupport jwtSupport;
    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("0123456789abcdef0123456789abcdef");
        properties.setAccessTokenTtl(Duration.ofMinutes(30));
        properties.setRefreshTokenTtl(Duration.ofDays(7));
        jwtSupport = new JwtSupport(properties);

        // 部分用例不触碰 Redis，用 lenient 避免严格 stub 误报
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        authService = new AuthServiceImpl(userMapper, userService, redisTemplate, jwtSupport);
    }

    /* ------------------------------------------------------------ 注册 */

    @Test
    @DisplayName("注册：用户名已存在时拒绝")
    void registerShouldRejectExistingUsername() {
        when(userMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> authService.register(new RegisterRequest("alice", RAW_PASSWORD, null, null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.USERNAME_ALREADY_EXISTS);

        // BaseMapper 同时有 insert(T) 与 insert(Collection<T>)，须指明类型避免编译期歧义
        verify(userMapper, never()).insert(any(UserEntity.class));
    }

    @Test
    @DisplayName("注册：并发下唯一索引冲突也转成同一业务错误，而不是 500")
    void registerShouldTranslateDuplicateKeyException() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(userMapper.insert(any(UserEntity.class))).thenThrow(new DuplicateKeyException("uk_user_username"));

        assertThatThrownBy(() -> authService.register(new RegisterRequest("alice", RAW_PASSWORD, null, null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.USERNAME_ALREADY_EXISTS);
    }

    @Test
    @DisplayName("注册：密码以 BCrypt 存储（禁止明文），角色为 USER，昵称缺省用用户名")
    void registerShouldHashPasswordAndDefaultRole() {
        when(userMapper.selectCount(any())).thenReturn(0L);

        authService.register(new RegisterRequest("alice", RAW_PASSWORD, null, null));

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userMapper).insert(captor.capture());
        UserEntity saved = captor.getValue();

        assertThat(saved.getPassword()).isNotEqualTo(RAW_PASSWORD);
        assertThat(saved.getPassword()).startsWith("$2");
        assertThat(BCrypt.checkpw(RAW_PASSWORD, saved.getPassword())).isTrue();
        assertThat(saved.getRole()).isEqualTo("USER");
        assertThat(saved.getNickname()).isEqualTo("alice");
    }

    /* ------------------------------------------------------------ 登录 */

    @Test
    @DisplayName("登录成功：签发双令牌，refresh 落 Redis 且带 TTL")
    void loginShouldIssueTokensAndStoreRefresh() {
        when(userMapper.selectOne(any())).thenReturn(user());
        when(userService.toVo(any())).thenReturn(new UserVo(USER_ID, "alice", "Alice", "USER"));

        LoginVo loginVo = authService.login(new LoginRequest("alice", RAW_PASSWORD), CLIENT_IP);

        assertThat(loginVo.accessToken()).isNotBlank();
        assertThat(loginVo.refreshToken()).isNotBlank();
        assertThat(loginVo.expiresIn()).isBetween(1700L, 1800L);

        // refresh token 落 Redis 才能主动注销
        String jti = jwtSupport.verifyAndParse(loginVo.refreshToken()).jwtId();
        verify(valueOperations).set(eq(RedisKeys.refreshToken(USER_ID, jti)), eq("1"),
                eq(Duration.ofDays(7)));
    }

    @Test
    @DisplayName("登录成功：清零失败计数")
    void loginShouldResetFailureCounters() {
        when(userMapper.selectOne(any())).thenReturn(user());
        when(userService.toVo(any())).thenReturn(new UserVo(USER_ID, "alice", "Alice", "USER"));

        authService.login(new LoginRequest("alice", RAW_PASSWORD), CLIENT_IP);

        verify(redisTemplate).delete(RedisKeys.loginFailureByIp(CLIENT_IP));
        verify(redisTemplate).delete(RedisKeys.loginFailureByUsername("alice"));
    }

    @Test
    @DisplayName("密码错误：返回统一错误码并同时累加 IP 与账号两个维度的失败计数")
    void loginShouldRecordFailureOnWrongPassword() {
        when(userMapper.selectOne(any())).thenReturn(user());
        when(valueOperations.increment(anyString())).thenReturn(1L);

        assertThatThrownBy(() -> authService.login(new LoginRequest("alice", "wrong-password"), CLIENT_IP))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PASSWORD_INCORRECT);

        verify(valueOperations).increment(RedisKeys.loginFailureByIp(CLIENT_IP));
        verify(valueOperations).increment(RedisKeys.loginFailureByUsername("alice"));
        // 计数要有 TTL，避免 Key 永久膨胀
        verify(redisTemplate).expire(eq(RedisKeys.loginFailureByIp(CLIENT_IP)), any(Duration.class));
    }

    @Test
    @DisplayName("用户不存在与密码错误返回同一错误码，避免账号枚举")
    void loginShouldNotRevealWhetherUserExists() {
        when(userMapper.selectOne(any())).thenReturn(null);
        when(valueOperations.increment(anyString())).thenReturn(1L);

        assertThatThrownBy(() -> authService.login(new LoginRequest("nobody", RAW_PASSWORD), CLIENT_IP))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PASSWORD_INCORRECT);
    }

    @Test
    @DisplayName("失败次数达到阈值：按 IP 维度锁定")
    void loginShouldLockWhenIpFailuresExceedThreshold() {
        when(valueOperations.get(RedisKeys.loginFailureByIp(CLIENT_IP))).thenReturn("5");

        assertThatThrownBy(() -> authService.login(new LoginRequest("alice", RAW_PASSWORD), CLIENT_IP))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.LOGIN_ATTEMPT_LIMITED);
    }

    @Test
    @DisplayName("失败次数达到阈值：按账号维度锁定（换 IP 也绕不过）")
    void loginShouldLockWhenAccountFailuresExceedThreshold() {
        when(valueOperations.get(RedisKeys.loginFailureByIp(CLIENT_IP))).thenReturn(null);
        when(valueOperations.get(RedisKeys.loginFailureByUsername("alice"))).thenReturn("5");

        assertThatThrownBy(() -> authService.login(new LoginRequest("alice", RAW_PASSWORD), CLIENT_IP))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.LOGIN_ATTEMPT_LIMITED);
    }

    /* ------------------------------------------------------------ 续期 */

    @Test
    @DisplayName("续期：不接受 access token")
    void refreshShouldRejectAccessToken() {
        String accessToken = jwtSupport.createAccessToken(USER_ID, "USER");

        assertThatThrownBy(() -> authService.refresh(accessToken))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOKEN_INVALID);
    }

    @Test
    @DisplayName("续期：Redis 中取不到即视为失效（这是主动注销能立即生效的前提）")
    void refreshShouldRejectWhenRedisEntryMissing() {
        String refreshToken = jwtSupport.createRefreshToken(USER_ID);
        when(valueOperations.getAndDelete(anyString())).thenReturn(null);

        assertThatThrownBy(() -> authService.refresh(refreshToken))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName("续期成功：旧 refresh token 被原子取出并作废（轮换）")
    void refreshShouldRotateOldToken() {
        String refreshToken = jwtSupport.createRefreshToken(USER_ID);
        String jti = jwtSupport.verifyAndParse(refreshToken).jwtId();
        when(valueOperations.getAndDelete(RedisKeys.refreshToken(USER_ID, jti))).thenReturn("1");
        when(userService.getActiveUser(USER_ID)).thenReturn(user());
        when(userService.toVo(any())).thenReturn(new UserVo(USER_ID, "alice", "Alice", "USER"));

        LoginVo refreshed = authService.refresh(refreshToken);

        // 必须原子 getAndDelete；换成 hasKey + delete 会让并发续期全部通过
        verify(valueOperations).getAndDelete(RedisKeys.refreshToken(USER_ID, jti));
        verify(redisTemplate, never()).delete(RedisKeys.refreshToken(USER_ID, jti));
        assertThat(refreshed.accessToken()).isNotBlank();
        assertThat(refreshed.refreshToken()).isNotEqualTo(refreshToken);
    }

    /* ------------------------------------------------------------ 注销 */

    @Test
    @DisplayName("注销：access token 进黑名单，且 TTL 等于剩余有效期（禁止永不过期的 Key）")
    void logoutShouldBlacklistAccessTokenWithRemainingTtl() {
        String accessToken = jwtSupport.createAccessToken(USER_ID, "USER");
        String jti = jwtSupport.verifyAndParse(accessToken).jwtId();

        authService.logout(accessToken, null);

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(eq(RedisKeys.accessTokenBlacklist(jti)), eq("1"), ttlCaptor.capture());
        assertThat(ttlCaptor.getValue().getSeconds()).isBetween(1700L, 1800L);
    }

    @Test
    @DisplayName("注销：同时撤销 refresh token，否则它还能换回新的 access token")
    void logoutShouldRevokeRefreshToken() {
        String accessToken = jwtSupport.createAccessToken(USER_ID, "USER");
        String refreshToken = jwtSupport.createRefreshToken(USER_ID);
        String refreshJti = jwtSupport.verifyAndParse(refreshToken).jwtId();

        authService.logout(accessToken, refreshToken);

        verify(redisTemplate).delete(RedisKeys.refreshToken(USER_ID, refreshJti));
    }

    @Test
    @DisplayName("注销：refresh token 已非法时忽略，不能让注销整体失败")
    void logoutShouldTolerateInvalidRefreshToken() {
        String accessToken = jwtSupport.createAccessToken(USER_ID, "USER");

        authService.logout(accessToken, "not-a-jwt");

        // access token 黑名单仍写入成功
        String jti = jwtSupport.verifyAndParse(accessToken).jwtId();
        verify(valueOperations).set(eq(RedisKeys.accessTokenBlacklist(jti)), eq("1"), any(Duration.class));
    }

    private UserEntity user() {
        UserEntity user = new UserEntity();
        user.setId(USER_ID);
        user.setUsername("alice");
        user.setNickname("Alice");
        user.setPassword(BCrypt.hashpw(RAW_PASSWORD));
        user.setRole("USER");
        user.setDeleted(0L);
        return user;
    }
}
