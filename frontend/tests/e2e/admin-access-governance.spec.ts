import { expect, test } from '@playwright/test'

test('administrator can inspect effective access and preview role impact', async ({
  page,
}, testInfo) => {
  const english = testInfo.project.name.includes('-en')
  const mobile = testInfo.project.name.includes('mobile')
  const consoleErrors: string[] = []
  const serverErrors: string[] = []
  page.on('console', (message) => {
    if (message.type() === 'error') consoleErrors.push(message.text())
  })
  page.on('response', (response) => {
    if (response.status() >= 500) serverErrors.push(`${response.status()} ${response.url()}`)
  })
  await page.addInitScript(({ locale }) => {
    sessionStorage.setItem('law_oa_demo_entered', 'true')
    localStorage.setItem('law_oa_locale', locale)
  }, { locale: english ? 'en-US' : 'zh-CN' })

  await page.goto('/admin')
  await page.waitForLoadState('networkidle')
  await expect(page.getByRole('heading', {
    name: english ? 'Admin Console' : '管理控制台',
    level: 2,
  })).toBeVisible()

  const accessResponse = page.waitForResponse((response) =>
    response.url().includes('/admin/users/')
      && response.url().endsWith('/effective-access')
      && response.status() === 200)
  await page.locator('tbody tr', { hasText: 'admin' }).getByRole('button', {
    name: english ? 'Edit access' : '编辑权限',
  }).click()
  await accessResponse

  const accessDialog = page.getByRole('dialog')
  await expect(accessDialog).toBeVisible()
  await expect(accessDialog.getByText(
    english ? 'Effective access now' : '当前有效权限',
  )).toBeVisible()
  await expect(accessDialog.getByText('ADMIN', { exact: true }).first()).toBeVisible()
  await expect(accessDialog.getByText('ADMIN_CONSOLE_VIEW', { exact: true })).toBeHidden()
  await accessDialog.getByText(
    english ? /View all \d+ effective permissions/ : /查看全部 \d+ 项有效权限/,
  ).click()
  await expect(accessDialog.getByText('ADMIN_CONSOLE_VIEW', { exact: true })).toBeVisible()

  const accessGeometry = await accessDialog.evaluate((element) => ({
    width: element.getBoundingClientRect().width,
    viewport: document.documentElement.clientWidth,
    scroll: element.scrollWidth,
    client: element.clientWidth,
  }))
  expect(accessGeometry.width).toBeLessThanOrEqual(accessGeometry.viewport)
  expect(accessGeometry.scroll).toBeLessThanOrEqual(accessGeometry.client + 1)
  await page.screenshot({
    path: `/tmp/law-oa-admin-access-${testInfo.project.name}.png`,
    fullPage: true,
  })
  await accessDialog.getByRole('button', {
    name: english ? 'Close' : '关闭',
    exact: true,
  }).click()

  await page.getByRole('button', {
    name: new RegExp(english ? 'Roles & permissions' : '角色与权限'),
  }).click()
  await page.locator('.role-list > button').first().click()
  const roleDialog = page.getByRole('dialog')
  await expect(roleDialog.getByText(
    english ? 'Permission changes' : '本次权限变化',
  )).toBeVisible()
  const protectedPermission = roleDialog.locator(
    'label',
    { hasText: 'ADMIN_CONSOLE_VIEW' },
  ).locator('input[type="checkbox"]')
  await expect(protectedPermission).toBeChecked()
  await protectedPermission.uncheck()
  await expect(roleDialog.getByText(
    english ? '1 removed' : '移除 1',
    { exact: false },
  )).toBeVisible()
  await expect(roleDialog.getByText(
    english
      ? /Saving will immediately change backend access/
      : /保存后将立即影响该角色下/,
  )).toBeVisible()

  const documentGeometry = await page.evaluate(() => ({
    viewport: document.documentElement.clientWidth,
    scroll: document.documentElement.scrollWidth,
  }))
  expect(documentGeometry.scroll, `${mobile ? 'mobile' : 'desktop'} dialog overflow`)
    .toBeLessThanOrEqual(documentGeometry.viewport + 1)
  expect(consoleErrors, 'browser console errors').toEqual([])
  expect(serverErrors, 'HTTP 5xx responses').toEqual([])
})
