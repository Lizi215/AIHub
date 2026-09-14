<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  batchDeleteAssetVersions,
  deleteAssetVersion,
  downloadAsset,
  downloadAssetVersion,
  downloadAssetVersionReadme,
  getAssetDetail,
  getDependencyTree,
  listAssetDependencies,
  listAssetVersions,
  publishAsset,
  updateAsset,
  uploadAssetVersion
} from '@/api/asset'
import {
  changeIssueStatus,
  createIssue,
  createIssueComment,
  listIssueComments,
  listIssuesByAsset
} from '@/api/issue'
import { listAssetTags, listTags, setAssetTags } from '@/api/tag'
import { useUserStore } from '@/stores/user'
import { saveBlob } from '@/utils/download'
import { formatDateTime } from '@/utils/format'

/**
 * 资源详情弹窗（基本信息 / 版本 / 依赖 / 讨论 + 下载）。
 *
 * 列表页与搜索页共用：搜索返回的 ES 文档没有版本、依赖、README 全文，
 * 必须回查业务接口，这套取数逻辑只写一份。
 */
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  assetId: { type: [String, Number], default: null },
  /** true 时打开弹窗自动切到版本 Tab 并展开上传表单（空草稿"继续上传"入口用） */
  autoOpenUpload: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue', 'changed'])

const userStore = useUserStore()

const loading = ref(false)
const detail = ref(null)
const tab = ref('basic')
const versions = ref([])
const dependencies = ref([])
const dependencyRows = ref([])
const downloading = ref(false)

/* 版本级下载：分别记录「正在下载的是哪个版本」，避免重复点击 */
const downloadingVersion = ref('')
const downloadingReadme = ref('')

/* 修改 / 发布 / 删除（仅归属者本人） */
const editVisible = ref(false)
const saving = ref(false)
const publishing = ref(false)
const editForm = reactive({ name: '', summary: '', category: 'OTHER' })

/** 分类下拉选项，与 AssetCreateView 的取值保持一致 */
const CATEGORY_OPTIONS = ['SKILL', 'HOOK', 'PROMPT', 'MCP', 'RULES', 'TEMPLATE', 'OTHER']

/** 资源状态标签样式（与资源列表页保持一致） */
const ASSET_STATUS_TAG = { DRAFT: 'info', PUBLISHED: 'success', SUSPENDED: 'danger' }

/* 上传新版本 */
const uploadVisible = ref(false)
const uploading = ref(false)
const uploadForm = reactive({ file: null, version: '', changelog: '', readme: '' })

/* 讨论 */
const issues = ref([])
const issuesLoading = ref(false)
const issueFormVisible = ref(false)
const submittingIssue = ref(false)
const issueForm = reactive({ title: '', content: '', version: '' })
const expandedIssues = ref([])
const commentsByIssue = reactive({})
const commentDraft = reactive({})

/** 只有 owner 能上传新版本（后端 requireOwned 兜底，这里只是别把按钮给错人） */
const isOwner = computed(
  () => Boolean(detail.value?.ownerId) && detail.value.ownerId === userStore.userId
)

/**
 * 作者展示：接口会带 authorName，跨服务取昵称失败时后端降级为 null，
 * 这里兜底成「用户 #id 后 6 位」，至少能区分是谁。
 */
function authorLabel(authorName, authorId) {
  if (authorName) {
    return authorName
  }
  return authorId ? `用户 #${String(authorId).slice(-6)}` : '匿名'
}

function flattenTree(nodes, depth = 0, out = []) {
  for (const node of nodes || []) {
    out.push({ ...node, depth })
    flattenTree(node.children, depth + 1, out)
  }
  return out
}

function resetState() {
  detail.value = null
  tab.value = 'basic'
  versions.value = []
  dependencies.value = []
  dependencyRows.value = []
  issues.value = []
  expandedIssues.value = []
  issueFormVisible.value = false
  uploadVisible.value = false
  editVisible.value = false
  downloadingVersion.value = ''
  downloadingReadme.value = ''
  assetTags.value = []
  tagDraft.value = []
  Object.keys(commentsByIssue).forEach((k) => delete commentsByIssue[k])
  Object.keys(commentDraft).forEach((k) => delete commentDraft[k])
  Object.assign(uploadForm, { file: null, version: '', changelog: '', readme: '' })
  Object.assign(issueForm, { title: '', content: '', version: '' })
}

