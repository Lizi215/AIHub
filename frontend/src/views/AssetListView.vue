<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteAsset, downloadAsset, listAssets, publishAsset } from '@/api/asset'
import { useUserStore } from '@/stores/user'
import { dateTimeFormatter } from '@/utils/format'
import { saveBlob } from '@/utils/download'
import AssetDetailDialog from '@/components/AssetDetailDialog.vue'

const router = useRouter()
const userStore = useUserStore()

const loading = ref(false)
const records = ref([])
const total = ref(0)
const query = reactive({ page: 1, size: 10, category: '', onlyMine: false })
const detailVisible = ref(false)
const detailAssetId = ref(null)
/** 打开详情时是否直接展开上传区（空草稿"继续上传"用） */
const detailAutoUpload = ref(false)

const STATUS_TAG = { DRAFT: 'info', PUBLISHED: 'success', SUSPENDED: 'danger' }

async function load() {
  loading.value = true
  try {
    const data = await listAssets({
      page: query.page,
      size: query.size,
      category: query.category || undefined,
      // 「只看我的」需要登录；未登录时开关不展示，这里也不会发出 true
      onlyMine: query.onlyMine || undefined
    })
    records.value = data.records
    // total 是数字：后端对分页计数豁免了 Long→String 规则；
    // 但 records 里的 id / ownerId 仍是字符串，不能 parseInt / Number。
    total.value = data.total
  } catch {
    records.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function handlePageChange(page) {
  query.page = page
  load()
}



function openDetail(row) {
  // 取数逻辑在 AssetDetailDialog 内部（与搜索页共用同一份）
  detailAssetId.value = row.id
  detailAutoUpload.value = false
  detailVisible.value = true
}

/** 空草稿续传：打开详情弹窗并直接定位到上传区 */
function continueUpload(row) {
  detailAssetId.value = row.id
  detailAutoUpload.value = true
  detailVisible.value = true
}

/** 只有归属者本人能发布/编辑/删除（后端 requireOwned 兜底，这里只是别把按钮给错人） */
function isOwnerRow(row) {
  return Boolean(row.ownerId) && row.ownerId === userStore.userId
}

// 「只看我的」批量编辑模式
const editing = ref(false)
const selectedRows = ref([])
const batchDeleting = ref(false)

/** 编辑模式只属于"只看我的"：关掉开关就退出编辑 */
function handleMineChange() {
  query.page = 1
  editing.value = false
  selectedRows.value = []
  load()
}

function toggleEdit() {
  editing.value = !editing.value
  selectedRows.value = []
}

function onSelectionChange(rows) {
  selectedRows.value = rows
}

/** 批量删除选中资源：逐个调删除接口，失败不中断（逐条计数如实回报） */
async function handleBatchDelete() {
  const ids = selectedRows.value.map((r) => r.id)
  if (!ids.length) {
    return
  }
  try {
    await ElMessageBox.confirm(
      `将删除选中的 ${ids.length} 个资源（含全部版本），不可恢复。确认删除？`,
      '批量删除',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return // 用户取消
  }
  batchDeleting.value = true
  let ok = 0
  let failed = 0
  try {
    for (const id of ids) {
      try {
        await deleteAsset(id)
        ok++
      } catch {
        failed++ // 失败提示由 utils/request.js 统一处理，继续删后面的
      }
    }
    if (ok) {
      ElMessage.success(`已删除 ${ok} 个资源` + (failed ? `；失败 ${failed} 个` : ''))
    }
    selectedRows.value = []
    await load() // 停在编辑模式，方便继续删
  } finally {
    batchDeleting.value = false
  }
}

async function handlePublish(row) {
  try {
    await publishAsset(row.id)
    ElMessage.success('已发布')
    load()
  } catch {
    // 失败提示由 utils/request.js 统一处理（如无版本不可发布）
  }
}

async function handleDownload(row) {
  try {
    const blob = await downloadAsset(row.id)
    saveBlob(blob, `${row.name}-${row.latestVersion || 'package'}.zip`)
    ElMessage.success('下载已开始')
  } catch {
    // 失败时 utils/request.js 已提示（例如资源不可见、文件缺失）
  }
}

onMounted(load)
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <div class="header">
        <span>资源列表</span>
        <div class="headerActions">
          <template v-if="query.onlyMine && userStore.isLoggedIn">
            <el-button
              v-if="editing"
              type="danger"
              plain
              :disabled="!selectedRows.length"
              :loading="batchDeleting"
              @click="handleBatchDelete"
            >
              删除{{ selectedRows.length ? `（${selectedRows.length}）` : '' }}
            </el-button>
            <el-button :type="editing ? 'primary' : 'default'" @click="toggleEdit">
              {{ editing ? '完成' : '编辑' }}
            </el-button>
          </template>
          <el-button type="primary" @click="router.push({ name: 'asset-create' })">新建资源</el-button>
        </div>
      </div>
    </template>

    <el-alert
      v-if="!userStore.isLoggedIn"
      class="hint"
      type="info"
      :closable="false"
      title="当前为匿名访问，只能看到已发布资源"
      description="登录后可以看到自己名下的草稿。"
    />

    <!-- 「我的资源」在资源列表里而非 Workspace：Workspace 是资源**组合**，语义不同 -->
    <div v-if="userStore.isLoggedIn" class="filters">
      <el-checkbox v-model="query.onlyMine" @change="handleMineChange">只看我的</el-checkbox>
    </div>

    <el-table v-loading="loading" :data="records" class="table" @selection-change="onSelectionChange">
      <el-table-column prop="name" label="名称" min-width="160" />
      <el-table-column label="分类" width="100">
        <template #default="{ row }">
          <el-tag size="small" type="info">{{ row.category }}</el-tag>
        </template>
      </el-table-column>
      <!-- 标签列宽有限：只展示前 2 个，其余折叠进悬停提示 -->
      <el-table-column label="作者" width="120">
        <template #default="{ row }">
          <span class="ownerName">{{ row.ownerName || (row.ownerId ? `用户#${String(row.ownerId).slice(-6)}` : '匿名') }}</span>
        </template>
      </el-table-column>
      <el-table-column label="标签" min-width="110">
        <template #default="{ row }">
          <template v-if="row.tags?.length">
            <el-tag v-for="t in row.tags.slice(0, 2)" :key="t" size="small" class="tagChip">{{ t }}</el-tag>
            <el-tooltip
              v-if="row.tags.length > 2"
              :content="row.tags.join('、')"
              placement="top"
            >
              <span class="tagMore">+{{ row.tags.length - 2 }}</span>
            </el-tooltip>
          </template>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="STATUS_TAG[row.status] || 'info'">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="latestVersion" label="最新版本" width="120" />
      <el-table-column prop="updateTime" label="更新时间" width="180" :formatter="dateTimeFormatter" />
      <el-table-column label="操作" width="280">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          <el-button link type="primary" :disabled="!row.latestVersion" @click="handleDownload(row)">
            下载
          </el-button>
          <!-- 草稿的发布入口做在列表上，省得每次都要点进详情 -->
          <el-button
            v-if="isOwnerRow(row) && row.status === 'DRAFT' && row.latestVersion"
            link
            type="success"
            @click="handlePublish(row)"
          >
            发布
          </el-button>
          <!-- 空草稿（还没传过文件）：唯一有意义的动作就是继续上传 -->
          <el-button
            v-if="isOwnerRow(row) && row.status === 'DRAFT' && !row.latestVersion"
            link
            type="warning"
            @click="continueUpload(row)"
          >
            继续上传
          </el-button>
        </template>
      </el-table-column>
      <!-- 选择列**常驻**（占位但非编辑模式不可勾选）：彻底避免进/出编辑时的列宽抖动。
           selectable 只在编辑模式放行，表头全选同样被拦住 -->
      <el-table-column
        type="selection"
        width="42"
        :selectable="() => editing"
        :class-name="editing ? 'selCol' : 'selCol selCol--idle'"
        :label-class-name="editing ? 'selCol' : 'selCol selCol--idle'"
      />
      <template #empty>暂无资源</template>
    </el-table>

    <el-pagination
      v-if="total > 0"
      class="pager"
      layout="prev, pager, next, total"
      :total="total"
      :page-size="query.size"
      :current-page="query.page"
      @current-change="handlePageChange"
    />
  </el-card>

  <!-- 详情弹窗与搜索页共用同一个组件（取数逻辑只写一份） -->
  <AssetDetailDialog
    v-model="detailVisible"
    :asset-id="detailAssetId"
    :auto-open-upload="detailAutoUpload"
    @changed="load"
  />
</template>

<style scoped>
.headerActions {
  display: flex;
  gap: 8px;
}
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.hint {
  margin-bottom: 16px;
}

.filters {
  margin-bottom: 12px;
}

.table {
  width: 100%;
}

.pager {
  margin-top: 16px;
  justify-content: flex-end;
}
.ownerName {
  color: #4e5969;
}
.tagChip {
  margin-right: 4px;
}
.tagMore {
  font-size: 12px;
  color: #86909c;
}
</style>

<style>
/* 非 scoped：selCol 是 el-table 内部渲染的 class-name，scoped 选择器匹配不到。
   非编辑模式隐藏复选框本体，列宽保留（零抖动） */
.selCol--idle .el-checkbox {
  display: none;
}
/* 编辑模式的复选框再压小一号（12px），勾选行与普通行高度一致 */
.selCol .el-checkbox {
  height: 20px;
}
.selCol .el-checkbox__inner {
  width: 12px;
  height: 12px;
}
/* 对勾按 12px 盒子重新定位：默认偏移是按 14px 算的，直接继承会偏右下 */
.selCol .el-checkbox__inner::after {
  height: 6px;
  width: 2px;
  left: 4px;
  top: 1px;
}
</style>
