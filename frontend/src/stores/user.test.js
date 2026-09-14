import { describe, it, expect, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useUserStore } from './user'

/** 造一个只有 payload 有意义的 JWT（前端只用它读 exp） */
function makeJwt(expSeconds) {
  const payload = btoa(JSON.stringify({ exp: expSeconds }))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '')
  return `header.${payload}.signature`
}

describe('useUserStore', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  it('无 token 时为未登录', () => {
    const store = useUserStore()
    expect(store.isLoggedIn).toBe(false)
    expect(store.accessToken).toBe('')
  })

  it('setTokens 写入内存与 localStorage，并按 expiresIn 计算到期时刻', () => {
    const store = useUserStore()
    const before = Date.now()
    store.setTokens({ access: 'a.b.c', refresh: 'r.r.r', expiresIn: 1800 })

    expect(store.accessToken).toBe('a.b.c')
    expect(store.refreshToken).toBe('r.r.r')
    expect(store.isLoggedIn).toBe(true)
    expect(localStorage.getItem('aihub_access_token')).toBe('a.b.c')
    expect(localStorage.getItem('aihub_refresh_token')).toBe('r.r.r')
    // 1800s 后到期，允许少量执行耗时偏差
    expect(store.accessTokenExpiresAt).toBeGreaterThanOrEqual(before + 1800 * 1000)
    expect(store.accessTokenExpiresAt).toBeLessThanOrEqual(Date.now() + 1800 * 1000)
  })

  it('isAccessTokenExpiringSoon：剩余不足 2 分钟 → true，充足 → false', () => {
    const store = useUserStore()
    store.setTokens({ access: 'a', refresh: 'r', expiresIn: 60 }) // 1 分钟
    expect(store.isAccessTokenExpiringSoon).toBe(true)

    store.setTokens({ access: 'a', refresh: 'r', expiresIn: 3600 }) // 1 小时
    expect(store.isAccessTokenExpiringSoon).toBe(false)
  })

  it('expiresIn 非有限值时不算到期时刻 → 不会误判需要续期', () => {
    const store = useUserStore()
    store.setTokens({ access: 'a', refresh: 'r', expiresIn: undefined })
    expect(store.accessTokenExpiresAt).toBe(0)
    expect(store.isAccessTokenExpiringSoon).toBe(false)
  })

  it('硬刷新后从 token 自身的 exp 推断到期时刻（无需等一次 401）', () => {
    const future = Math.floor(Date.now() / 1000) + 3600
    localStorage.setItem('aihub_access_token', makeJwt(future))
    setActivePinia(createPinia()) // 重新实例化，模拟页面刷新
    const store = useUserStore()

    expect(store.accessTokenExpiresAt).toBe(future * 1000)
    expect(store.isAccessTokenExpiringSoon).toBe(false)
  })

  it('token 的 exp 已过期 → 判定为应立即续期', () => {
    const past = Math.floor(Date.now() / 1000) - 10
    localStorage.setItem('aihub_access_token', makeJwt(past))
    setActivePinia(createPinia())
    const store = useUserStore()

    expect(store.isAccessTokenExpiringSoon).toBe(true)
  })

  it('JWT 结构损坏时安全降级（exp 取不到 = 0），不抛异常', () => {
    localStorage.setItem('aihub_access_token', 'not-a-jwt')
    setActivePinia(createPinia())
    const store = useUserStore()

    expect(store.accessTokenExpiresAt).toBe(0)
    expect(store.isLoggedIn).toBe(true) // 有 token 就算登录态，鉴权仍在后端
  })

  it('setProfile 原样保存字符串 ID（禁止 Number 转换，避免雪花 ID 丢精度）', () => {
    const store = useUserStore()
    store.setProfile({ id: '1945678901234567890', nickname: '昵称', role: 'ADMIN' })

    expect(store.userId).toBe('1945678901234567890')
    expect(store.nickname).toBe('昵称')
    expect(store.role).toBe('ADMIN')
  })

  it('setProfile 容忍缺字段', () => {
    const store = useUserStore()
    store.setProfile({})
    expect(store.userId).toBe('')
    expect(store.nickname).toBe('')
    expect(store.role).toBe('')
  })

  it('clear 清空全部状态与 localStorage', () => {
    const store = useUserStore()
    store.setTokens({ access: 'a', refresh: 'r', expiresIn: 100 })
    store.setProfile({ id: '1', nickname: 'n', role: 'USER' })

    store.clear()

    expect(store.accessToken).toBe('')
    expect(store.refreshToken).toBe('')
    expect(store.userId).toBe('')
    expect(store.accessTokenExpiresAt).toBe(0)
    expect(store.isLoggedIn).toBe(false)
    expect(localStorage.getItem('aihub_access_token')).toBeNull()
    expect(localStorage.getItem('aihub_refresh_token')).toBeNull()
  })

  it('setTokens 传空 access 时不会把空串写进 localStorage', () => {
    const store = useUserStore()
    store.setTokens({ access: '', refresh: '', expiresIn: 100 })
    expect(localStorage.getItem('aihub_access_token')).toBeNull()
  })
})
