<script setup>
import { nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { ElMessage, ElMessageBox } from 'element-plus'
import { cancelAgentTask, confirmAgentTask } from '@/api/agent'
import { createWorkspace } from '@/api/workspace'
import { createAsset, downloadAssetVersion, publishAsset, uploadAssetVersion } from '@/api/asset'
import { listAssetTags, setAssetTags } from '@/api/tag'
import { saveBlob } from '@/utils/download'
import JSZip from 'jszip'
import { useAgentChatStore } from '@/stores/agentChat'

const SCENES = [
  { value: 'SEARCH', label: 'AI 搜索' },
  { value: 'DOWNLOAD', label: 'AI 下载助手' },
  { value: 'WORKSPACE', label: 'Workspace 组合' },
  { value: 'UPLOAD', label: '上传助手' },
  { value: 'BUG_ANALYSIS', label: 'Bug 分析' }
]
const STATUS_TAG = { PENDING: 'info', RUNNING: 'primary', WAITING_CONFIRM: 'warning', SUCCEEDED: 'success', FAILED: 'danger', CANCELLED: 'info' }
const STATUS_TEXT = { PENDING: '排队中', RUNNING: '执行中', WAITING_CONFIRM: '等待确认', SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已取消' }

/** Tool 名 → 用户能看懂的说法（计划与结果里展示用） */
const TOOL_LABELS = {
  search_assets: '检索资源',
  get_asset_detail: '查看资源详情',
  prepare_download: '准备下载',
  build_workspace_draft: '生成 Workspace 组合草案',
  analyze_upload_candidate: '分析上传候选',
  analyze_upload_batch: '批量分析上传候选',
  list_my_assets: '查询我的资源',
  update_asset_tags: '修改资源标签',
  list_issues: '查看资源讨论',
  list_posts: '查看社区帖子'
}
const toolLabel = (tool) => TOOL_LABELS[tool] || tool

const sceneLabel = (value) => SCENES.find((s) => s.value === value)?.label || value

// 会话状态在 Pinia store 里：路由切换不丢，刷新后由 sessionStorage 恢复
const chat = useAgentChatStore()
const { messages, busy } = storeToRefs(chat)

const form = reactive({ scene: 'SEARCH', request: '' })
const sending = ref(false)

/* 批量上传：UPLOAD 场景下选择的本地文件夹（浏览器扫描，File 引用只存内存） */
const folderInput = ref(null)
const pickedFolders = ref([])
const MAX_BATCH = 10

function triggerFolderPick() {
  folderInput.value?.click()
}

/** webkitdirectory 的 files 带 webkitRelativePath，按一级子目录分组。
 *  只把至少三层路径的组当目录，根目录下的散文件不是待上传资源，排除 */
function onFolderPick(event) {
  const files = Array.from(event.target.files || [])
  const groups = new Map()
  let hasSubdir = false
  for (const file of files) {
    const parts = (file.webkitRelativePath || '').split('/')
    if (parts.length < 2) {
      continue
    }
    // 子目录分组（根/子目录/文件）；整个目录下没有子目录时，根目录本身就是资源
    const dir = parts.length >= 3 ? parts[1] : parts[0]
    const strip = parts.length >= 3 ? 2 : 1
    hasSubdir = hasSubdir || parts.length >= 3
    if (!groups.has(dir)) {
      groups.set(dir, { name: dir, strip, files: [] })
    }
    groups.get(dir).files.push(file)
  }
  if (!groups.size) {
    ElMessage.warning('所选文件夹是空的')
    event.target.value = ''
    return
  }
  const incoming = Array.from(groups.values())
  if (incoming.length > MAX_BATCH) {
    ElMessage.warning(`最多支持 ${MAX_BATCH} 个资源，已截取前 ${MAX_BATCH} 个`)
    incoming.length = MAX_BATCH
  }
  // 追加而不是替换：浏览器目录选择框一次只能选一个目录，
  // 连续多次"选择文件夹"即可凑齐一批（同名组会被新选择覆盖）
  const merged = [...pickedFolders.value]
  for (const group of incoming) {
    const idx = merged.findIndex((f) => f.name === group.name)
    if (idx >= 0) {
      merged[idx] = group
    } else {
      merged.push(group)
    }
  }
  if (merged.length > MAX_BATCH) {
    ElMessage.warning(`累计已选 ${merged.length} 个，超出 ${MAX_BATCH} 的部分不会上传`)
  }
  pickedFolders.value = merged.slice(0, MAX_BATCH)
  event.target.value = ''
}

function clearPickedFolders() {
  pickedFolders.value = []
}

function send() {
  const origText = form.request.trim()

  // 跨轮指令：没重新选文件夹却说「直接上传/直接发布」→ 落到最近的批量任务上执行。
  // 分析已完成就立即执行，还在跑就记下指令、完成时再执行；没有批量上下文时不拦截，
  // 交给正常 Agent 对话（避免劫持"怎么上传资源"这类普通提问）
  if (form.scene === 'UPLOAD' && !pickedFolders.value.length && /发布|上传/.test(origText)) {
    // 情形 0：上一批已经上传完（batchDone）→ 直接发布其中"已创建为草稿"的行
    const doneDrafts = lastUnpublishedDrafts()
    if (doneDrafts.length) {
      chat.appendUserMessage(form.scene, origText)
      form.request = ''
      scrollToBottom(true)
      publishDrafts(doneDrafts) // 发布自带逐行状态更新与成功提示，无需等待
      return
    }
    const candidate = lastBatchCandidate()
    if (candidate) {
      chat.appendUserMessage(form.scene, origText)
      form.request = ''
      scrollToBottom(true)
      if (!candidate.msg.files?.length) {
        ElMessage.warning('文件夹引用已丢失（页面刷新过）：请重新选择文件夹后再发一次')
        return
      }
      confirmBatchUpload(candidate.msg, candidate.step, { autoPublish: true })
      return
    }
    const running = findBatchInProgress()
    if (running) {
      chat.appendUserMessage(form.scene, origText)
      form.request = ''
      scrollToBottom(true)
      // 指令排队：分析任务终态后由 watcher 自动执行「上传 + 发布」
      running.pendingAutoPublish = true
      if (!running.files?.length) {
        ElMessage.warning('已收到指令，但文件夹引用丢失（页面刷新过）：请重新选择文件夹后再发一次')
      } else {
        ElMessage.info('已收到指令：等分析完成后自动上传并发布')
      }
      return
    }
    // 无批量上下文：不拦截，走正常 Agent 对话
  }

  // 批量上传：用已选文件夹生成约定格式的批量请求；气泡显示用户原始描述
  let displayText = origText
  let autoPublish = false
  let filesForBatch = []
  if (form.scene === 'UPLOAD' && pickedFolders.value.length) {
    const names = pickedFolders.value.map((f) => f.name)
    // 描述里出现"发布"→ 上传完成后自动逐个发布
    autoPublish = /发布/.test(origText)
    // 描述里明确说了类型（如"这些都是 skill"）→ 随协议传给服务端，保证分类不被模型改掉
    const typeHint = detectTypeHint(origText)
    form.request = `批量上传：${names.join('；')}${typeHint ? `｜类型：${typeHint}` : ''}`
    displayText = origText || `批量上传 ${names.length} 个文件夹`
    filesForBatch = pickedFolders.value
  } else if (form.scene === 'UPLOAD' && !origText) {
    ElMessage.warning('请描述你的需求，或先选择要上传的文件夹')
    return
  }
  const text = form.request.trim()
  if (!text) {
    ElMessage.warning('请描述你的需求')
    return
  }
  sending.value = true
  // 发消息是用户主动行为，无论如何都跳到底部（并恢复自动跟随）
  scrollToBottom(true)
  chat
    .send(form.scene, text, displayText)
    .then((ai) => {
      // File 引用只存内存：气泡恢复（刷新）后丢失，需重新选择
      if (ai && filesForBatch.length) {
        ai.files = filesForBatch
        ai.autoPublish = autoPublish
      }
    })
    .finally(() => {
      sending.value = false
    })
  form.request = ''
  clearPickedFolders()
}

/**
 * 从文件夹里读 aihub.yaml 的 requires。浏览器没有 YAML 库，只解析本约定用到的最小子集：
 * requires 列表 + 「- name」/「- name: x」两种写法。读不到或解析失败都当作无依赖。
 */
async function readRequires(group) {
  try {
    const entry = group.files.find((f) => {
      const rel = f.webkitRelativePath.split('/').slice(f.strip ?? 2).join('/') || f.name
      return rel === 'aihub.yaml'
    })
    if (!entry) {
      return []
    }
    const text = await entry.text()
    const deps = []
    let inRequires = false
    for (const rawLine of text.split(String.fromCharCode(10))) {
      const line = rawLine.replace(/#.*$/, '').trimEnd()
      if (/^requires:\s*$/.test(line)) {
        inRequires = true
        continue
      }
      if (!inRequires) {
        continue
      }
      const item = line.match(/^\s*-\s+(.*)$/)
      if (!item) {
        if (line && !line.startsWith('#')) {
          break // requires 列表结束
        }
        continue
      }
      const body = item[1].trim()
      // 两种写法都只取名字（version 约束由后端解析，排序只需要名字）：
      // 「- save-last」简写 / 「- name: save-last」映射
      const inline = body.match(/^["']?([\w.-]+)["']?\s*$/)
      const kv = body.match(/^name:\s*["']?([\w.-]+)["']?\s*$/)
      const name = inline ? inline[1] : kv ? kv[1] : null
      if (name) {
        deps.push(name)
      }
    }
    return deps
  } catch {
    return []
  }
}

/** 按批次内依赖做拓扑排序（Kahn）。环 / 外部依赖不阻塞：保持原相对顺序。 */
async function orderByDependencies(suggestions, files) {
  const names = new Set(suggestions.map((s) => s.name))
  const depsByName = new Map()
  for (const sug of suggestions) {
    const group = files.find((f) => f.name === sug.name)
    const all = group ? await readRequires(group) : []
    // 只关心"本批次内"的依赖：外部依赖由后端解析（已发布资源），不影响顺序
    depsByName.set(sug.name, all.filter((d) => names.has(d) && d !== sug.name))
  }
  const ordered = []
  const done = new Set()
  const remaining = [...suggestions]
  let guard = remaining.length * remaining.length + 1
  while (remaining.length && guard-- > 0) {
    const idx = remaining.findIndex((sug) =>
      depsByName.get(sug.name).every((d) => done.has(d))
    )
    if (idx < 0) {
      break // 剩下的互相成环：按原顺序补完（后端会拦住成环的边并如实提示）
    }
    const [sug] = remaining.splice(idx, 1)
    done.add(sug.name)
    ordered.push(sug)
  }
  return [...ordered, ...remaining]
}

/** 批量上传按钮文案：跟随真实行为（要求直接发布时不能还写着"创建为草稿"），完成后置灰 */
function batchActionLabel(msg) {
  if (msg.batchDone) {
    return '已完成'
  }
  return msg.autoPublish ? '确认上传并发布' : '确认上传（创建为草稿）'
}

/** 从用户描述里识别资源类型声明（与后端约定格式配合，保证"我说了是 skill"真生效） */
const TYPE_KEYWORDS = [
  ['SKILL', /skill|技能/i],
  ['HOOK', /hook|钩子|拦截/i],
  ['PROMPT', /prompt|提示词/i],
  ['MCP', /mcp/i],
  ['RULES', /rules?|规则|规范/i],
  ['TEMPLATE', /template|模板|脚手架/i]
]

function detectTypeHint(text) {
  if (!text) {
    return null
  }
  for (const [type, pattern] of TYPE_KEYWORDS) {
    if (pattern.test(text)) {
      return type
    }
  }
  return null
}

/** 找正在进行中的批量分析任务（还没终态、也没完成上传的那条），用于给指令排队 */
function findBatchInProgress() {
  for (let i = messages.value.length - 1; i >= 0; i--) {
    const msg = messages.value[i]
    if (msg.role === 'ai' && msg.scene === 'UPLOAD' && msg.taskId && !msg.batchDone
        && msg.task && !isTerminal(msg.task)) {
      return msg
    }
  }
  return null
}

/** 最近一次批量上传里「还没发布」的行，承接"帮我把这些草稿发布"。
 *  按 _published 标记判断而不是状态文案：追加到草稿的新版本也是草稿，同样该被接住。 */
function lastUnpublishedDrafts() {
  for (let i = messages.value.length - 1; i >= 0; i--) {
    const msg = messages.value[i]
    if (msg.role !== 'ai' || !msg.task?.result?.steps) {
      continue
    }
    const step = msg.task.result.steps.find(
      (s) => s.tool === 'analyze_upload_batch' && s.data?.suggestions?.length
    )
    if (!step) {
      continue
    }
    const drafts = step.data.suggestions.filter(
      (sug) => sug._assetId && sug._published !== true
    )
    if (drafts.length || msg.batchDone) {
      return drafts // 找到最近一批就停：全发布了就返回空（调用方提示没有可发布的）
    }
  }
  return []
}

function lastBatchCandidate() {
  for (let i = messages.value.length - 1; i >= 0; i--) {
    const msg = messages.value[i]
    if (msg.role !== 'ai' || msg.batchDone || !msg.task?.result?.steps) {
      continue
    }
    const step = msg.task.result.steps.find(
      (s) => s.tool === 'analyze_upload_batch' && s.data?.suggestions?.length
    )
    if (step) {
      return { msg, step }
    }
  }
  return null
}

/** 逐个发布草稿（publishAsset 走用户自己的登录态，合法写链路） */
async function publishDrafts(rows) {
  let published = 0
  for (const row of rows) {
    try {
      await publishAsset(row._assetId)
      row._published = true
      row._status = '已发布'
      published++
    } catch {
      // 失败提示由 utils/request.js 统一处理，继续发后面的
    }
  }
  if (published) {
    ElMessage.success(`已发布 ${published} 个资源`)
  } else if (rows.length) {
    // 一个都没成功：多半是刷新后内存标记丢失、点到了已发布的资源（后端拒绝重复发布），
    // 如实提示去列表确认，不要静默无反应。
    ElMessage.warning('没有资源被发布：可能它们已经是已发布状态，请到「资源列表 → 只看我的」确认')
  }
}

const chatScroll = ref(null)
/** 用户是否"贴着"底部：贴底时自动跟随新消息；往上翻了就不再打扰 */
const pinned = ref(true)

/** 滚动位置监听：距离底部 40px 内视为贴底 */
function onScroll() {
  const el = chatScroll.value
  if (!el) {
    return
  }
  pinned.value = el.scrollHeight - el.scrollTop - el.clientHeight < 40
}

function scrollToBottom(force = false) {
  // 用户正在回看历史时不拽滚动条；force 用于发送新消息这类"必须跳到底"的场景
  if (!force && !pinned.value) {
    return
  }
  nextTick(() => {
    const el = chatScroll.value
    if (el) {
      el.scrollTop = el.scrollHeight
      pinned.value = true
    }
  })
}

// 会话任何变化（新消息 / 轮询状态更新 / 结果渲染）都尝试跟随，但只在贴底时生效
watch(messages, () => scrollToBottom(), { deep: true })

// 排队中的「上传并发布」指令：批量分析任务一到终态就自动执行
watch(messages, () => {
  for (const msg of messages.value) {
    if (msg.role !== 'ai' || !msg.pendingAutoPublish || !msg.task || !isTerminal(msg.task)) {
      continue
    }
    msg.pendingAutoPublish = false
    const step = (msg.task.result?.steps || []).find(
      (s) => s.tool === 'analyze_upload_batch' && s.data?.suggestions?.length
    )
    if (!step || !msg.files?.length) {
      ElMessage.warning('分析已完成，但无法自动上传（缺少文件夹引用或没有候选），请重新选择文件夹')
      continue
    }
    confirmBatchUpload(msg, step, { autoPublish: true })
  }
}, { deep: true })

onMounted(() => {
  chat.restore()
  scrollToBottom()
})

onBeforeUnmount(() => {
  // 只停 timer，不清会话——store 继续持有状态，切回时恢复
  chat.stopAll()
})

/** 开启新对话：二次确认，避免误触丢掉当前会话 */
async function startNewConversation() {
  if (messages.value.length) {
    await ElMessageBox.confirm('将清空当前对话记录（右侧任务不受影响），确定开始新对话？', '开启新对话', {
      type: 'warning',
      confirmButtonText: '开始新对话',
      cancelButtonText: '取消'
    })
  }
  chat.newConversation()
  ElMessage.success('已开启新对话')
}

const isTerminal = (task) => ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(task?.status)

async function confirmTask(ai) {
  await confirmAgentTask(ai.taskId)
  ElMessage.success('已确认，继续执行')
}

async function cancelTask(ai) {
  await cancelAgentTask(ai.taskId)
  ElMessage.success('任务已取消')
}

/**
 * 执行中每个计划步骤的状态。task.steps 只含**已执行**的步骤，
 * 因此按下标对应计划：没有对应记录的就是还没轮到。
 */
function planStepStatus(ai, index) {
  const executed = ai.task?.steps?.[index]
  if (!executed) {
    return '待执行'
  }
  if (executed.status === 'SUCCEEDED') {
    return '已完成'
  }
  if (executed.status === 'FAILED') {
    return '失败'
  }
  return '执行中'
}

const hasFailed = (step) => step.status === 'FAILED'

function excerpt(text, limit = 200) {
  const t = String(text ?? '')
  return t.length > limit ? `${t.slice(0, limit)}…` : t
}

async function downloadFromStep(data) {
  busy[data.assetId] = true
  try {
    const blob = await downloadAssetVersion(data.assetId, data.version)
    saveBlob(blob, `${data.name || 'asset'}-${data.version}.zip`)
    ElMessage.success('下载已开始')
  } catch {
    // 失败提示由 utils/request.js 统一处理
  } finally {
    busy[data.assetId] = false
  }
}

const CATEGORIES = ['SKILL', 'HOOK', 'PROMPT', 'MCP', 'RULES', 'TEMPLATE', 'OTHER']

/**
 * 批量上传执行：按建议逐行压缩对应文件夹并创建资源（默认草稿）。
 * 字节全部来自浏览器内存里保存的 File 引用；行状态写在建议对象上随渲染更新。
 */
async function confirmBatchUpload(msg, step, opts = {}) {
  const suggestions = step.data.suggestions || []
  const files = msg.files || []
  // 指令来自跨轮（"直接发布"）时同步到消息上：按钮文案与行为必须一致
  if (opts.autoPublish != null) {
    msg.autoPublish = opts.autoPublish
  }
  const autoPublish = msg.autoPublish
  busy[msg.id] = true
  let created = 0

  // 同批互相依赖 → 按依赖拓扑排序上传（被依赖者先建，后者的 aihub.yaml 才能解析到）。
  // 依赖清单从各文件夹的 aihub.yaml 里读；解析失败当无依赖（不阻断上传）
  const ordered = await orderByDependencies(suggestions, files)
  for (const sug of ordered) {
    if (sug.batchDuplicate || sug._assetId) {
      continue
    }
    sug._status = '压缩中…'
    try {
      const group = files.find((f) => f.name === sug.name)
      if (!group) {
        sug._status = '失败：文件夹引用丢失，请重新选择后重试'
        continue
      }
      const zip = new JSZip()
      for (const file of group.files) {
        // webkitRelativePath = 根目录/子目录/文件，去掉前两层后即 zip 内相对路径
        const rel = file.webkitRelativePath.split('/').slice(file.strip ?? 2).join('/') || file.name
        zip.file(rel, file)
      }
      const blob = await zip.generateAsync({ type: 'blob' })
      const formData = new FormData()
      formData.append('file', blob, `${sug.name}.zip`)
      if (sug.readme) {
        formData.append('readme', sug.readme)
      }

      // 自己已有同名资源 → **追加新版本**（不再造重复资源）。
      // version 留空：后端按补丁号自动递增（1.0.0 → 1.0.1）；
      // 已发布资源上传新版本会自动发布（后端规则），所以这里直接就是"上传并发布"
      const targetAssetId = sug.sameNameAssetId
      if (targetAssetId) {
        sug._status = '追加新版本中…'
        // 追加到「已发布」资源后端会把新版本一并发布，追加到草稿则仍是草稿。
        // 状态必须读接口返回的 publishStatus，硬编码会让后续「帮我发布」漏接。
        const appended = await uploadAssetVersion(targetAssetId, formData)
        sug._assetId = targetAssetId
        sug._published = appended?.publishStatus === 'PUBLISHED'
        sug._status = sug._published ? '已发布（追加新版本）' : '已追加新版本（草稿）'
        // 标签合并：保留资源已有标签，并入 AI 建议的
        if (sug.suggestedTags?.length) {
          try {
            const existingTags = await listAssetTags(targetAssetId)
            const merged = [...new Set([...(existingTags || []), ...sug.suggestedTags])]
            if (merged.length > (existingTags || []).length) {
              await setAssetTags(targetAssetId, merged)
            }
          } catch {
            // 同上，失败不影响上传结果
          }
        }
        created++
        continue
      }

      sug._status = '创建中…'
      const assetId = await createAsset({
        name: sug.name,
        summary: sug.summary || '',
        category: sug.category || 'SKILL'
      })
      formData.append('version', '1.0.0')
      await uploadAssetVersion(assetId, formData)
      sug._assetId = assetId
      sug._published = false // 新建的一律是草稿（发布由 autoPublish / 后续指令决定）
      sug._status = '已创建（草稿）'
      // AI 建议的标签要真正落库，否则资源列表里看不到标签
      if (sug.suggestedTags?.length) {
        try {
          await setAssetTags(assetId, sug.suggestedTags)
        } catch {
          // 打标签失败不回滚上传：提示由统一错误处理给
        }
      }
      created++
    } catch {
      sug._status = '失败（可重试）'
    }
  }
  busy[msg.id] = false
  if (created) {
    msg.batchDone = true
    ElMessage.success(`已创建 ${created} 个资源`)
    // 用户要求直接发布（同轮描述含「发布」或跨轮指令）→ 创建完自动逐个发布
    if (autoPublish) {
      const createdRows = suggestions.filter((sug) => sug._assetId && sug._published !== true)
      await publishDrafts(createdRows)
    } else {
      ElMessage.info('已存为草稿，可在下方逐个发布，或发「直接帮我上传并发布吧」')
    }
  }
}

async function publishBatchRow(sug) {
  try {
    await publishAsset(sug._assetId)
    sug._published = true
    sug._status = '已发布'
    ElMessage.success(`「${sug.name}」已发布`)
  } catch {
    // 失败提示由 utils/request.js 统一处理
  }
}

/** 从 AI 气泡里提取 Workspace 草案（仅 WORKSPACE 场景产出） */
function workspaceDraftOf(ai) {  const step = (ai.task?.result?.steps || []).find(
    (s) => s.tool === 'build_workspace_draft' && s.data?.items?.length && !hasFailed(s)
  )
  return step?.data || null
}

async function saveWorkspace(ai) {
  const draft = workspaceDraftOf(ai)
  if (!draft) {
    return
  }
  const { value } = await ElMessageBox.prompt('请输入 Workspace 名称', '保存 Workspace', {
    inputValue: draft.title || '',
    inputPattern: /\S+/,
    inputErrorMessage: '名称不能为空'
  })
  busy[ai.id] = true
  try {
    // AI 草案落库由用户确认后发起（docs/AGENT.md）；条目在 User Service 侧还会再校验。
    // description 由 AI 在草案阶段生成（build_workspace_draft 的参数），随 Workspace 落库展示
    await createWorkspace({
      name: value,
      description: draft.description || '',
      items: draft.items.map((it) => ({ assetId: it.assetId, version: it.version }))
    })
    ai.wsSaved = true
    ElMessage.success('Workspace 已保存，可在「我的 Workspace」查看')
  } finally {
    busy[ai.id] = false
  }
}

/** Enter 发送、Shift+Enter 换行；输入法组合中的回车（选字）不发送 */
function onComposerEnter(event) {
  if (event.isComposing) {
    return
  }
  event.preventDefault()
  send()
}
</script>

<template>
  <el-card shadow="never" class="agent">
    <div class="agent__header">
      <span>AI Agent</span>
      <span class="agent__headerHint">
        未配置模型 API Key 时由内置脚本生成回复（mock 模式），执行链路与真实模型一致
      </span>
      <el-button
        size="small"
        class="agent__newChat"
        :disabled="!messages.length"
        @click="startNewConversation"
      >
        开启新对话
      </el-button>
    </div>

    <div ref="chatScroll" class="agent__scroll" @scroll="onScroll">
      <!-- 欢迎语 -->
      <div class="msg msg--ai">
        <div class="msg__bubble">
          <div class="msg__meta"><span>AIHub 助手</span></div>
          <p class="msg__text">
            你好，我是 AIHub 的 Agent 助手。先在下方选择一个场景，再用自然语言描述需求，
            我会给出执行计划；涉及下载等需要确认的操作时，会先征求你的同意。
          </p>
        </div>
      </div>

      <div v-for="msg in messages" :key="msg.id" class="msg" :class="msg.role === 'user' ? 'msg--user' : 'msg--ai'">
        <!-- 用户消息 -->
        <div v-if="msg.role === 'user'" class="msg__bubble">
          <div class="msg__meta"><span>{{ sceneLabel(msg.scene) }}</span></div>
          <p class="msg__text">{{ msg.text }}</p>
        </div>

        <!-- AI 消息：一个 Agent 任务的实时进展 -->
        <div v-else class="msg__bubble">
          <template v-if="!msg.task">
            <div class="msg__meta"><span>AIHub 助手</span></div>
            <p class="msg__text">正在创建任务…</p>
            <div class="typing"><span></span><span></span><span></span></div>
          </template>
          <template v-else>
            <div class="msg__meta">
              <el-tag :type="STATUS_TAG[msg.task.status] || 'info'" size="small">
                {{ STATUS_TEXT[msg.task.status] || msg.task.status }}
              </el-tag>
              <span>{{ sceneLabel(msg.task.scene) }}</span>
              <span v-if="msg.task.model">· {{ msg.task.model === 'mock' ? 'mock 模式' : msg.task.model }}</span>
            </div>

            <el-alert
              v-if="msg.task.injectionDetected"
              type="warning"
              show-icon
              :closable="false"
              title="检测到疑似提示注入"
              description="资源描述中包含可疑指令，已被中和，不影响本任务的执行计划"
              class="msg__alert"
            />

            <p v-if="msg.task.plan?.summary" class="msg__text">{{ msg.task.plan.summary }}</p>

            <!-- 执行进度（未到终态时） -->
            <ol v-if="!isTerminal(msg.task) && msg.task.plan?.steps?.length" class="msg__plan">
              <li v-for="(s, i) in msg.task.plan.steps" :key="i">
                <span class="msg__planIndex">{{ i + 1 }}.</span>
                {{ s.intent || toolLabel(s.tool) }}
                <span class="msg__planStatus">— {{ planStepStatus(msg, i) }}</span>
              </li>
            </ol>

            <div v-if="!isTerminal(msg.task) && msg.task.status === 'RUNNING'" class="typing">
              <span></span><span></span><span></span>
            </div>

            <!-- 确认门禁：涉及下载等写入操作必须用户点头 -->
            <div v-if="msg.task.awaitingConfirmation" class="msg__actions">
              <el-button type="primary" size="small" @click="confirmTask(msg)">确认执行</el-button>
              <el-button size="small" @click="cancelTask(msg)">取消</el-button>
            </div>

            <!-- 终态：把每个工具步骤转成一段可读的回答 -->
            <template v-if="isTerminal(msg.task)">
              <div
                v-for="(s, i) in msg.task.result?.steps || []"
                :key="i"
                class="msg__step"
                :class="{ 'msg__step--failed': hasFailed(s) }"
              >
                <p class="msg__stepTitle">
                  {{ hasFailed(s) ? '✕' : '✓' }} {{ s.intent || toolLabel(s.tool) }}
                </p>
                <p class="msg__text">{{ s.message }}</p>

                <!-- 检索结果：候选资源列表 -->
                <ul v-if="s.tool === 'search_assets' && s.data?.items?.length" class="msg__items">
                  <li v-for="it in s.data.items" :key="it.assetId">
                    <b>{{ it.name }}</b>
                    <span class="msg__sub">
                      （{{ it.category }}{{ it.latestVersion ? ` · v${it.latestVersion}` : '' }}）
                      {{ it.summary }}
                    </span>
                  </li>
                </ul>

                <!-- 标签修改结果：写操作的关键信息是"改了哪些资源、最终标签是什么" -->
                <div v-else-if="s.tool === 'update_asset_tags' && s.data?.updated?.length" class="msg__card">
                  <p class="msg__sub">已修改 {{ s.data.updated.length }} 个资源：</p>
                  <ul class="msg__items">
                    <li v-for="it in s.data.updated" :key="it.assetId">
                      <b>{{ it.name }}</b>
                      <span class="msg__sub">
                        <el-tag v-for="t in it.tags" :key="t" size="small" class="mineTag">{{ t }}</el-tag>
                      </span>
                    </li>
                  </ul>
                </div>

                <!-- 我的资源：owner 视角，草稿可见 -->
                <ul v-else-if="s.tool === 'list_my_assets' && s.data?.items?.length" class="msg__items">
                  <li v-for="it in s.data.items" :key="it.assetId">
                    <b>{{ it.name }}</b>
                    <el-tag
                      :type="it.status === 'DRAFT' ? 'info' : it.status === 'PUBLISHED' ? 'success' : 'danger'"
                      size="small"
                      class="mineTag"
                    >
                      {{ it.status }}
                    </el-tag>
                    <span v-if="it.latestVersion" class="msg__sub">v{{ it.latestVersion }}</span>
                  </li>
                </ul>

                <!-- 资源详情：README 摘要 -->
                <div v-else-if="s.tool === 'get_asset_detail' && s.data?.name" class="msg__card">
                  <p>
                    <b>{{ s.data.name }}</b>
                    <span class="msg__sub">（{{ s.data.category }} · v{{ s.data.latestVersion }}）</span>
                  </p>
                  <p class="msg__sub">{{ s.data.summary }}</p>
                  <p v-if="s.data.readme" class="msg__readme">{{ excerpt(s.data.readme) }}</p>
                </div>

                <!-- 下载：给出真实下载动作 -->
                <div v-else-if="s.tool === 'prepare_download' && s.data?.downloadUrl" class="msg__card">
                  <p>
                    <b>{{ s.data.name }}</b>
                    <span class="msg__sub"> v{{ s.data.version }} · {{ s.data.size }} 字节</span>
                  </p>
                  <el-button
                    size="small"
                    type="primary"
                    :loading="busy[s.data.assetId]"
                    @click="downloadFromStep(s.data)"
                  >
                    下载 {{ s.data.version }}
                  </el-button>
                </div>

                <!-- Workspace 草案：可保存 -->
                <div v-else-if="s.tool === 'build_workspace_draft' && s.data?.items?.length" class="msg__card">
                  <p class="msg__sub">组合草案，共 {{ s.data.items.length }} 个资源：</p>
                  <ul class="msg__items">
                    <li v-for="it in s.data.items" :key="it.assetId">
                      {{ it.name }}<span class="msg__sub">（v{{ it.version }}）</span>
                    </li>
                  </ul>
                  <el-button
                    size="small"
                    type="primary"
                    :loading="busy[msg.id]"
                    :disabled="msg.wsSaved"
                    @click="saveWorkspace(msg)"
                  >
                    {{ msg.wsSaved ? '已保存' : '保存为 Workspace' }}
                  </el-button>
                </div>

                <!-- 批量上传建议：表格确认后浏览器端压缩并批量创建草稿 -->
                <div
                  v-else-if="s.tool === 'analyze_upload_batch' && s.data?.suggestions?.length"
                  class="msg__card msg__batch"
                >
                  <p class="msg__sub">
                    AI 上传建议（{{ s.data.suggestions.length }} 个）：可修改名称/分类/README，
                    确认后自动压缩并创建为草稿
                  </p>
                  <div v-for="(sug, idx) in s.data.suggestions" :key="idx" class="batchRow">
                    <div class="batchRow__main">
                      <el-input v-model="sug.name" size="small" class="batchRow__name" />
                      <el-select v-model="sug.category" size="small" class="batchRow__category" placeholder="分类">
                        <el-option v-for="c in CATEGORIES" :key="c" :label="c" :value="c" />
                      </el-select>
                      <span class="msg__sub">{{ (sug.suggestedTags || []).join(' / ') || '无标签建议' }}</span>
                      <!-- 自己的同名资源分两种：草稿（追加后仍是草稿）与已发布（追加即发布）。
                           文案按 sameNameStatus 如实显示，不再一律说"已发布" -->
                      <el-tag v-if="sug.exactNameDuplicate" type="danger" size="small">
                        {{ sug.sameNameStatus === 'PUBLISHED'
                          ? '你已发布过同名资源（将追加新版本）'
                          : '你已有同名资源草稿（将追加新版本）' }}
                      </el-tag>
                      <el-tag v-else-if="sug.otherAuthorSameName" type="info" size="small">他人同名（正常）</el-tag>
                      <el-tag v-if="sug.batchDuplicate" type="warning" size="small">批内重名</el-tag>
                      <span class="msg__sub">{{ sug._status || '待确认' }}</span>
                      <!-- 只要这行还没发布（新建草稿，或追加到草稿上的新版本）就给手动发布入口 -->
                      <el-button
                        v-if="sug._assetId && sug._published !== true"
                        link
                        type="success"
                        size="small"
                        @click="publishBatchRow(sug)"
                      >
                        发布
                      </el-button>
                    </div>
                    <details class="batchRow__readme">
                      <summary>
                        简介与 README（{{ sug.readmeSource === 'ai-draft' || sug.summary ? 'AI 生成' : '无，可自行填写' }}）
                      </summary>
                      <el-input
                        v-model="sug.summary"
                        size="small"
                        :placeholder="sug.summary || '资源简介（一句话，会展示在资源列表）'"
                        class="batchRow__summary"
                      />
                      <el-input
                        v-model="sug.readme"
                        type="textarea"
                        :rows="5"
                        size="small"
                        :placeholder="'README 草稿（Markdown）'"
                      />
                    </details>
                  </div>
                  <el-button
                    type="primary"
                    size="small"
                    :loading="busy[msg.id]"
                    :disabled="msg.batchDone || !msg.files?.length"
                    @click="confirmBatchUpload(msg, s)"
                  >
                    {{ batchActionLabel(msg) }}
                  </el-button>
                  <p v-if="!msg.files?.length" class="msg__sub">
                    文件夹引用已丢失（刷新页面会这样）：请重新选择文件夹后再确认上传
                  </p>
                </div>
              </div>

              <p v-if="msg.task.error" class="msg__error">{{ msg.task.error }}</p>
            </template>
          </template>
        </div>
      </div>
    </div>

    <!-- 输入区：一体化输入卡——场景胶囊在上，无边框输入在中，提示与发送在下 -->
    <div class="agent__composer">
      <div class="composer">
        <div class="composer__scenes" role="radiogroup" aria-label="选择场景">
          <button
            v-for="s in SCENES"
            :key="s.value"
            type="button"
            class="scenePill"
            :class="{ 'scenePill--active': form.scene === s.value }"
            @click="form.scene = s.value"
          >
            {{ s.label }}
          </button>
          <!-- 批量上传：只有 UPLOAD 场景出现；浏览器读目录，AI 只出建议 -->
          <template v-if="form.scene === 'UPLOAD'">
            <input
              ref="folderInput"
              type="file"
              class="composer__folderInput"
              webkitdirectory
              multiple
              @change="onFolderPick"
            />
            <el-button size="small" @click="triggerFolderPick">选择文件夹</el-button>
            <span v-if="pickedFolders.length" class="composer__picked">
              已选 {{ pickedFolders.length }} 个：{{ pickedFolders.map((f) => f.name).join('、') }}
              <el-button link size="small" @click="clearPickedFolders">清除</el-button>
            </span>
          </template>
        </div>
        <el-input
          v-model="form.request"
          type="textarea"
          :autosize="{ minRows: 2, maxRows: 6 }"
          resize="none"
          :placeholder="form.scene === 'UPLOAD'
            ? '选择要批量上传的文件夹，AI 会给出命名/分类/README 建议，确认后自动创建草稿'
            : '描述你的需求，例如：帮我组合一套 spring 配置相关的资源'"
          class="composer__input"
          @keydown.enter="onComposerEnter"
        />
        <div class="composer__footer">
          <span class="composer__hint">Enter 发送 · Shift+Enter 换行 · 场景决定 Agent 的工作方式</span>
          <el-button type="primary" size="small" :loading="sending" @click="send">发送</el-button>
        </div>
      </div>
    </div>
  </el-card>
</template>

<style scoped>
.agent :deep(.el-card__body) {
  padding: 0;
  display: flex;
  flex-direction: column;
  /* 顶部导航 60px + 主区上下内边距 48px + 卡片边框 2px；少减会多出一截页面空白 */
  height: calc(100vh - 110px);
  min-height: 480px;
}

.agent__header {
  display: flex;
  align-items: baseline;
  gap: 12px;
  padding: 14px 16px;
  border-bottom: 1px solid #e5e6eb;
  font-weight: 600;
}

.agent__headerHint {
  font-weight: 400;
  font-size: 12px;
  color: #86909c;
  flex: 1;
  /* 过长时省略，给右侧按钮让位 */
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.agent__newChat {
  flex-shrink: 0;
}

.agent__scroll {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  background: #f7f8fa;
}

.msg {
  display: flex;
  margin-bottom: 12px;
}

.msg--user {
  justify-content: flex-end;
}

.msg__bubble {
  max-width: 82%;
  background: #fff;
  border: 1px solid #e5e6eb;
  border-radius: 12px;
  border-bottom-left-radius: 4px;
  padding: 10px 14px;
  box-shadow: 0 1px 2px rgba(30, 35, 45, 0.04);
  /* 对话正文比全局默认（14px）小一号：同样的信息量少占屏，长对话更好读。
     行高略微放松，避免字号变小后行间距显得拥挤 */
  font-size: 13px;
  line-height: 1.65;
}

.msg--user .msg__bubble {
  background: #e8f3ff;
  border-color: #c9e2ff;
  border-radius: 12px;
  border-bottom-right-radius: 4px;
}

/* 执行中的打字指示：三个渐隐渐现的圆点 */
.typing {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  margin-top: 6px;
  height: 14px;
}

.typing span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #a8c7fa;
  animation: typing 1.2s infinite ease-in-out;
}

.typing span:nth-child(2) {
  animation-delay: 0.2s;
}

.typing span:nth-child(3) {
  animation-delay: 0.4s;
}

@keyframes typing {
  0%, 60%, 100% {
    opacity: 0.25;
    transform: translateY(0);
  }
  30% {
    opacity: 1;
    transform: translateY(-3px);
  }
}

.msg__meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #86909c;
  margin-bottom: 6px;
}

.msg__text {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
}

.msg__alert {
  margin: 8px 0;
}

.msg__plan {
  margin: 8px 0 0;
  padding-left: 18px;
}

.msg__planIndex {
  color: #86909c;
}

.msg__planStatus {
  color: #86909c;
  font-size: 12px;
}

.msg__actions {
  margin-top: 10px;
  display: flex;
  gap: 8px;
}

.msg__step {
  border-top: 1px dashed #e5e6eb;
  margin-top: 10px;
  padding-top: 8px;
}

.msg__stepTitle {
  margin: 0 0 4px;
  font-weight: 600;
  font-size: 13px;
}

.msg__step--failed .msg__stepTitle {
  color: #f53f3f;
}

.msg__items {
  margin: 6px 0 0;
  padding-left: 18px;
}

.msg__items li {
  margin-bottom: 4px;
}

.msg__card {
  background: #f7f8fa;
  border-radius: 6px;
  padding: 8px 10px;
  margin-top: 6px;
}

.msg__readme {
  margin: 6px 0 0;
  color: #4e5969;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-word;
}

.msg__sub {
  color: #86909c;
  font-size: 12px;
}

.msg__error {
  margin: 8px 0 0;
  color: #f53f3f;
  font-size: 13px;
}

.agent__composer {
  border-top: 1px solid #e5e6eb;
  padding: 12px 16px;
  background: #fff;
}

/* 一体化输入卡：胶囊、输入、按钮共享一个容器，高度关系天然一致 */
.composer {
  border: 1px solid #e5e6eb;
  border-radius: 12px;
  padding: 10px 12px 8px;
  background: #fff;
  transition: border-color 0.15s, box-shadow 0.15s;
}

.composer:focus-within {
  border-color: #409eff;
  box-shadow: 0 0 0 2px rgba(64, 158, 255, 0.12);
}

.composer__scenes {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 6px;
}

.scenePill {
  border: 1px solid #e5e6eb;
  background: #f7f8fa;
  border-radius: 999px;
  padding: 3px 12px;
  font-size: 12px;
  line-height: 18px;
  color: #4e5969;
  cursor: pointer;
  transition: all 0.15s;
}

.scenePill:hover {
  border-color: #c9e2ff;
  color: #409eff;
}

.scenePill--active {
  background: #e8f3ff;
  border-color: #409eff;
  color: #409eff;
  font-weight: 600;
}

/* 输入框去掉自身边框，融入卡片 */
.composer__input :deep(.el-textarea__inner) {
  border: none;
  box-shadow: none;
  padding: 2px 0;
  font-size: 14px;
  line-height: 1.6;
  background: transparent;
}

.composer__footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 4px;
}


.mineTag {
  margin: 0 6px;
}
.composer__folderInput {
  display: none;
}
.composer__picked {
  font-size: 12px;
  color: #4e5969;
}
.batchRow {
  padding: 6px 0;
  border-bottom: 1px dashed #f0f1f3;
}
.batchRow__main {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.batchRow__name {
  width: 180px;
}
.batchRow__category {
  width: 110px;
}
.batchRow__readme {
  margin-top: 4px;
  font-size: 12px;
  color: #86909c;
}
.batchRow__readme summary {
  cursor: pointer;
}
.batchRow__summary {
  margin-bottom: 6px;
}
.composer__hint {
  font-size: 12px;
  color: #86909c;
}
</style>
