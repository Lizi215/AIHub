import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

// 单测配置单独一份，不复用 vite.config.js：那边挂了 Element Plus 的自动按需引入，
// 单测不需要它——组件测试显式引入被测组件，未注册的 Element Plus 组件在挂载时以 stub 出现。

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['src/**/*.test.js'],
    clearMocks: true
  }
})
