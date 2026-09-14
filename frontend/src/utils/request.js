import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'
import { useUserStore } from '@/stores/user'

/** 与后端 docs/API.md §3 约定一致：0 表示成功 */
export const SUCCESS_CODE = 0

/**
 * 用户域认证错误码（1xxxx）：10001 未认证，10007 已过期。
 *
 * 收到这类错误时先尝试续期并重放原请求，续期不了才清登录态回登录页。
 */
const AUTH_FAILURE_CODES = [10001, 10007]

/** 与 request 实例同一个 baseURL（续期请求不能走本实例的拦截器，见 renewTokens） */
const BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api/v1'

/** 这些路径本身就是在处理认证，不参与「续期后重放」，否则会递归 */
const NON_RETRYABLE_PATHS = ['/auth/refresh', '/auth/login']

/**
 * 全站唯一 Axios 实例。禁止在组件里直接使用 axios（docs/FRONTEND.md §2）。
 */
const request = axios.create({
  baseURL: BASE_URL,
  timeout: 30000
})

/**
 * 正在进行的续期，必须单飞：后端 /auth/refresh 会轮换 refresh token，
 * 并发第二个请求拿旧 token 去换必然失败，还会把刚拿到的新 token 一起废掉。
 * 所以多个请求同时发现过期时只发起一次，其余复用同一个 Promise。
 */
let renewInFlight = null

/**
 * 用 refresh token 换新的一对 token。
 *
 * <p>刻意用裸 `axios` 而不是本实例：本实例的响应拦截器会给失败请求做续期重放，
 * 续期请求自己再走一遍就递归了。
 *
 * @returns 续期是否成功
 */
function renewTokens() {
  const userStore = useUserStore()
  if (!userStore.refreshToken) {
    return Promise.resolve(false)
  }
  if (renewInFlight) {
    return renewInFlight
  }

  renewInFlight = (async () => {
    try {
      const response = await axios.post(`${BASE_URL}/auth/refresh`, {
        refreshToken: userStore.refreshToken
      })
      const body = response.data
      if (!body || body.code !== SUCCESS_CODE) {
        return false
      }
      // 后端的 user 一并回填：续期顺带刷新了昵称/角色，且不依赖路由守卫再查一次
      userStore.setTokens({
        access: body.data.accessToken,
        refresh: body.data.refreshToken,
        expiresIn: body.data.expiresIn
      })
      userStore.setProfile(body.data.user)
      return true
    } catch {
      return false
    } finally {
      renewInFlight = null
    }
  })()

  return renewInFlight
}

/** 续期彻底失败：清登录态并回登录页（已经在登录页就不重复跳） */
function failToLogin(message) {
  useUserStore().clear()
  ElMessage.error(message || '登录已失效，请重新登录')
  if (router.currentRoute.value.name !== 'login') {
    router.push({ name: 'login' })
  }
}

function isAuthEndpoint(url) {
  return NON_RETRYABLE_PATHS.some((path) => (url || '').includes(path))
}

request.interceptors.request.use(async (config) => {
  // 主动续期：剩余不足 2 分钟就先换掉，避免请求刚发出去 token 就过期
  const userStore = useUserStore()
  if (!isAuthEndpoint(config.url) && userStore.isAccessTokenExpiringSoon) {
    await renewTokens()
  }
  const { accessToken } = useUserStore()
  if (accessToken) {
    config.headers.Authorization = `Bearer ${accessToken}`
  }
  return config
})

request.interceptors.response.use(
  async (response) => {
    const contentType = response.headers?.['content-type'] || ''
    let body = response.data

    // 下载类请求 responseType=blob：成功返回二进制流直接透出，
    // 失败时后端仍返回 JSON 错误体，必须读出来走错误分支，否则会静默无反应。
    if (response.config?.responseType === 'blob') {
      if (!contentType.includes('application/json')) {
        return body
      }
      body = JSON.parse(await body.text())
    }

    // 后端统一响应体是硬契约，结构不符说明链路上有非本项目服务或代理改写了响应
    if (!body || typeof body.code === 'undefined') {
      ElMessage.error('响应格式不符合统一契约')
      return Promise.reject(new Error('invalid response envelope'))
    }

    if (body.code === SUCCESS_CODE) {
      // 解包，业务层直接拿到 data，不再关心 code/message
      return body.data
    }

    if (AUTH_FAILURE_CODES.includes(body.code)) {
      const config = response.config
      // 续期后原样重放一次。config 里带着原始 body/params/responseType，能覆盖下载类请求。
      if (!config._retried && !isAuthEndpoint(config.url) && (await renewTokens())) {
        config._retried = true
        config.headers.Authorization = `Bearer ${useUserStore().accessToken}`
        return request(config)
      }
      failToLogin()
      return Promise.reject(new Error(body.message))
    }

    ElMessage.error(body.message || '请求失败')
    return Promise.reject(new Error(body.message))
  },
  (error) => {
    ElMessage.error(error.message || '网络异常')
    return Promise.reject(error)
  }
)

export default request
