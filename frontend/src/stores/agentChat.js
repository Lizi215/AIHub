import { reactive, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { createAgentTask, getAgentTask, subscribeAgentTask } from '@/api/agent'

const STORAGE_KEY = 'aihub_agent_chat'
const TERMINAL = ['SUCCEEDED', 'FAILED', 'CANCELLED']

const isTerminal = (task) => TERMINAL.includes(task?.status)

/**
 * Agent 对话会话。放 Pinia 而不是组件里：切回本页时组件会重建，store 仍持有会话；
 * 另同步到 sessionStorage，整页刷新也能恢复。
 *
 * sessionId 是前端生成的 UUID，后端用它作历史 Key 的一部分，把最近几轮对话摘要存进 Redis
 * （TTL 2h）。新对话就是换一个新 UUID。订阅句柄不进响应式状态也不持久化，
 * 恢复会话时对未终态任务重连即可。
 */
export const useAgentChatStore = defineStore('agentChat', () => {
  /** role=user 用户输入；role=ai 一个 Agent 任务，task 随轮询原地更新 */
  const messages = ref([])
  /** 当前会话标识（后端历史 Key 的一部分）；null 表示下一轮发送时再生成 */
  const sessionId = ref(null)
  let seq = 0
  /** taskId -> 轮询 timer */
  const pollers = new Map()
  /** 按消息/资源记录下载、保存 Workspace 的进行中状态 */
  const busy = reactive({})

  function persist() {
    try {
      if (messages.value.length) {
        sessionStorage.setItem(STORAGE_KEY, JSON.stringify({ messages: messages.value, sessionId: sessionId.value }))
      } else {
        // 空会话不留空数组占位，避免"新对话"后残留脏数据
        sessionStorage.removeItem(STORAGE_KEY)
      }
    } catch {
      // 存不下（隐私模式/超配额）只影响刷新恢复，不影响当前会话
    }
  }

  // 任务状态被轮询原地更新，deep watch 保证每次变化都落 sessionStorage
  watch([messages, sessionId], persist, { deep: true })

  /** 取当前会话标识；还没有就用 UUID 开一个（后端用它作历史 Key 的一部分） */
  function ensureSessionId() {
    if (!sessionId.value) {
      sessionId.value = crypto.randomUUID()
    }
    return sessionId.value
  }

  /**
   * 订阅任务执行过程：优先 SSE（500ms 推快照，有过程感且省请求），
   * 流异常中断或服务端 120s 订阅超时且任务仍未终态时，回退到轮询兜底。
   */
  function subscribe(ai) {
    if (!ai.taskId || pollers.has(ai.taskId)) {
      return
    }
    const stopAndCleanup = () => {
      stop?.()
      pollers.delete(ai.taskId)
    }
    const stop = subscribeAgentTask(ai.taskId, {
      onTask: (task) => {
        ai.task = task
        if (isTerminal(task)) {
          stopAndCleanup()
        }
      },
      // 服务端只在终态或订阅超时时关闭流；超时时任务可能还在跑，回落轮询接力
      onEnd: () => {
        if (!isTerminal(ai.task)) {
          pollers.delete(ai.taskId)
          startPolling(ai)
        }
      },
      onError: () => {
        if (!isTerminal(ai.task)) {
          pollers.delete(ai.taskId)
          startPolling(ai)
        }
      }
    })
    pollers.set(ai.taskId, stopAndCleanup)
  }

  /** 轮询兜底：SSE 不可用（网关不支持流式 / token 续期间断流）时保证任务进度仍可见 */
  function startPolling(ai) {
    if (!ai.taskId || pollers.has(ai.taskId)) {
      return
    }
    const timer = setInterval(async () => {
      try {
        ai.task = await getAgentTask(ai.taskId)
        if (isTerminal(ai.task)) {
          clearInterval(timer)
          pollers.delete(ai.taskId)
        }
      } catch {
        clearInterval(timer)
        pollers.delete(ai.taskId)
      }
    }, 1500)
    pollers.set(ai.taskId, () => clearInterval(timer))
  }

  /** 只追加一条用户消息（本地指令，如"上传并发布"由前端执行，不创建 Agent 任务） */
  function appendUserMessage(scene, text) {
    messages.value.push({ id: ++seq, role: 'user', scene, text })
  }

  /** 发送一条用户消息并创建对应的 Agent 任务。
   *  displayText：气泡里显示的文本（批量上传时后端收到的是约定格式，气泡显示原始描述） */
  async function send(scene, text, displayText) {
    messages.value.push({ id: ++seq, role: 'user', scene, text: displayText || text })
    const ai = reactive({ id: ++seq, role: 'ai', scene, taskId: null, task: null })
    messages.value.push(ai)

    try {
      const taskId = await createAgentTask({ scene, request: text, sessionId: ensureSessionId() })
      ai.taskId = taskId
      ai.task = await getAgentTask(taskId)
      subscribe(ai)
    } catch {
      // 错误提示由 utils/request.js 统一处理，这里把失败落到气泡里
      ai.task = { status: 'FAILED', scene, error: '任务创建失败' }
    }
    return ai
  }

  /**
   * 恢复会话：组件挂载时调用。内存里已有（路由切回）什么都不做；
   * sessionStorage 里有（整页刷新）则还原，并对未终态任务重启轮询。
   */
  function restore() {
    if (messages.value.length) {
      return
    }
    let saved
    try {
      saved = JSON.parse(sessionStorage.getItem(STORAGE_KEY) || 'null')
    } catch {
      // 存量数据损坏按无会话处理
      saved = null
    }
    if (!saved) {
      return
    }
    // 兼容两种持久化形态：带 sessionId 的对象（现行）与纯消息数组（旧版）
    const savedMessages = Array.isArray(saved) ? saved : saved.messages
    if (!Array.isArray(savedMessages) || !savedMessages.length) {
      return
    }
    messages.value = savedMessages
    sessionId.value = Array.isArray(saved) ? null : saved.sessionId || null
    seq = Math.max(...savedMessages.map((m) => m.id || 0))
    for (const msg of messages.value) {
      if (msg.role === 'ai' && msg.taskId && !isTerminal(msg.task)) {
        subscribe(msg)
      }
    }
  }

  /** 开启新对话：清空消息与轮询并换新会话标识，旧历史在 Redis 里到 TTL 自动消失 */
  function newConversation() {
    pollers.forEach((stop) => stop())
    pollers.clear()
    messages.value = []
    sessionId.value = null
    seq = 0
    try {
      sessionStorage.removeItem(STORAGE_KEY)
    } catch {
      // 忽略
    }
  }

  function stopAll() {
    pollers.forEach((stop) => stop())
    pollers.clear()
  }

  return { messages, sessionId, busy, send, appendUserMessage, restore, newConversation, startPolling, stopAll }
})
