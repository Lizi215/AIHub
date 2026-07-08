package com.aihub.common.redis;

/**
 * Redis Key 统一命名（docs/DATA.md §3）。
 *
 * <p>命名规则固定为 {@code aihub:{业务域}:{标识}}，集中在这里定义，避免各服务各写一套字符串导致 Key 漂移。
 *
 * <p>每个 Key 都必须有 TTL，禁止出现永久膨胀的 Key。
 * 黑名单与 refresh token 的 TTL 与其对应 token 的剩余有效期一致。
 */
public final class RedisKeys {

    private static final String PREFIX = "aihub:";

    private RedisKeys() {
    }

    /**
     * refresh token：{@code aihub:rt:{userId}:{jti}}
     *
     * <p>Redis 中不存在即视为失效，这是支持主动注销的前提。
     */
    public static String refreshToken(Long userId, String jti) {
        return PREFIX + "rt:" + userId + ":" + jti;
    }

    /**
     * access token 注销黑名单：{@code aihub:blacklist:{jti}}
     *
     * <p>access token 无状态、无法主动失效，注销时把 jti 写入黑名单直至其自然过期。
     */
    public static String accessTokenBlacklist(String jti) {
        return PREFIX + "blacklist:" + jti;
    }

    /**
     * 登录失败计数：{@code aihub:login:fail:ip:{ip}} / {@code aihub:login:fail:user:{username}}
     *
     * <p>按 IP 与账号双维度限流（docs/SECURITY.md §3），防止针对单一账号或单一来源的暴力破解。
     */
    public static String loginFailureByIp(String ip) {
        return PREFIX + "login:fail:ip:" + ip;
    }

    public static String loginFailureByUsername(String username) {
        return PREFIX + "login:fail:user:" + username;
    }

    /**
     * 消息幂等：{@code aihub:idem:event:{eventId}}
     *
     * <p>消费端处理前先抢占该 Key，成功即视为首次处理。TTL 需明显长于消息重试窗口。
     */
    public static String eventIdempotency(String eventId) {
        return PREFIX + "idem:event:" + eventId;
    }

    /**
     * 热门资源榜：{@code aihub:hot:asset:{yyyyMMdd}}
     *
     * <p>按天分段，配合 TTL 与冷榜清理，禁止使用单一不过期的 ZSet（docs/DATA.md §3）。
     */
    public static String hotAssetRank(String yyyyMMdd) {
        return PREFIX + "hot:asset:" + yyyyMMdd;
    }

    /**
     * 下载去重（防刷量）：{@code aihub:dl:dedup:{assetId}:{version}:{userId}}
     *
     * <p>同一用户对同一 asset + version 在时间窗口内只计一次，
     * 否则热门榜可以被反复下载刷上去（docs/SECURITY.md §3）。
     */
    public static String assetDownloadDedup(Long assetId, String version, Long userId) {
        return PREFIX + "dl:dedup:" + assetId + ":" + version + ":" + userId;
    }

    /**
     * 帖子点赞去重：{@code aihub:like:post:{postId}}，Set，成员为 userId。
     *
     * <p>它只是快路径与缓存；「重复点赞不重复计」的硬保证在 MySQL 的
     * {@code uk_post_like} 唯一索引上，因此本集合即使丢失或过期也不会造成重复计数。
     */
    public static String postLikeSet(Long postId) {
        return PREFIX + "like:post:" + postId;
    }
}
