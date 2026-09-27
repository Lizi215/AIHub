<script setup>
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { searchAssets } from '@/api/search'
import { downloadAsset } from '@/api/asset'
import { saveBlob } from '@/utils/download'
import AssetDetailDialog from '@/components/AssetDetailDialog.vue'

const CATEGORIES = ['', 'SKILL', 'HOOK', 'PROMPT', 'MCP', 'RULES', 'TEMPLATE', 'OTHER']
const SORTS = [
  { value: 'relevance', label: '相关度' },
  { value: 'latest', label: '最新' },
  { value: 'hottest', label: '最热' }
]

const query = reactive({ keyword: '', category: '', tag: '', sort: 'relevance', ownerId: null })
/** 当前作者筛选的展示名（点卡片作者名设置） */
const authorFilter = ref('')
const loading = ref(false)
const records = ref([])
const total = ref(0)
const searched = ref(false)
const cursor = ref(null)
const detailVisible = ref(false)
const detailAssetId = ref(null)

/**
 * 高亮片段来自 ES（<em> 包裹命中词），而正文是用户上传的纯文本——
 * 直接 v-html 会把正文里的 <script> 原样注入（XSS）。
 * 这里先整体转义 HTML，再把转义后的 em 标记还原，保证只有我们自己的标记是活标签。
 */
