<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { createAsset, getAssetDetail, lookupOwnAssetByName, publishAsset, uploadAssetVersion } from '@/api/asset'

const router = useRouter()

/** 未上传文件的草稿 assetId 存这里，离开向导后回来可续传 */
const DRAFT_KEY = 'aihub_draft_asset_id'

/** 与后端 asset.category 对应，目前为固定分类 */
const CATEGORIES = ['SKILL', 'HOOK', 'PROMPT', 'MCP', 'RULES', 'TEMPLATE', 'OTHER']

const step = ref(0)
const submitting = ref(false)

const assetId = ref('')
/** 追加目标：自己已有的同名资源（存在时不再新建资源，直接给它加版本） */
const existingAsset = ref(null)
const form = ref({ name: '', summary: '', category: 'SKILL' })

const file = ref(null)
const version = ref('1.0.0')
const changelog = ref('')
const readme = ref('')

/** 保存草稿进度：阶段①完成但还没上传文件时，离开向导也能在下次进入时续传 */
function saveDraftProgress() {
  try {
    sessionStorage.setItem(DRAFT_KEY, String(assetId.value))
  } catch {
    // 存不下只影响续传提示，不影响流程
  }
}

function clearDraftProgress() {
  try {
    sessionStorage.removeItem(DRAFT_KEY)
  } catch {
    // 忽略
  }
}

/** 进入页面时检测未完成的草稿，询问是否继续上传 */
onMounted(async () => {
    let savedId
    try {
      savedId = sessionStorage.getItem(DRAFT_KEY)
    } catch {
      // 存不下就没有续传提示
      return
    }
  if (!savedId) {
    return
  }
  try {
    const detail = await getAssetDetail(savedId)
    // 只有"还是自己的无版本草稿"才值得续传；已发布/已删除的不再提示
    if (detail.data?.status !== 'DRAFT' || detail.data?.latestVersion) {
      clearDraftProgress()
      return
    }
    const action = await ElMessageBox.confirm(
      '检测到有未完成的草稿（还没上传文件）。是否继续上传？',
      '继续创建资源',
      { confirmButtonText: '继续上传', cancelButtonText: '丢弃草稿', type: 'info' }
    ).catch(() => null)
    clearDraftProgress()
    if (!action) {
      return
    }
    assetId.value = savedId
    step.value = 1
  } catch {
    // 用户点"丢弃草稿"（cancel）或详情拉取失败（草稿可能已删）都清掉标记
    clearDraftProgress()
  }
})

async function handleCreateDraft() {
  if (!form.value.name) {
    ElMessage.warning('请填写资源名称')
    return
  }
  submitting.value = true
  try {
    // 先看自己是否已有同名资源：有就作为新版本追加到它，而不是又建一个重复资源
    const existing = await lookupOwnAssetByName(form.value.name.trim())
    if (existing) {
      existingAsset.value = existing
      assetId.value = existing.assetId
      step.value = 1
      ElMessage.info(
        `你已有同名资源（最新 v${existing.latestVersion || '—'}）：本次上传将作为**新版本**追加到它`
      )
      return
    }
    existingAsset.value = null
    assetId.value = await createAsset(form.value)
    step.value = 1
    saveDraftProgress()
    ElMessage.success('草稿已创建')
  } catch {
    // 统一错误提示
  } finally {
    submitting.value = false
  }
}

function onFileChange(uploadFile) {
  file.value = uploadFile.raw
}

function onFileRemove() {
  file.value = null
}

async function handleUpload() {
  if (!file.value) {
    ElMessage.warning('请选择要上传的 zip 包')
    return
  }
  // 追加到同名资源时，同版本号不允许覆盖（后端硬性拒绝）：提前给出可操作的提示
  if (existingAsset.value && version.value
      && (existingAsset.value.versions || []).includes(version.value.trim())) {
    ElMessage.warning(
      `版本 ${version.value.trim()} 已存在于该资源；系统不允许覆盖已发布的版本，`
      + '请改用其它版本号（留空则自动递增补丁号），或先到资源详情里删除该版本'
    )
    return
  }
  submitting.value = true
  try {
    const formData = new FormData()
    formData.append('file', file.value)
    if (version.value) formData.append('version', version.value)
    if (changelog.value) formData.append('changelog', changelog.value)
    if (readme.value) formData.append('readme', readme.value)

    // 不手工设置 Content-Type，交给浏览器生成 multipart boundary
    const uploaded = await uploadAssetVersion(assetId.value, formData)
    // 草稿已有版本，不再是"未完成的空草稿"，续传标记可以清了
    clearDraftProgress()
    if (existingAsset.value && existingAsset.value.status === 'PUBLISHED') {
      // 已发布资源追加新版本时后端会自动发布，不需要再走"发布"这一步
      step.value = 3
      ElMessage.success(`已作为 v${uploaded.version} 追加到同名资源并自动发布`)
    } else {
      step.value = 2
      ElMessage.success('文件已上传，版本已生成')
    }
    // 后端会解析包内 aihub.yaml 的 requires，并如实回报未解析项（不阻断上传）
    for (const note of uploaded?.dependencyNotes || []) {
      ElMessage.info(note)
    }
  } catch {
    // 统一错误提示
  } finally {
    submitting.value = false
  }
}