async function load(id) {
  loading.value = true
  resetState()
  try {
    detail.value = await getAssetDetail(id)
  } catch {
    // 失败提示由 utils/request.js 统一处理（例如资源不可见）
    loading.value = false
    return
  }
  loading.value = false

  // 详情已展示，其余数据随后补上；每个请求单独兜底，一个失败不影响其他 Tab
  const [versionList, dependencyList, tree] = await Promise.all([
    listAssetVersions(id).catch(() => []),
    listAssetDependencies(id).catch(() => []),
    getDependencyTree(id).catch(() => [])
  ])
  versions.value = versionList
  dependencies.value = dependencyList
  dependencyRows.value = flattenTree(tree)
  loadIssues(id)
  loadTags(id)
}

// 标签（查看 / 归属者编辑）

/** 资源当前标签（公开读，访客也能看） */
const assetTags = ref([])
/** 标签字典（下拉选项） */
const tagOptions = ref([])
/** 归属者的编辑草稿 */
const tagDraft = ref([])
const savingTags = ref(false)

const tagsDirty = computed(() => {
  const a = [...tagDraft.value].map((t) => t.trim()).filter(Boolean).sort()
  const b = [...assetTags.value].sort()
  return a.length !== b.length || a.some((t, i) => t !== b[i])
})

async function loadTags(id) {
  // 两个请求各自兜底：字典挂了只影响下拉选项，标签展示不受影响
  const [current, dictionary] = await Promise.all([
    listAssetTags(id).catch(() => []),
    listTags().catch(() => [])
  ])
  assetTags.value = current || []
  tagDraft.value = [...assetTags.value]
  tagOptions.value = dictionary || []
}

/**
 * 保存标签。后端清洗后整组替换，字典里没有的名字会自动注册，不需要先单独建标签。
 */
async function saveTags() {
  const tags = [...new Set(tagDraft.value.map((t) => t.trim()).filter(Boolean))]
  // 与后端 TagServiceImpl 的约束一致（进 ES keyword 字段，超长会被拒）
  if (tags.some((t) => t.length > 20)) {
    ElMessage.warning('单个标签最多 20 个字符')
    return
  }
  if (tags.length > 10) {
    ElMessage.warning('最多 10 个标签')
    return
  }
  savingTags.value = true
  try {
    const saved = await setAssetTags(props.assetId, tags)
    assetTags.value = saved || tags
    tagDraft.value = [...assetTags.value]
    // 新名字可能进了字典，刷新下拉选项
    tagOptions.value = await listTags().catch(() => tagOptions.value)
    ElMessage.success('标签已更新')
    // ES 的 tags 字段是异步同步的，列表/搜索页要跟着刷新
    emit('changed')
  } catch {
    // 失败提示由 utils/request.js 统一处理（非归属者 / 超长 / 超数等）
  } finally {
    savingTags.value = false
  }
}

async function loadIssues(id) {
  issuesLoading.value = true
  try {
    const data = await listIssuesByAsset(id, { page: 1, size: 50 })
    issues.value = data.records || []
  } catch {
    issues.value = []
  } finally {
    issuesLoading.value = false
  }
}

watch(
  () => [props.modelValue, props.assetId],
  ([visible, id]) => {
    if (visible && id) {
      load(id).then(() => {
        // 空草稿"继续上传"入口：直接把用户带到上传表单
        if (props.autoOpenUpload && isOwner.value) {
          tab.value = 'versions'
          uploadVisible.value = true
        }
      })
    }
  }
)

async function handleDownload() {
  if (!props.assetId) {
    return
  }
  downloading.value = true
  try {
    const blob = await downloadAsset(props.assetId)
    const name = detail.value?.name || 'asset'
    saveBlob(blob, `${name}-${detail.value?.latestVersion || 'package'}.zip`)
    ElMessage.success('下载已开始')
  } catch {
    // 失败时 utils/request.js 已提示（例如资源不可见、文件缺失）
  } finally {
    downloading.value = false
  }
}

