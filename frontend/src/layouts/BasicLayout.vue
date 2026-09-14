<script setup>
import { computed } from 'vue'
import { RouterLink, RouterView, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { logout } from '@/api/auth'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()

const displayName = computed(() => userStore.nickname || '已登录用户')

async function handleLogout() {
  try {
    // 必须把 refreshToken 传给后端，否则它仍能换回新的 access token
    await logout({ refreshToken: userStore.refreshToken })
  } catch {
    // 后端注销失败也要清本地状态：本地不清会导致界面显示"已登录"但请求全被拒
  } finally {
    userStore.clear()
    ElMessage.success('已退出登录')
    router.push({ name: 'assets' })
  }
}
</script>

<template>
  <el-container class="layout">
    <el-header class="layout__header">
      <span class="layout__brand">AIHub</span>
      <nav class="layout__nav">
        <RouterLink to="/">资源列表</RouterLink>
        <RouterLink to="/search">搜索</RouterLink>
        <RouterLink to="/agent">AI Agent</RouterLink>
        <RouterLink to="/workspaces">我的 Workspace</RouterLink>
        <RouterLink to="/assets/new">新建资源</RouterLink>
      </nav>
      <div class="layout__user">
        <template v-if="userStore.isLoggedIn">
          <span class="layout__user-name">{{ displayName }}</span>
          <el-button link type="primary" @click="handleLogout">退出</el-button>
        </template>
        <RouterLink v-else to="/login">登录 / 注册</RouterLink>
      </div>
    </el-header>
    <el-main class="layout__main">
      <RouterView />
    </el-main>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100%;
}

.layout__header {
  display: flex;
  align-items: center;
  gap: 24px;
  background-color: #ffffff;
  border-bottom: 1px solid #e5e6eb;
}

.layout__brand {
  font-size: 18px;
  font-weight: 600;
}

.layout__nav {
  display: flex;
  gap: 16px;
  /* 居中比默认的 stretch 更稳：菜单项若换成两行或加图标不会错位 */
  align-items: center;
}

.layout__nav a,
.layout__user a {
  color: #1f2329;
  text-decoration: none;
}

/* 用 exact-active 而不是 active：active 是前缀匹配，to="/" 是所有路径的前缀，
 * 会让任何页面下「资源列表」都高亮。 */
.layout__nav a.router-link-exact-active {
  color: #165dff;
  font-weight: 500;
}

.layout__user {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 12px;
}

.layout__user-name {
  color: #4e5969;
}

.layout__main {
  padding: 24px;
}
</style>
