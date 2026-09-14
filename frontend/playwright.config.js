import { defineConfig, devices } from '@playwright/test'

// 端到端冒烟。跑的是真实前端 + 真实网关 + 真实后端，运行前要先起好后端。
// 只覆盖关键路径（登录 → 列表 → 搜索 → Agent 对话），不做全量回归。

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: [['list']],
  timeout: 90_000,
  expect: { timeout: 15_000 },
  use: {
    baseURL: process.env.AIHUB_WEB || 'http://127.0.0.1:5173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure'
  },
  projects: [
    // 用系统已装的 Chrome，省去 `playwright install` 下载二进制；本机没有 Chrome 时改回默认 chromium。
    { name: 'chrome', use: { ...devices['Desktop Chrome'], channel: 'chrome' } }
  ],
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 5173',
    url: 'http://127.0.0.1:5173',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000
  }
})
