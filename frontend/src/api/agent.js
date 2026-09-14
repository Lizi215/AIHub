import request from '@/utils/request'
import { useUserStore } from '@/stores/user'

const BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api/v1'

/** 创建 Agent 任务，立即返回 taskId（执行是异步的） */
export function createAgentTask(data) {
  return request.post('/agent/tasks', data)
}

/** 查询任务状态与结果 */
export function getAgentTask(id) {
  return request.get(`/agent/tasks/${id}`)
}

/**
 * SSE 订阅任务执行过程（服务端每 500ms 推一次快照，终态后自动关闭）。
 *
 * 用 fetch 而不是 EventSource：后者无法携带 Authorization 头，而该接口强制登录。
 * 中途断流时由调用方回退到轮询（轮询的 axios 拦截器会自动续期）。
 *
 * @returns 取消函数（组件卸载/清理时调用）
 */
export function subscribeAgentTask(id, { onTask, onEnd, onError }) {
  const controller = new AbortController()
  ;(async () => {
    try {
      const userStore = useUserStore()
      const resp = await fetch(`${BASE_URL}/agent/tasks/${id}/stream`, {
        headers: { Authorization: `Bearer ${userStore.accessToken}` },
        signal: controller.signal
      })
      if (!resp.ok || !resp.body) {
        throw new Error(`stream unavailable: ${resp.status}`)
      }
      const reader = resp.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      while (true) {
        const { done, value } = await reader.read()
        if (done) {
          break
        }
        buffer += decoder.decode(value, { stream: true })
        // SSE 帧以空行分隔；我们只关心 data 行（event 名固定为 status）
        let boundary
        while ((boundary = buffer.indexOf('\n\n')) >= 0) {
          const frame = buffer.slice(0, boundary)
          buffer = buffer.slice(boundary + 2)
          const dataLine = frame
            .split('\n')
            .find((line) => line.startsWith('data:'))
          if (!dataLine) {
            continue
          }
          try {
            onTask(JSON.parse(dataLine.slice(5).trim()))
          } catch {
            // 坏帧丢弃：下一帧快照是全量状态，不会失真
          }
        }
      }
      onEnd?.()
    } catch (e) {
      if (!controller.signal.aborted) {
        onError?.(e)
      }
    }
  })()
  return () => controller.abort()
}

/** 提交用户确认（WAITING_CONFIRM → RUNNING） */
export function confirmAgentTask(id) {
  return request.post(`/agent/tasks/${id}/confirm`)
}

/** 取消任务 */
export function cancelAgentTask(id) {
  return request.delete(`/agent/tasks/${id}`)
}
