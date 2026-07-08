package com.aihub.api.dto;

/**
 * 用户简要信息（跨服务只暴露展示所需的最小字段，不泄露 email / 密码等）。
 *
 * <p>id 按项目约定序列化为字符串，见 README「Long 序列化契约」。
 */
public record UserBrief(Long id, String nickname) {
}