async function handlePublish() {
  submitting.value = true
  try {
    await publishAsset(assetId.value)
    ElMessage.success('发布成功')
    clearDraftProgress()
    router.push({ name: 'assets' })
  } catch {
    // 统一错误提示
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-card shadow="never">
    <template #header>新建资源（三阶段上传）</template>

    <el-steps :active="step" finish-status="success" class="steps">
      <el-step title="① 创建草稿" description="写入 asset，状态 DRAFT" />
      <el-step title="② 上传文件" description="经 File Service 落对象存储" />
      <el-step title="③ 发布" description="状态变为 PUBLISHED，公开可见" />
    </el-steps>

    <el-form v-if="step === 0" label-width="90px" class="form">
      <el-form-item label="名称">
        <el-input v-model="form.name" maxlength="200" show-word-limit />
      </el-form-item>
      <el-form-item label="简介">
        <el-input v-model="form.summary" maxlength="500" show-word-limit />
      </el-form-item>
      <el-form-item label="分类">
        <el-select v-model="form.category">
          <el-option v-for="item in CATEGORIES" :key="item" :label="item" :value="item" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="submitting" @click="handleCreateDraft">创建草稿</el-button>
      </el-form-item>
    </el-form>

    <el-form v-else-if="step === 1" label-width="90px" class="form">
      <el-form-item label="资源 ID">
        <code>{{ assetId }}</code>
      </el-form-item>
      <el-alert
        v-if="existingAsset"
        type="info"
        :closable="false"
        show-icon
        title="你将给「已有的同名资源」追加新版本"
        :description="`当前最新 v${existingAsset.latestVersion || '—'}；版本号留空会自动递增补丁号。同版本号不允许覆盖。`"
      />
      <el-form-item label="文件">
        <el-upload
          :auto-upload="false"
          :limit="1"
          accept=".zip"
          :on-change="onFileChange"
          :on-remove="onFileRemove"
        >
          <el-button>选择 zip 包</el-button>
        </el-upload>
        <p class="uploadHint">
          包根目录放 <code>aihub.yaml</code> 声明依赖（<code>requires: [save-last]</code>），
          上传后会自动解析并关联到已有资源（解析不到不会阻断上传，会逐条提示）
        </p>
      </el-form-item>
      <el-form-item label="版本号">
        <el-input v-model="version" placeholder="默认 1.0.0" />
      </el-form-item>
      <el-form-item label="更新日志">
        <el-input v-model="changelog" type="textarea" :rows="2" />
      </el-form-item>
      <el-form-item label="README">
        <el-input v-model="readme" type="textarea" :rows="4" placeholder="按纯文本存储，不渲染 HTML" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="submitting" @click="handleUpload">上传文件</el-button>
      </el-form-item>
    </el-form>

    <div v-else class="done">
      <!-- 追加到已发布资源：新版本后端已自动发布，这里不再给"发布"按钮（重复发布会被拒绝） -->
      <el-result
        v-if="existingAsset && existingAsset.status === 'PUBLISHED'"
        icon="success"
        title="已追加新版本并发布"
        :sub-title="`同名资源「${form.name}」的 v${existingAsset.latestVersion || '—'} 已更新，访问者立即可见`"
      >
        <template #extra>
          <el-button type="primary" @click="router.push({ name: 'assets' })">返回资源列表</el-button>
        </template>
      </el-result>
      <el-result v-else icon="success" title="文件已上传" sub-title="确认无误后发布，发布后资源对所有访问者可见">
        <template #extra>
          <el-button type="primary" :loading="submitting" @click="handlePublish">发布资源</el-button>
          <el-button @click="router.push({ name: 'assets' })">稍后再发布</el-button>
          <p class="laterHint">稍后发布也没关系：草稿不会丢，可在「资源列表 → 我的资源」里继续上传新版本或发布</p>
        </template>
      </el-result>
    </div>
  </el-card>
</template>

<style scoped>
.steps {
  margin-bottom: 28px;
}

.form {
  max-width: 560px;
}

.done {
  padding: 8px 0;
}

.laterHint {
  margin: 12px 0 0;
  font-size: 12px;
  color: #86909c;
}
</style>
