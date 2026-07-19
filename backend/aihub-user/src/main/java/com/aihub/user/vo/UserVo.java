package com.aihub.user.vo;

/**
 * 用户信息（对外）。
 *
 * <p>{@code id} 用 Long 声明即可：aihub-common 的 Jackson 配置会把 Long 全局序列化为 String，
 * 前端拿到字符串，不会丢雪花 ID 精度。
 */
public record UserVo(Long id, String username, String nickname, String role) {
}
