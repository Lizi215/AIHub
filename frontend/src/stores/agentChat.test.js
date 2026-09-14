import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'

vi.mock('@/api/agent', () => ({
  createAgentTask: vi.fn(),
  getAgentTask: vi.fn(),
  subscribeAgentTask: vi.fn()
}))

import { createAgentTask, getAgentTask, subscribeAgentTask } from '@/api/agent'
import { useAgentChatStore } from './agentChat'

const STORAGE_KEY = 'aihub_agent_chat'

describe('useAgentChatStore', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    setActivePinia(createPinia())
    // 确定性 UUID，便于断言 sessionId 透传
    vi.stubGlobal('crypto', { randomUUID: vi.fn(() => 'uuid-1') })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('appendUserMessage 追加一条用户消息并递增 id', () => {
    const store = useAgentChatStore()
    store.appendUserMessage('upload', '第一条')
    store.appendUserMessage('upload', '第二条')

    expect(store.messages).toHaveLength(2)
    expect(store.messages[0]).toMatchObject({ id: 1, role: 'user', scene: 'upload', text: '第一条' })
    expect(store.messages[1].id).toBe(2)
  })

  it('send 成功：气泡展示 displayText，任务 id 落地并订阅 SSE', async () => {
    createAgentTask.mockResolvedValue('task-1')
    getAgentTask.mockResolvedValue({ status: 'RUNNING' })
    subscribeAgentTask.mockReturnValue(vi.fn())

    const store = useAgentChatStore()
    const ai = await store.send('upload', '后端约定格式', '给用户看的描述')

    expect(createAgentTask).toHaveBeenCalledWith({
      scene: 'upload',
      request: '后端约定格式',
      sessionId: 'uuid-1'
    })
    // 用户气泡显示 displayText，而不是发给后端的约定格式
    expect(store.messages[0]).toMatchObject({ role: 'user', text: '给用户看的描述' })
    expect(store.messages[1].role).toBe('ai')
    expect(ai.taskId).toBe('task-1')
    expect(ai.task).toEqual({ status: 'RUNNING' })
    expect(subscribeAgentTask).toHaveBeenCalledWith('task-1', expect.any(Object))
  })

  it('send 失败：把失败落进 AI 气泡，不抛出', async () => {
    createAgentTask.mockRejectedValue(new Error('boom'))

    const store = useAgentChatStore()
    const ai = await store.send('upload', 'req')

    expect(ai.task).toMatchObject({ status: 'FAILED', error: '任务创建失败' })
    expect(subscribeAgentTask).not.toHaveBeenCalled()
  })

  it('restore 从新格式 {messages, sessionId} 还原并恢复 seq', () => {
    sessionStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({ messages: [{ id: 5, role: 'user', scene: 's', text: 't' }], sessionId: 'sess-9' })
    )
    const store = useAgentChatStore()
    store.restore()

    expect(store.messages).toHaveLength(1)
    expect(store.sessionId).toBe('sess-9')
    // seq 恢复：下一条 id 从 6 继续，避免与历史消息 id 冲突
    store.appendUserMessage('s', 'x')
    expect(store.messages[1].id).toBe(6)
  })

  it('restore 兼容旧格式（纯消息数组）：sessionId 置空', () => {
    sessionStorage.setItem(
      STORAGE_KEY,
      JSON.stringify([{ id: 2, role: 'user', scene: 's', text: 'old' }])
    )
    const store = useAgentChatStore()
    store.restore()

    expect(store.messages).toHaveLength(1)
    expect(store.sessionId).toBeNull()
  })

  it('restore 遇到损坏 JSON 时按无会话处理，不抛异常', () => {
    sessionStorage.setItem(STORAGE_KEY, '{broken')
    const store = useAgentChatStore()
    expect(() => store.restore()).not.toThrow()
    expect(store.messages).toHaveLength(0)
  })

  it('restore 对未终态任务重连订阅，对已终态任务不重连', () => {
    subscribeAgentTask.mockReturnValue(vi.fn())
    sessionStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({
        messages: [
          { id: 1, role: 'ai', scene: 's', taskId: 'running-task', task: { status: 'RUNNING' } },
          { id: 2, role: 'ai', scene: 's', taskId: 'done-task', task: { status: 'SUCCEEDED' } }
        ],
        sessionId: 's'
      })
    )
    const store = useAgentChatStore()
    store.restore()

    expect(subscribeAgentTask).toHaveBeenCalledTimes(1)
    expect(subscribeAgentTask).toHaveBeenCalledWith('running-task', expect.any(Object))
  })

  it('内存里已有会话时 restore 是幂等的（路由切回不重复还原）', () => {
    const store = useAgentChatStore()
    store.appendUserMessage('s', 'existing')
    sessionStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({ messages: [{ id: 99, role: 'user', scene: 's', text: 'other' }], sessionId: 'x' })
    )

    store.restore()
    expect(store.messages).toHaveLength(1)
    expect(store.messages[0].text).toBe('existing')
  })

  it('newConversation 清空消息/会话并移除 sessionStorage', async () => {
    const store = useAgentChatStore()
    store.appendUserMessage('s', 'hi')
    await nextTick()

    store.newConversation()

    expect(store.messages).toHaveLength(0)
    expect(store.sessionId).toBeNull()
    expect(sessionStorage.getItem(STORAGE_KEY)).toBeNull()
    // 新对话后 id 从 1 重新计数
    store.appendUserMessage('s', 'again')
    expect(store.messages[0].id).toBe(1)
  })

  it('消息变化会持久化到 sessionStorage；空会话不残留占位', async () => {
    const store = useAgentChatStore()
    store.appendUserMessage('s', 'persist-me')
    await nextTick()

    const saved = JSON.parse(sessionStorage.getItem(STORAGE_KEY))
    expect(saved.messages).toHaveLength(1)
    expect(saved.messages[0].text).toBe('persist-me')

    store.newConversation()
    await nextTick()
    expect(sessionStorage.getItem(STORAGE_KEY)).toBeNull()
  })

  describe('startPolling 轮询兜底', () => {
    it('轮询到终态后停止，不再重复请求', async () => {
      vi.useFakeTimers()
      try {
        getAgentTask.mockResolvedValue({ status: 'SUCCEEDED' })
        const store = useAgentChatStore()
        const ai = { taskId: 'poll-1', task: null }

        store.startPolling(ai)
        await vi.advanceTimersByTimeAsync(1500)

        expect(ai.task.status).toBe('SUCCEEDED')
        expect(getAgentTask).toHaveBeenCalledTimes(1)

        // 终态后 interval 已清，再推进时间不应再调用
        await vi.advanceTimersByTimeAsync(4500)
        expect(getAgentTask).toHaveBeenCalledTimes(1)
      } finally {
        vi.useRealTimers()
      }
    })

    it('stopAll 停止所有轮询', async () => {
      vi.useFakeTimers()
      try {
        getAgentTask.mockResolvedValue({ status: 'RUNNING' })
        const store = useAgentChatStore()
        const ai = { taskId: 'poll-2', task: null }

        store.startPolling(ai)
        await vi.advanceTimersByTimeAsync(1500)
        expect(getAgentTask).toHaveBeenCalledTimes(1)

        store.stopAll()
        await vi.advanceTimersByTimeAsync(4500)
        expect(getAgentTask).toHaveBeenCalledTimes(1)
      } finally {
        vi.useRealTimers()
      }
    })
  })
})
