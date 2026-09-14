import request from '@/utils/request'

/** 注册，返回新用户 id（字符串形式的雪花 ID） */
export function register(data) {
  return request.post('/auth/register', data)
}

/**
 * 登录，返回 { accessToken, refreshToken, expiresIn, user }
 */
export function login(data) {
  return request.post('/auth/login', data)
}

/**
 * 注销。必须把 refreshToken 一并提交，否则 refresh token 仍能换回新 access token。
 */
export function logout(data) {
  return request.post('/auth/logout', data)
}

/** 用 refresh token 换新令牌 */
export function refreshToken(data) {
  return request.post('/auth/refresh', data)
}

/** 当前登录用户 */
export function getCurrentUser() {
  return request.get('/users/me')
}