// 版本级下载（zip / README）

/** 下载指定版本的 zip。老版本也能单独下载，不再只能下最新版 */
async function handleDownloadVersion(row) {
  downloadingVersion.value = row.version
  try {
    const blob = await downloadAssetVersion(props.assetId, row.version)
    saveBlob(blob, `${detail.value?.name || 'asset'}-${row.version}.zip`)
    ElMessage.success('下载已开始')
  } catch {
    // 失败提示由 utils/request.js 统一处理（资源不可见 / 文件缺失等）
  } finally {
    downloadingVersion.value = ''
  }
}

/** 下载该版本的 README（.md）。一个版本一份，所以老版本的 README 也在 */
async function handleDownloadReadme(row) {
  downloadingReadme.value = row.version
  try {
    const blob = await downloadAssetVersionReadme(props.assetId, row.version)
    saveBlob(blob, `${detail.value?.name || 'asset'}-${row.version}-README.md`)
    ElMessage.success('README 已开始下载')
  } catch {
    // 失败提示由 utils/request.js 统一处理（该版本没写 README 时后端会明确报错）
  } finally {
    downloadingReadme.value = ''
  }
}

// 修改 / 发布 / 删除

function openEdit() {
  if (!detail.value) {
    return
  }
  Object.assign(editForm, {
    name: detail.value.name || '',
    summary: detail.value.summary || '',
    category: detail.value.category || 'OTHER'
  })
  editVisible.value = true
}

async function submitEdit() {
  if (!editForm.name.trim()) {
    ElMessage.warning('名称不能为空')
    return
  }
  saving.value = true
  try {
    await updateAsset(props.assetId, {
      name: editForm.name.trim(),
      summary: editForm.summary.trim(),
      category: editForm.category
    })
    ElMessage.success('已保存')
    editVisible.value = false
    await load(props.assetId)
    emit('changed')
  } catch {
    // 失败提示由 utils/request.js 统一处理（无权限 / 参数非法等）
  } finally {
    saving.value = false
  }
}

async function handlePublish() {
  publishing.value = true
  try {
    await publishAsset(props.assetId)
    ElMessage.success('已发布')
    await load(props.assetId)
    emit('changed')
  } catch {
    // 失败提示由 utils/request.js 统一处理（如无版本不可发布）
  } finally {
    publishing.value = false
  }
}


// 上传新版本

function onFileChange(file) {
  uploadForm.file = file?.raw || null
}

async function submitVersion() {
  if (!uploadForm.file) {
    ElMessage.warning('请先选择文件')
    return
  }
  uploading.value = true
  try {
    const formData = new FormData()
    formData.append('file', uploadForm.file)
    if (uploadForm.version) {
      formData.append('version', uploadForm.version)
    }
    if (uploadForm.changelog) {
      formData.append('changelog', uploadForm.changelog)
    }
    if (uploadForm.readme) {
      formData.append('readme', uploadForm.readme)
    }
    await uploadAssetVersion(props.assetId, formData)
    // 资源已是 PUBLISHED 时新版本直接发布（不做先审后发），这里如实告知
    ElMessage.success(
      detail.value?.status === 'PUBLISHED' ? '新版本已上传并发布' : '新版本已上传'
    )
    await load(props.assetId)
    // 必须通知外层：资源的 latestVersion 变了，列表页那一行要跟着刷新，
    // 否则列表里还显示旧版本号，非要手动刷页面才更新
    emit('changed')
  } catch {
    // 失败提示由 utils/request.js 统一处理（版本号重复 / 扩展名 / 大小上限等）
  } finally {
    uploading.value = false
  }
}

// 删除版本（仅归属者）

const selectedVersions = ref([])
const deletingVersions = ref(false)

function onVersionSelectionChange(rows) {
  selectedVersions.value = rows
}

/**
 * 删除版本（单删传一个，批删传多个）。后端软删并重算 latest_version，
 * 返回值就是重算后的最新版本号（可能为 null，表示草稿已无版本）。
 */
