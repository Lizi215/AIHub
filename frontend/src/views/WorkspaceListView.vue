<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import JSZip from 'jszip'
import { deleteWorkspace, getWorkspace, listWorkspaces } from '@/api/workspace'
import { downloadAssetVersion, downloadAssetVersionReadme } from '@/api/asset'
import { saveBlob } from '@/utils/download'
import { dateTimeFormatter } from '@/utils/format'

const records = ref([])
const total = ref(0)
const loading = ref(false)
const query = ref({ page: 1, size: 10 })
const detailVisible = ref(false)
const detail = ref(null)
/** 按行记录打包下载进行中状态（行 id -> bool） */
const bundling = ref({})

async function load() {
  loading.value = true
  try {
    const data = await listWorkspaces(query.value)
    records.value = data.records
    total.value = data.total
  } finally {
    loading.value = false
  }
}

async function openDetail(row) {
  detail.value = await getWorkspace(row.id)
  detailVisible.value = true
}

async function remove(row) {
  await ElMessageBox.confirm(`确认删除「${row.name}」？`, '删除 Workspace', { type: 'warning' })
  await deleteWorkspace(row.id)
  ElMessage.success('已删除')
  await load()
}

/**
 * 整包下载：把每个可用条目的 zip 与 README.md 打进一个压缩包。
 * 打包放在浏览器端（JSZip）：前端本来就要逐个下载，本地组装即可，服务端聚合反而要全量中转字节。
 * 不可用条目跳过并计数。
 */
async function downloadBundle(row) {
  bundling.value[row.id] = true
  try {
    const ws = await getWorkspace(row.id)
    const items = (ws.items || []).filter((it) => it.available && it.name)
    if (!items.length) {
      ElMessage.warning('没有可用条目可下载')
      return
    }
    const zip = new JSZip()
    let readmeCount = 0
    for (const it of items) {
      const base = `${it.name}-${it.version}`
      zip.file(`${base}/package.zip`, await downloadAssetVersion(it.assetId, it.version))
      try {
        zip.file(`${base}/README.md`, await downloadAssetVersionReadme(it.assetId, it.version))
        readmeCount++
      } catch {
        // 该版本没写 README（50004）→ 跳过这个文件，不中断整包
      }
    }
    const blob = await zip.generateAsync({ type: 'blob' })
    saveBlob(blob, `${ws.name}.zip`)
    ElMessage.success(`已打包 ${items.length} 个资源、${readmeCount} 份 README`)
  } catch {
    // 失败提示由 utils/request.js 统一处理
  } finally {
    bundling.value[row.id] = false
  }
}

onMounted(load)
</script>

<template>
  <el-card shadow="never">
    <template #header>我的 Workspace</template>
    <el-table v-loading="loading" :data="records" @row-dblclick="openDetail">
      <el-table-column prop="name" label="名称" min-width="160" />
      <!-- 简介来自 Agent 草案；旧数据为空显示占位符 -->
      <el-table-column label="简介" min-width="240" show-overflow-tooltip>
        <template #default="{ row }">
          <span :class="row.description ? '' : 'wsEmpty'">{{ row.description || '（无简介）' }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="创建时间" width="170" :formatter="dateTimeFormatter" />
      <el-table-column label="操作" width="220">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">查看</el-button>
          <el-button link type="primary" :loading="bundling[row.id]" @click="downloadBundle(row)">
            打包下载
          </el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>

  <el-dialog v-model="detailVisible" :title="detail?.name" width="640px">
    <p v-if="detail?.description" class="wsDesc">{{ detail.description }}</p>
    <el-table :data="detail?.items ?? []" size="small">
      <el-table-column label="资源" min-width="200">
        <template #default="{ row }">
          {{ row.name ?? `资源 ${row.assetId}（已不可用）` }}
        </template>
      </el-table-column>
      <el-table-column prop="version" label="版本" width="120" />
      <el-table-column label="可用" width="80">
        <template #default="{ row }">
          <el-tag :type="row.available ? 'success' : 'danger'" size="small">
            {{ row.available ? '可用' : '不可用' }}
          </el-tag>
        </template>
      </el-table-column>
    </el-table>
  </el-dialog>
</template>

<style scoped>
.wsEmpty {
  color: #c0c4cc;
}
.wsDesc {
  margin: 0 0 12px;
  padding: 8px 12px;
  background: #f7f8fa;
  border-radius: 6px;
  color: #4e5969;
  font-size: 13px;
  line-height: 1.6;
}
</style>
