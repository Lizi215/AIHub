import { createRouter, createWebHistory } from 'vue-router'
import { getCurrentUser } from '@/api/auth'
import BasicLayout from '@/layouts/BasicLayout.vue'
import { useUserStore } from '@/stores/user'

const routes = [
  {
    path: '/',
    component: BasicLayout,
    children: [
      {
        path: '',
        name: 'assets',
        component: () => import('@/views/AssetListView.vue'),
        meta: { title: '资源列表' }
      },
      {
        path: 'assets/new',
        name: 'asset-create',
        component: () => import('@/views/AssetCreateView.vue'),
        meta: { title: '新建资源', requiresAuth: true }
      },
      {
        path: 'search',
        name: 'search',
        component: () => import('@/views/SearchView.vue'),
        meta: { title: '搜索' }
      },
      {
        path: 'agent',
        name: 'agent',
        component: () => import('@/views/AgentView.vue'),
        meta: { title: 'AI Agent', requiresAuth: true }
      },
      {
        path: 'workspaces',
        name: 'workspaces',
        component: () => import('@/views/WorkspaceListView.vue'),
        meta: { title: '我的 Workspace', requiresAuth: true }
      }
    ]
  },
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录' }
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/'
  }
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes
})

/**
 * 登录态守卫，当前只区分「需要登录」与「匿名可访问」。
 *
 * 同时回填用户资料：token 在 localStorage，而 userId / nickname / role 只在内存里，
 * 硬刷新后 store 重建会丢掉它们，owner-only 入口靠 userId 判断归属就会失效。
 * 所以发现「有 token 但没资料」时补一次 /users/me；失败不拦路由，交给 request.js 处理。
 */
router.beforeEach(async (to) => {
  const userStore = useUserStore()
  if (userStore.isLoggedIn && !userStore.userId) {
    try {
      userStore.setProfile(await getCurrentUser())
    } catch {
      // 忽略：不因为回填失败就把用户挡在页面外
    }
  }

  if (!to.meta.requiresAuth) {
    return true
  }
  if (userStore.isLoggedIn) {
    return true
  }
  // 带上来源路径，登录后可回到原页面
  return { name: 'login', query: { redirect: to.fullPath } }
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · AIHub` : 'AIHub'
})

export default router