async function handleDeleteVersions(rows) {
  const list = rows.map((r) => r.version)
  const confirmed = await ElMessageBox.confirm(
    `确定删除版本：${list.join('、')}？删除后不可恢复（对象存储文件会保留）`,
    '删除版本',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
  ).catch(() => null)
  if (!confirmed) {
    return
  }
  deletingVersions.value = true
  try {
    const latest =
      list.length === 1
        ? await deleteAssetVersion(props.assetId, list[0])
        : await batchDeleteAssetVersions(props.assetId, list)
    ElMessage.success(latest ? `已删除，最新版本：${latest}` : '已删除，资源暂无版本（可继续上传）')
    selectedVersions.value = []
    await load(props.assetId)
    emit('changed')
  } catch {
    // 失败提示由 utils/request.js 统一处理（如已发布资源删最后一个版本被拒）
  } finally {
    deletingVersions.value = false
  }
}

// 讨论

async function submitIssue() {
  if (!issueForm.title.trim() || !issueForm.content.trim()) {
    ElMessage.warning('标题和内容都要填')
    return
  }
  submittingIssue.value = true
  try {
    await createIssue({
      assetId: props.assetId,
      version: issueForm.version || detail.value?.latestVersion || null,
      title: issueForm.title.trim(),
      content: issueForm.content.trim()
    })
    ElMessage.success('已提交')
    issueFormVisible.value = false
    Object.assign(issueForm, { title: '', content: '', version: '' })
    await loadIssues(props.assetId)
  } catch {
    // 失败提示由 utils/request.js 统一处理（未登录 10001 等）
  } finally {
    submittingIssue.value = false
  }
}

async function onIssueExpand(issueId) {
  if (!commentsByIssue[issueId]) {
    await loadComments(issueId)
  }
}

async function loadComments(issueId) {
  try {
    const data = await listIssueComments(issueId, { page: 1, size: 100 })
    commentsByIssue[issueId] = data.records || []
  } catch {
    commentsByIssue[issueId] = []
  }
}

async function submitComment(issueId) {
  const content = (commentDraft[issueId] || '').trim()
  if (!content) {
    return
  }
  try {
    await createIssueComment(issueId, { content })
    commentDraft[issueId] = ''
    await loadComments(issueId)
  } catch {
    // 失败提示由 utils/request.js 统一处理（未登录等）
  }
}

/** 可用的状态流转与后端 IssueStatusMachine 保持一致 */
function nextStatuses(status) {
  if (status === 'OPEN') {
    return [
      { value: 'PROCESSING', label: '标记处理中' },
      { value: 'CLOSED', label: '关闭' }
    ]
  }
  if (status === 'PROCESSING') {
    return [{ value: 'CLOSED', label: '关闭' }]
  }
  return [{ value: 'OPEN', label: '重新打开' }]
}

const STATUS_TAG = { OPEN: 'warning', PROCESSING: 'primary', CLOSED: 'info' }

