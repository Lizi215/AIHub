package com.aihub.common.auth;

import cn.hutool.core.exceptions.ValidateException;
import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTUtil;
import cn.hutool.jwt.JWTValidator;
import cn.hutool.jwt.RegisteredPayload;
import cn.hutool.jwt.signers.JWTSigner;
import cn.hutool.jwt.signers.JWTSignerUtil;
import com.aihub.common.exception.BizException;
import com.aihub.common.result.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 签发与校验，基于 Hutool（HS256）。
 *
 * <p>access token 无状态、不落 Redis；refresh token 落 Redis，用于续期与主动注销。
 * 每个 token 带 jti，注销时用它把 access token 写入黑名单。role 随 token 签发，
 * 业务服务直接从 token 读取，无需回查 User Service。
 */
public class JwtSupport {

    /** 自定义 claim：角色 */
    public static final String CLAIM_ROLE = "role";

    private final JwtProperties properties;

    // signer 每个线程一个。Hutool 的 signer 内部持有 javax.crypto.Mac，不是线程安全的；
    // 本类是单例，多线程共用会让并发签名的 update 相互交错、校验偶发失败。
    private final ThreadLocal<JWTSigner> signers;

    public JwtSupport(JwtProperties properties) {
        String secret = properties.getSecret();
        if (secret == null || secret.isBlank()) {
            // 空密钥等于允许伪造 token，启动即失败
            throw new IllegalStateException(
                    "aihub.jwt.secret is required; set JWT_SECRET env or Apollo config, empty secret is not allowed");
        }
        this.properties = properties;
        byte[] key = secret.getBytes(StandardCharsets.UTF_8);
        this.signers = ThreadLocal.withInitial(() -> JWTSignerUtil.hs256(key));
    }

    private JWTSigner signer() {
        return signers.get();
    }

    public String createAccessToken(Long userId, String role) {
        return createToken(userId, role, properties.getAccessTokenTtl());
    }

    public String createRefreshToken(Long userId) {
        return createToken(userId, null, properties.getRefreshTokenTtl());
    }

    public Duration getRefreshTokenTtl() {
        return properties.getRefreshTokenTtl();
    }

    /**
     * 校验签名与有效期并解析载荷。
     *
     * <p>Hutool 对非法 token 抛的是它自己的异常，这里统一转成业务异常，
     * 避免穿透成 500、把实现细节暴露给调用方。
     *
     * @throws BizException 签名非法 {@link ErrorCode#TOKEN_INVALID}；已过期 {@link ErrorCode#TOKEN_EXPIRED}
     */
    public AuthenticatedUser verifyAndParse(String token) {
        if (token == null || token.isBlank()) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }

        JWT jwt;
        try {
            if (!JWTUtil.verify(token, signer())) {
                throw new BizException(ErrorCode.TOKEN_INVALID);
            }
            jwt = JWTUtil.parseToken(token);
            JWTValidator.of(jwt).validateDate();
        } catch (BizException e) {
            throw e;
        } catch (ValidateException e) {
            throw new BizException(ErrorCode.TOKEN_EXPIRED);
        } catch (Exception e) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }

        return new AuthenticatedUser(
                parseUserId(jwt),
                asString(jwt.getPayload(CLAIM_ROLE)),
                asString(jwt.getPayload(RegisteredPayload.JWT_ID)),
                readExpiresAt(jwt));
    }

    private String createToken(Long userId, String role, Duration ttl) {
        Instant now = Instant.now();
        JWT jwt = JWT.create()
                .setSigner(signer())
                .setIssuer(properties.getIssuer())
                .setSubject(String.valueOf(userId))
                .setJWTId(UUID.randomUUID().toString().replace("-", ""))
                .setIssuedAt(Date.from(now))
                .setExpiresAt(Date.from(now.plus(ttl)));
        if (role != null) {
            jwt.setPayload(CLAIM_ROLE, role);
        }
        return jwt.sign();
    }

    private Long parseUserId(JWT jwt) {
        Object subject = jwt.getPayload(RegisteredPayload.SUBJECT);
        if (subject == null) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
        try {
            return Long.valueOf(String.valueOf(subject));
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
    }

    /** 读 exp。Hutool 落盘形态不定（Number / Date / 字符串），按可能的形态取值；取不到判为非法 token。 */
    private Instant readExpiresAt(JWT jwt) {
        Object raw = jwt.getPayload(RegisteredPayload.EXPIRES_AT);
        if (raw instanceof Number number) {
            return Instant.ofEpochSecond(number.longValue());
        }
        if (raw instanceof Date date) {
            return date.toInstant();
        }
        if (raw instanceof CharSequence text) {
            try {
                return Instant.ofEpochSecond(Long.parseLong(text.toString()));
            } catch (NumberFormatException e) {
                throw new BizException(ErrorCode.TOKEN_INVALID);
            }
        }
        throw new BizException(ErrorCode.TOKEN_INVALID);
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