function safeHighlight(text) {
  const escaped = String(text ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
  return escaped
    .replace(/&lt;em&gt;/g, '<em class="hl">')
    .replace(/&lt;\/em&gt;/g, '</em>')
    .replace(/\n/g, '<br/>')
}

function snippetOf(hit) {
  const hl = hit.highlights || {}
  const field = ['title', 'description', 'readme', 'content'].find((f) => hl[f]?.length)
  return field ? hl[field][0] : String(hit.source?.summary ?? hit.source?.content ?? '')
}

async function search(reset = true) {
  loading.value = true
  try {
    const params = {
      keyword: query.keyword || undefined,
      category: query.category || undefined,
      tag: query.tag || undefined,
      sort: query.sort,
      ownerId: query.ownerId || undefined
    }
    if (reset) {
      params.size = 10
    } else {
      // 深分页：带上上一页的游标继续往后取
      params.size = 10
      params.searchAfter = cursor.value
    }
    const data = await searchAssets(params)
    total.value = data.total
    cursor.value = data.nextSearchAfter ?? null
    records.value = reset ? data.records : records.value.concat(data.records)
    searched.value = true
  } finally {
    loading.value = false
  }
}

function onSearch() {
  search(true)
}

/** 点卡片作者名 → 只看该作者（authorId 精确过滤；再点一次取消） */
function filterByAuthor(hit) {
  const id = hit.source?.authorId
  if (!id) {
    return
  }
  if (String(query.ownerId) === String(id)) {
    clearAuthorFilter()
    return
  }
  query.ownerId = id
  authorFilter.value = authorNameOf(hit)
  search(true)
}

function clearAuthorFilter() {
  query.ownerId = null
  authorFilter.value = ''
  search(true)
}

function authorNameOf(hit) {
  const name = hit.source?.authorName
  if (name) {
    return name
  }
  const id = hit.source?.authorId
  return id ? `用户#${String(id).slice(-6)}` : '匿名'
}

/** 详情弹窗里改了资源（含标签）：结果要刷新 */
function onDetailChanged() {
  search(true)
}

function loadMore() {
  if (cursor.value) {
    search(false)
  }
}

function openDetail(hit) {
  // 搜索返回的是 ES 文档（没有版本/依赖/README 全文），详情由组件回查业务接口
  detailAssetId.value = hit.id
  detailVisible.value = true
}

async function handleDownload(hit) {
  try {
    const blob = await downloadAsset(hit.id)
    const name = hit.source?.title || hit.source?.name || 'asset'
    saveBlob(blob, `${name}.zip`)
    ElMessage.success('下载已开始')
  } catch {
    // 失败时 utils/request.js 已提示（例如未登录、资源不可见）
  }
}
</script>

<template>
  <div class="search">
    <el-form inline @submit.prevent="onSearch">
      <el-form-item>
        <el-input
          v-model="query.keyword"
          placeholder="搜索资源…"
          clearable
          style="width: 320px"
          @keyup.enter="onSearch"
        />
      </el-form-item>
      <el-form-item>
        <el-select v-model="query.category" placeholder="分类" clearable style="width: 140px">
          <el-option v-for="c in CATEGORIES.filter(Boolean)" :key="c" :label="c" :value="c" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <!-- 手输标签，不拉全量字典：标签会越攒越多，下拉既占资源又难找 -->
        <el-input
          v-model="query.tag"
          placeholder="标签"
          clearable
          style="width: 160px"
          @keyup.enter="onSearch"
        />
      </el-form-item>
      <el-form-item>
        <el-select v-model="query.sort" style="width: 120px">
          <el-option v-for="s in SORTS" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="loading" @click="onSearch">搜索</el-button>
      </el-form-item>
    </el-form>

    <el-empty v-if="searched && !records.length && !loading" description="没有匹配的资源" />

    <!-- 命中数：搜索最有用的反馈，告诉用户"还有多少没翻" -->
    <p v-if="searched && records.length" class="search__count">
      共 <b>{{ total }}</b> 条匹配结果
      <el-tag v-if="authorFilter" closable size="small" class="search__authorFilter" @close="clearAuthorFilter">
        只看作者：{{ authorFilter }}
      </el-tag>
    </p>

    <!-- 双列卡片网格：每张卡片独立成块，右侧不再大片留白 -->
    <div v-if="records.length" class="search__grid">
      <!-- 整卡可点：搜到之后最常见的动作就是「看一眼再下载」，卡片本身就是入口 -->
      <el-card
        v-for="hit in records"
        :key="hit.id"
        class="search__hit"
        shadow="hover"
        @click="openDetail(hit)"
      >
        <div class="search__title">
          <span class="search__name">{{ hit.source?.title ?? hit.source?.name }}</span>
          <el-tag v-if="hit.source?.category" size="small" type="info">{{ hit.source.category }}</el-tag>
        </div>
        <!-- eslint-disable-next-line vue/no-v-html -- safeHighlight 已转义 HTML，只还原自有的 em 标记 -->
        <div class="search__snippet" v-html="safeHighlight(snippetOf(hit))" />
        <div v-if="hit.source?.tags?.length" class="search__tags">
          <!-- 卡片空间有限：展示前 4 个，其余折叠进悬停提示 -->
          <el-tag v-for="t in hit.source.tags.slice(0, 4)" :key="t" size="small" effect="plain">{{ t }}</el-tag>
          <el-tooltip
            v-if="hit.source.tags.length > 4"
            :content="hit.source.tags.join('、')"
            placement="top"
          >
            <span class="search__tagMore">+{{ hit.source.tags.length - 4 }}</span>
          </el-tooltip>
        </div>
        <div class="search__meta">
          <span class="search__author" title="点击只看该作者" @click.stop="filterByAuthor(hit)">
            {{ authorNameOf(hit) }}
          </span>
          <span>相关度 {{ hit.score?.toFixed(3) }}</span>
          <span v-if="hit.source?.downloadCount">下载 {{ hit.source.downloadCount }}</span>
        </div>
        <!-- 按钮阻止冒泡：点按钮只做那件事，不要顺带打开弹窗 -->
        <div class="search__actions" @click.stop>
          <el-button link type="primary" @click="openDetail(hit)">查看详情</el-button>
          <el-button link type="primary" @click="handleDownload(hit)">下载</el-button>
        </div>
      </el-card>
    </div>

    <div v-if="cursor" class="search__more">
      <el-button :loading="loading" @click="loadMore">加载更多</el-button>
    </div>

    <!-- 与资源列表共用同一个详情弹窗 -->
    <AssetDetailDialog v-model="detailVisible" :asset-id="detailAssetId" @changed="onDetailChanged" />
  </div>
</template>

<style scoped>
/* 双列网格：窄屏自动降为单列 */
.search__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(420px, 1fr));
  gap: 12px;
}
.search__count {
  margin: 4px 0 12px;
  font-size: 13px;
  color: #4e5969;
}
.search__count b {
  color: #409eff;
}
.search__hit {
  /* 整卡可点，给出可交互的视觉提示 */
  cursor: pointer;
}
.search__actions {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid #f0f1f3;
}
.search__title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
  flex-wrap: wrap;
}
.search__name {
  font-size: 15px;
  font-weight: 600;
}
.search__snippet {
  color: #4e5969;
  font-size: 13px;
  line-height: 1.7;
}
.search__snippet :deep(em.hl) {
  color: #f53f3f;
  font-style: normal;
  font-weight: 600;
}
.search__tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}
.search__tagMore {
  font-size: 12px;
  color: #86909c;
  cursor: default;
}
.search__author {
  color: #409eff;
  cursor: pointer;
}
.search__authorFilter {
  margin-left: 8px;
}
.search__meta {
  display: flex;
  gap: 16px;
  color: #86909c;
  font-size: 12px;
  margin-top: 6px;
}
.search__more {
  text-align: center;
  margin-top: 16px;
}
</style>
