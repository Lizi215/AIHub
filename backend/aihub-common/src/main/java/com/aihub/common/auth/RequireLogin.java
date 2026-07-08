package com.aihub.common.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记接口需要登录。
 *
 * <p>由 {@link JwtUserContextInterceptor} 在 {@code preHandle} 阶段检查：没有有效 token 时直接拒绝。
 * Gateway 已做第一道校验，这里是纵深防御，服务被内网进程直接调用时也不会失守。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireLogin {
}