async function changeStatus(issueId, status) {
  try {
    await changeIssueStatus(issueId, status)
    ElMessage.success('状态已更新')
    await loadIssues(props.assetId)
  } catch {
    // 失败提示由 utils/request.js 统一处理（非法流转 / 无权限）
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="资源详情"
    width="780px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-tabs v-if="detail" v-model="tab">
      <el-tab-pane label="基本信息" name="basic">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="名称">{{ detail.name }}</el-descriptions-item>
          <el-descriptions-item label="简介">{{ detail.summary }}</el-descriptions-item>
          <el-descriptions-item label="分类">{{ detail.category }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="ASSET_STATUS_TAG[detail.status] || 'info'" size="small">
              {{ detail.status }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="最新版本">{{ detail.latestVersion }}</el-descriptions-item>
        </el-descriptions>

        <!-- 标签：访客只读，归属者可编辑（整组替换，新名字自动进字典） -->
        <p class="detail__label">标签</p>
        <div class="detail__tagBlock">
          <template v-if="isOwner">
            <el-select
              v-model="tagDraft"
              multiple
              filterable
              allow-create
              default-first-option
              :reserve-keyword="false"
              class="detail__tagSelect"
              placeholder="从字典选择，或直接输入新标签"
            >
              <el-option v-for="t in tagOptions" :key="t.id" :label="t.name" :value="t.name" />
            </el-select>
            <el-button
              type="primary"
              size="small"
              :loading="savingTags"
              :disabled="!tagsDirty"
              @click="saveTags"
            >
              保存标签
            </el-button>
            <span class="detail__hint">最多 10 个，单个 ≤20 字符；保存后约数秒同步到搜索</span>
          </template>
          <template v-else>
            <el-tag v-for="t in assetTags" :key="t" size="small" type="info" class="detail__tagItem">
              {{ t }}
            </el-tag>
            <span v-if="!assetTags.length" class="detail__hint">（无标签）</span>
          </template>
        </div>

        <!-- 归属者本人的操作入口：草稿要能发布，已发布/草稿都要能改与删 -->
        <div v-if="isOwner" class="detail__toolbar detail__ownerActions">
          <el-button
            v-if="detail.status === 'DRAFT'"
            type="primary"
            size="small"
            :loading="publishing"
            :disabled="!detail.latestVersion"
            @click="handlePublish"
          >
            发布
          </el-button>
          <el-button size="small" @click="openEdit">编辑</el-button>
          <span v-if="detail.status === 'DRAFT' && !detail.latestVersion" class="detail__hint">
            需要先上传文件生成版本才能发布
          </span>
        </div>

        <template v-if="detail.latestVersionDetail">
          <p class="detail__label">README</p>
          <!-- 用插值渲染即自动转义：README 按纯文本 / Markdown 存储，禁止存储并渲染 HTML -->
          <pre class="detail__readme">{{ detail.latestVersionDetail.readme || '（无）' }}</pre>
        </template>
      </el-tab-pane>

      <el-tab-pane :label="`版本（${versions.length}）`" name="versions">
        <div v-if="isOwner" class="detail__toolbar">
          <el-button type="primary" size="small" @click="uploadVisible = !uploadVisible">
            {{ uploadVisible ? '收起' : '上传新版本' }}
          </el-button>
          <el-button
            v-if="versions.length"
            type="danger"
            size="small"
            plain
            :disabled="!selectedVersions.length"
            :loading="deletingVersions"
            @click="handleDeleteVersions(selectedVersions)"
          >
            删除所选{{ selectedVersions.length ? `（${selectedVersions.length}）` : '' }}
          </el-button>
          <span class="detail__hint">已发布的资源，新版本会直接发布</span>
        </div>

        <el-form v-if="isOwner && uploadVisible" label-width="90px" class="detail__form">
          <el-form-item label="文件">
            <el-upload
              :auto-upload="false"
              :limit="1"
              accept=".zip"
              :on-change="onFileChange"
              :on-exceed="() => ElMessage.warning('一次只传一个文件')"
            >
              <el-button size="small">选择 zip 包</el-button>
            </el-upload>
          </el-form-item>
          <el-form-item label="版本号">
            <el-input v-model="uploadForm.version" placeholder="留空则自动递增补丁号（1.0.0 → 1.0.1）" />
          </el-form-item>
          <el-form-item label="变更说明">
            <el-input v-model="uploadForm.changelog" type="textarea" :rows="2" />
          </el-form-item>
          <el-form-item label="README">
            <el-input v-model="uploadForm.readme" type="textarea" :rows="3" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="uploading" @click="submitVersion">提交</el-button>
          </el-form-item>
        </el-form>

        <el-table :data="versions" size="small" @selection-change="onVersionSelectionChange">
          <el-table-column v-if="isOwner" type="selection" width="42" />
          <el-table-column prop="version" label="版本" width="100" />
          <el-table-column prop="publishStatus" label="状态" width="100" />
          <el-table-column prop="size" label="大小(字节)" width="110" />
          <el-table-column prop="changelog" label="变更说明" min-width="140" show-overflow-tooltip />
          <!-- 任意版本都能下：zip 与它自己那份 README.md -->
          <el-table-column label="操作" width="200">
            <template #default="{ row }">
              <el-button
                link
                type="primary"
                :loading="downloadingVersion === row.version"
                @click="handleDownloadVersion(row)"
              >
                下载
              </el-button>
              <el-button
                link
                type="primary"
                :disabled="!row.readme"
                :loading="downloadingReadme === row.version"
                @click="handleDownloadReadme(row)"
              >
                README.md
              </el-button>
              <el-button
                v-if="isOwner"
                link
                type="danger"
                :disabled="deletingVersions"
                @click="handleDeleteVersions([row])"
              >
                删除
              </el-button>
            </template>
          </el-table-column>
          <template #empty>
            暂无版本 —— {{ isOwner ? '在上方上传第一个文件（版本号留空自动生成 1.0.0）' : '作者还未上传文件' }}
          </template>
        </el-table>
      </el-tab-pane>

      <el-tab-pane :label="`依赖（${dependencies.length}）`" name="dependencies">
        <el-table :data="dependencies" size="small">
          <el-table-column prop="name" label="直接依赖" min-width="140" />
          <el-table-column prop="latestVersion" label="最新版本" width="110" />
          <el-table-column label="版本约束" width="120">
            <template #default="{ row }">
              <!-- 声明里没写版本约束 = 跟随被依赖资源的最新版本（aihub.yaml 的解析语义） -->
              <span v-if="row.versionConstraint">{{ row.versionConstraint }}</span>
              <span v-else class="detail__muted">最新版本</span>
            </template>
          </el-table-column>
          <template #empty>暂无依赖</template>
        </el-table>

        <p class="detail__label">
          依赖树（含传递依赖）
          <span v-if="detail?.latestVersion" class="detail__muted">
            —— 直接依赖来自最新版本 v{{ detail.latestVersion }} 的 aihub.yaml 声明
          </span>
        </p>
        <el-table :data="dependencyRows" size="small">
          <el-table-column label="依赖" min-width="180">
            <template #default="{ row }">
              <!-- 用缩进表达层级；cycle 为 true 表示数据里存在环，后端已标记且不再展开 -->
              <span :style="{ paddingLeft: `${row.depth * 18}px` }">
                {{ row.name || row.assetId }}
              </span>
              <el-tag v-if="row.cycle" type="danger" size="small" class="detail__cycle">存在环</el-tag>
            </template>
          </el-table-column>
          <template #empty>暂无依赖</template>
        </el-table>
      </el-tab-pane>

      <el-tab-pane :label="`讨论（${issues.length}）`" name="discussion">
        <div class="detail__toolbar">
          <el-button
            v-if="userStore.isLoggedIn"
            type="primary"
            size="small"
            @click="issueFormVisible = !issueFormVisible"
          >
            {{ issueFormVisible ? '收起' : '提 Issue' }}
          </el-button>
          <el-button v-else link type="primary" @click="$router.push({ name: 'login' })">
            登录后可提 Issue 与回复
          </el-button>
        </div>

        <el-form v-if="issueFormVisible" label-width="70px" class="detail__form">
          <el-form-item label="标题">
            <el-input v-model="issueForm.title" maxlength="200" show-word-limit />
          </el-form-item>
          <el-form-item label="版本">
            <el-input v-model="issueForm.version" :placeholder="detail.latestVersion || '可选'" />
          </el-form-item>
          <el-form-item label="内容">
            <el-input v-model="issueForm.content" type="textarea" :rows="3" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="submittingIssue" @click="submitIssue">提交</el-button>
          </el-form-item>
        </el-form>

        <el-empty
          v-if="!issuesLoading && !issues.length"
          description="还没有讨论，点「提 Issue」开一个"
        />

        <el-collapse v-model="expandedIssues" @change="(ids) => ids.forEach(onIssueExpand)">
          <el-collapse-item v-for="issue in issues" :key="issue.id" :name="issue.id">
            <template #title>
              <el-tag :type="STATUS_TAG[issue.status] || 'info'" size="small" class="detail__issueTag">
                {{ issue.status }}
              </el-tag>
              <span class="detail__issueTitle">{{ issue.title }}</span>
              <span class="detail__issueMeta">
                {{ authorLabel(issue.authorName, issue.authorId) }} · {{ formatDateTime(issue.createTime) }}
                <template v-if="issue.version"> · v{{ issue.version }}</template>
              </span>
            </template>

            <pre class="detail__issueBody">{{ issue.content }}</pre>

            <div class="detail__comments">
              <div v-for="c in commentsByIssue[issue.id] || []" :key="c.id" class="detail__comment">
                <span class="detail__commentMeta">
                  {{ authorLabel(c.authorName, c.authorId) }} · {{ formatDateTime(c.createTime) }}
                </span>
                <pre class="detail__commentBody">{{ c.content }}</pre>
              </div>
              <p v-if="!(commentsByIssue[issue.id] || []).length" class="detail__hint">还没有回复</p>
            </div>

            <div v-if="userStore.isLoggedIn" class="detail__commentForm">
              <el-input
                v-model="commentDraft[issue.id]"
                type="textarea"
                :rows="2"
                placeholder="写下你的回复…"
              />
              <el-button
                type="primary"
                size="small"
                :disabled="!(commentDraft[issue.id] || '').trim()"
                @click="submitComment(issue.id)"
              >
                回复
              </el-button>
            </div>

            <div v-if="userStore.isLoggedIn" class="detail__issueActions">
              <el-button
                v-for="opt in nextStatuses(issue.status)"
                :key="opt.value"
                link
                type="primary"
                size="small"
                @click="changeStatus(issue.id, opt.value)"
              >
                {{ opt.label }}
              </el-button>
            </div>
          </el-collapse-item>
        </el-collapse>
      </el-tab-pane>
    </el-tabs>

    <el-skeleton v-else :rows="4" animated />

    <template #footer>
      <el-button
        type="primary"
        :loading="downloading"
        :disabled="!detail || !detail.latestVersion"
        @click="handleDownload"
      >
        下载最新版本
      </el-button>
      <el-button @click="emit('update:modelValue', false)">关闭</el-button>
    </template>
  </el-dialog>

  <!-- 编辑基础信息：独立的小弹窗，避免和详情主弹窗的状态纠缠 -->
  <el-dialog v-model="editVisible" title="编辑资源" width="460px" append-to-body>
    <el-form label-width="70px">
      <el-form-item label="名称">
        <el-input v-model="editForm.name" maxlength="100" show-word-limit />
      </el-form-item>
      <el-form-item label="简介">
        <el-input v-model="editForm.summary" type="textarea" :rows="3" maxlength="500" show-word-limit />
      </el-form-item>
      <el-form-item label="分类">
        <el-select v-model="editForm.category">
          <el-option v-for="c in CATEGORY_OPTIONS" :key="c" :label="c" :value="c" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="editVisible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submitEdit">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.detail__muted {
  color: #86909c;
  font-weight: 400;
}
.detail__label {
  margin: 16px 0 8px;
  color: #4e5969;
}

.detail__readme {
  max-height: 240px;
  overflow: auto;
  padding: 12px;
  background-color: #f7f8fa;
  border-radius: 4px;
  white-space: pre-wrap;
  word-break: break-word;
}

.detail__cycle {
  margin-left: 8px;
}

.detail__ownerActions {
  margin-top: 12px;
}

.detail__tagBlock {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.detail__tagSelect {
  width: 320px;
}

.detail__tagItem {
  margin-right: 4px;
}

.detail__toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.detail__hint {
  color: #86909c;
  font-size: 12px;
}

.detail__form {
  padding: 12px;
  margin-bottom: 12px;
  background-color: #f7f8fa;
  border-radius: 4px;
}

.detail__issueTag {
  margin-right: 8px;
}

.detail__issueTitle {
  font-weight: 600;
}

.detail__issueMeta {
  margin-left: auto;
  padding-right: 12px;
  color: #86909c;
  font-size: 12px;
}

.detail__issueBody {
  margin: 0 0 12px;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
}

.detail__comments {
  padding-left: 12px;
  border-left: 2px solid #f0f1f3;
}

.detail__comment + .detail__comment {
  margin-top: 10px;
}

.detail__commentMeta {
  color: #86909c;
  font-size: 12px;
}

.detail__commentBody {
  margin: 2px 0 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
}

.detail__commentForm {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-top: 12px;
}

.detail__issueActions {
  margin-top: 8px;
}
</style>
