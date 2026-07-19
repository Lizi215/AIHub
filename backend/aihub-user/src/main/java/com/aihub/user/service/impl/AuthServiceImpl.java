package com.aihub.user.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.aihub.common.auth.AuthenticatedUser;
import com.aihub.common.auth.JwtSupport;
import com.aihub.common.exception.BizException;
import com.aihub.common.mybatis.BaseEntity;
import com.aihub.common.redis.RedisKeys;
import com.aihub.common.result.ErrorCode;
import com.aihub.user.dto.LoginRequest;
import com.aihub.user.dto.RegisterRequest;
import com.aihub.user.entity.UserEntity;
import com.aihub.common.auth.Role;
import com.aihub.user.mapper.UserMapper;
import com.aihub.user.service.AuthService;
import com.aihub.user.service.UserService;
import com.aihub.user.vo.LoginVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * 认证实现。
 *
 * <p>access token 无状态、不落 Redis，有效期短；refresh token 落 Redis，
 * 续期与主动注销都靠删除 Key 立即生效。
 */
@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    /** 同一维度连续失败达到该次数即锁定 */
    private static final int MAX_LOGIN_FAILURES = 5;

    /** 失败计数窗口 */
    private static final Duration LOGIN_FAILURE_TTL = Duration.ofMinutes(15);

    private final UserMapper userMapper;
    private final UserService userService;
    private final StringRedisTemplate redisTemplate;
    private final JwtSupport jwtSupport;

    public AuthServiceImpl(UserMapper userMapper,
                           UserService userService,
                           StringRedisTemplate redisTemplate,
                           JwtSupport jwtSupport) {
        this.userMapper = userMapper;
        this.userService = userService;
        this.redisTemplate = redisTemplate;
        this.jwtSupport = jwtSupport;
    }

    @Override
    @Transactional
    public Long register(RegisterRequest request) {
        Long existing = userMapper.selectCount(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getUsername, request.username())
                .eq(UserEntity::getDeleted, BaseEntity.NOT_DELETED));
        if (existing != null && existing > 0) {
            throw new BizException(ErrorCode.USERNAME_ALREADY_EXISTS);
        }

        UserEntity entity = new UserEntity();
        entity.setUsername(request.username());
        // BCrypt 自带盐值，禁止 MD5 / SHA1 / 明文
        entity.setPassword(BCrypt.hashpw(request.password()));
        entity.setNickname(StrUtil.isBlank(request.nickname()) ? request.username() : request.nickname());
        entity.setEmail(request.email());
        // 注册即 User 角色；Visitor 是「未登录」，不落库
        entity.setRole(Role.USER.name());
        entity.setDeleted(BaseEntity.NOT_DELETED);

        try {
            userMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            // 先查后插之间有竞态窗口，靠唯一索引兜底
            throw new BizException(ErrorCode.USERNAME_ALREADY_EXISTS);
        }
        log.info("user registered, userId={}, username={}", entity.getId(), entity.getUsername());
        return entity.getId();
    }

    @Override
    public LoginVo login(LoginRequest request, String clientIp) {
        assertNotLocked(clientIp, request.username());

        UserEntity user = findByUsername(request.username());
        // 用户不存在与密码错误返回同一错误码，避免账号枚举
        if (user == null || !BCrypt.checkpw(request.password(), user.getPassword())) {
            recordFailure(clientIp, request.username());
            throw new BizException(ErrorCode.PASSWORD_INCORRECT);
        }

        resetFailures(clientIp, request.username());
        LoginVo loginVo = issueTokens(user);
        log.info("user logged in, userId={}, username={}", user.getId(), user.getUsername());
        return loginVo;
    }

    @Override
    public LoginVo refresh(String refreshToken) {
        AuthenticatedUser claims = jwtSupport.verifyAndParse(refreshToken);
        if (claims.role() != null) {
            // access token 不允许用于续期
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }

        String key = RedisKeys.refreshToken(claims.userId(), claims.jwtId());
        // 必须用 getAndDelete 一步取出并作废：hasKey + delete 是两次往返，
        // 并发续期会全部放行，轮换失效。取不到即失效，这也是注销能立即生效的原因。
        if (redisTemplate.opsForValue().getAndDelete(key) == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }

        UserEntity user = userService.getActiveUser(claims.userId());
        return issueTokens(user);
    }

    @Override
    public void logout(String accessToken, String refreshToken) {
        AuthenticatedUser claims = jwtSupport.verifyAndParse(accessToken);

        long remainingSeconds = Duration.between(Instant.now(), claims.expiresAt()).getSeconds();
        if (remainingSeconds > 0) {
            // 黑名单 TTL 与 token 剩余有效期一致，不能永不过期
            redisTemplate.opsForValue().set(
                    RedisKeys.accessTokenBlacklist(claims.jwtId()), "1", Duration.ofSeconds(remainingSeconds));
        }

        if (StrUtil.isNotBlank(refreshToken)) {
            try {
                AuthenticatedUser refreshClaims = jwtSupport.verifyAndParse(refreshToken);
                redisTemplate.delete(RedisKeys.refreshToken(refreshClaims.userId(), refreshClaims.jwtId()));
            } catch (BizException e) {
                // refresh token 已失效，无需撤销
                log.debug("refresh token not revocable on logout: {}", e.getMessage());
            }
        }
        log.info("user logged out, userId={}", claims.userId());
    }

    private LoginVo issueTokens(UserEntity user) {
        String accessToken = jwtSupport.createAccessToken(user.getId(), user.getRole());
        String refreshToken = jwtSupport.createRefreshToken(user.getId());
        // 取 jti 拼 Redis Key
        AuthenticatedUser refreshClaims = jwtSupport.verifyAndParse(refreshToken);
        redisTemplate.opsForValue().set(
                RedisKeys.refreshToken(user.getId(), refreshClaims.jwtId()),
                "1",
                jwtSupport.getRefreshTokenTtl());

        long expiresIn = Duration.between(Instant.now(), jwtSupport.verifyAndParse(accessToken).expiresAt()).getSeconds();
        return new LoginVo(accessToken, refreshToken, expiresIn, userService.toVo(user));
    }

    private UserEntity findByUsername(String username) {
        return userMapper.selectOne(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getUsername, username)
                .eq(UserEntity::getDeleted, BaseEntity.NOT_DELETED));
    }

    private void assertNotLocked(String clientIp, String username) {
        if (failureCount(RedisKeys.loginFailureByIp(clientIp)) >= MAX_LOGIN_FAILURES
                || failureCount(RedisKeys.loginFailureByUsername(username)) >= MAX_LOGIN_FAILURES) {
            throw new BizException(ErrorCode.LOGIN_ATTEMPT_LIMITED);
        }
    }

    private void recordFailure(String clientIp, String username) {
        increment(RedisKeys.loginFailureByIp(clientIp));
        increment(RedisKeys.loginFailureByUsername(username));
    }

    private void resetFailures(String clientIp, String username) {
        redisTemplate.delete(RedisKeys.loginFailureByIp(clientIp));
        redisTemplate.delete(RedisKeys.loginFailureByUsername(username));
    }

    private long failureCount(String key) {
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private void increment(String key) {
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            // 只在首次出现时设 TTL，否则每次失败都顺延窗口，永远锁不住
            redisTemplate.expire(key, LOGIN_FAILURE_TTL);
        }
    }
}
