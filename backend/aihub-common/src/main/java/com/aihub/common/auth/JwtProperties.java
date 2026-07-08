package com.aihub.common.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * JWT 配置。密钥必须来自环境变量或 Apollo，禁止写入代码或提交到 Git（docs/SECURITY.md §1）。
 *
 * <p>不给 {@code secret} 设默认值：缺失时启动即失败，避免用默认密钥静默上线，那等于任何人都能伪造 token。
 */
@ConfigurationProperties(prefix = "aihub.jwt")
public class JwtProperties {

    /** HMAC-SHA256 密钥，长度建议不少于 32 字节。 */
    private String secret;

    /** 签发者标识，写入 iss。 */
    private String issuer = "aihub";

    /** access token 有效期，短有效期以降低泄露风险。 */
    private Duration accessTokenTtl = Duration.ofMinutes(30);

    /** refresh token 有效期，落 Redis，用于续期与主动注销。 */
    private Duration refreshTokenTtl = Duration.ofDays(7);

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public Duration getAccessTokenTtl() {
        return accessTokenTtl;
    }

    public void setAccessTokenTtl(Duration accessTokenTtl) {
        this.accessTokenTtl = accessTokenTtl;
    }

    public Duration getRefreshTokenTtl() {
        return refreshTokenTtl;
    }

    public void setRefreshTokenTtl(Duration refreshTokenTtl) {
        this.refreshTokenTtl = refreshTokenTtl;
    }
}
