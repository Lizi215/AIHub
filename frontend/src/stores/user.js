import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

const ACCESS_TOKEN_KEY = 'aihub_access_token'
const REFRESH_TOKEN_KEY = 'aihub_refresh_token'

/**
 * 从 JWT 的 exp 反推到期时刻（毫秒），取不到返回 0。
 *
 * 只用于决定什么时候续期，不参与鉴权判断（鉴权始终在后端）；
 * 有它硬刷新后也能继续提前续期，不用干等一次 401。
 */
function expiresAtFromJwt(token) {
  try {
    const payload = token.split('.')[1]
    if (!payload) {
      return 0
    }
    const base64 = payload.replace(/-/g, '+').replace(/_/g, '/')
    const json = JSON.parse(atob(base64.padEnd(Math.ceil(base64.length / 4) * 4, '=')))
    return Number.isFinite(json?.exp) ? json.exp * 1000 : 0
  } catch {
    return 0
  }
}

/**
 * 登录态。跨页面共享状态放 Pinia，不塞进组件。
 *
 * 所有 ID 按字符串保存：19 位雪花 ID 超出 JS 安全整数范围，不能 parseInt / Number，否则丢精度。
 */
export const useUserStore = defineStore('user', () => {
  const accessToken = ref(localStorage.getItem(ACCESS_TOKEN_KEY) || '')
  const refreshToken = ref(localStorage.getItem(REFRESH_TOKEN_KEY) || '')
  const userId = ref('')
  const nickname = ref('')
  const role = ref('')
  /**
   * access token 到期时刻（毫秒）。优先用登录/续期响应的 expiresIn，
   * 刷新页面后从 token 的 exp 推断。只放内存，丢了只是退化成撞到 401 再续。
   */
  const accessTokenExpiresAt = ref(expiresAtFromJwt(accessToken.value))

  const isLoggedIn = computed(() => Boolean(accessToken.value))

  /**
   * 是否该提前续期。留 2 分钟余量，避免请求刚发出去 token 就过期。
   */
  const isAccessTokenExpiringSoon = computed(() => {
    if (!accessToken.value || !accessTokenExpiresAt.value) {
      return false
    }
    return Date.now() + 120 * 1000 >= accessTokenExpiresAt.value
  })

  function setTokens({ access, refresh, expiresIn }) {
    accessToken.value = access || ''
    refreshToken.value = refresh || ''
    persist(ACCESS_TOKEN_KEY, accessToken.value)
    persist(REFRESH_TOKEN_KEY, refreshToken.value)
    // expiresIn 是秒；后端显式以数字返回（docs/API.md §5）
    accessTokenExpiresAt.value =
      accessToken.value && Number.isFinite(expiresIn) ? Date.now() + expiresIn * 1000 : 0
  }

  function setProfile(profile) {
    userId.value = profile?.id ?? ''
    nickname.value = profile?.nickname ?? ''
    role.value = profile?.role ?? ''
  }

  function clear() {
    accessToken.value = ''
    refreshToken.value = ''
    userId.value = ''
    nickname.value = ''
    role.value = ''
    accessTokenExpiresAt.value = 0
    localStorage.removeItem(ACCESS_TOKEN_KEY)
    localStorage.removeItem(REFRESH_TOKEN_KEY)
  }

  function persist(key, value) {
    if (value) {
      localStorage.setItem(key, value)
    } else {
      localStorage.removeItem(key)
    }
  }

  return {
    accessToken,
    refreshToken,
    userId,
    nickname,
    role,
    accessTokenExpiresAt,
    isLoggedIn,
    isAccessTokenExpiringSoon,
    setTokens,
    setProfile,
    clear
  }
})
