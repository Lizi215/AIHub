import { test, expect } from '@playwright/test'

/**
 * 前端关键路径冒烟。运行前需起好整套后端（登录/搜索/Agent 都要真实接口）。
 *
 * <p>账号取自环境变量，默认用演示账号。
 */
const USER = process.env.AIHUB_TEST_USER || 'agent_demo_1790145477'
const PASS = process.env.AIHUB_TEST_PASSWORD || 'Test1234'

async function login(page) {
  await page.goto('/login')
  // 登录与注册两个 Tab 的输入框都在 DOM 里，用 autocomplete 精确定位登录那个
  await page.locator('input[autocomplete="username"]').fill(USER)
  await page.locator('input[autocomplete="current-password"]').fill(PASS)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  // 登录成功后跳回资源列表
  await expect(page).toHaveURL(/\/$/)
}

test.describe('AIHub 关键路径冒烟', () => {
  test('登录 → 资源列表 → 搜索 → Agent 对话', async ({ page }) => {
    test.slow()

    // ---------- 登录 ----------
    await login(page)
    await expect(page.locator('.layout__nav a', { hasText: '资源列表' })).toBeVisible()
    await expect(page.getByRole('button', { name: '新建资源' })).toBeVisible()
    // 已登录才有的筛选开关
    await expect(page.getByText('只看我的')).toBeVisible()

    // ---------- 搜索 ----------
    await page.getByRole('link', { name: '搜索' }).click()
    await expect(page).toHaveURL(/\/search$/)
    await page.getByPlaceholder('搜索资源…').fill('skill')
    await page.getByRole('button', { name: '搜索', exact: true }).click()
    // 有结果出网格、无结果出空态；两者都算搜索链路通（不 500、不卡死）
    await expect(
      page.locator('.search__grid').or(page.getByText('没有匹配的资源'))
    ).toBeVisible()

    // ---------- Agent 对话 ----------
    await page.getByRole('link', { name: 'AI Agent' }).click()
    await expect(page).toHaveURL(/\/agent$/)

    const composer = page.getByPlaceholder(/描述你的需求/)
    await composer.fill('帮我搜索 spring 相关的资源')
    await page.getByRole('button', { name: '发送' }).click()

    // 用户气泡原地出现
    await expect(page.locator('.msg--user', { hasText: 'spring' })).toBeVisible()
    // AI 任务从「正在创建任务」到终态：mock 模式下 SEARCH 场景应正常完成
    await expect(page.locator('.msg--ai .msg__meta .el-tag').last()).toContainText(
      /已完成|等待确认/,
      { timeout: 60_000 }
    )
  })

  test('未登录访问受限页 → 被引导到登录页', async ({ page }) => {
    await page.goto('/agent')
    await expect(page).toHaveURL(/\/login/)
  })

  test('登录后从列表进入新建资源页', async ({ page }) => {
    await login(page)
    await page.getByRole('button', { name: '新建资源' }).click()
    await expect(page).toHaveURL(/\/assets\/new$/)
  })
})
