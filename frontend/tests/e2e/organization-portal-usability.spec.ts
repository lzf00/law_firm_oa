import { expect, test } from '@playwright/test'

async function enterDemo(page: import('@playwright/test').Page, locale: string) {
  await page.addInitScript(({ selectedLocale }) => {
    sessionStorage.setItem('law_oa_demo_entered', 'true')
    localStorage.setItem('law_oa_locale', selectedLocale)
  }, { selectedLocale: locale })
}

test('organization portal filters reduce daily navigation cost', async ({ page }, testInfo) => {
  const english = testInfo.project.name.includes('-en')
  const locale = english ? 'en-US' : 'zh-CN'
  const consoleErrors: string[] = []
  const serverErrors: string[] = []
  page.on('console', (message) => {
    if (message.type() === 'error') consoleErrors.push(message.text())
  })
  page.on('response', (response) => {
    if (response.status() >= 500) serverErrors.push(`${response.status()} ${response.url()}`)
  })
  await enterDemo(page, locale)

  await page.goto('/announcements')
  await page.waitForLoadState('networkidle')
  await expect(page.locator('.announcement-controls')).toBeVisible()
  const unreadButton = page.getByRole('button', {
    name: new RegExp(english ? '^Unread' : '^未读'),
  })
  const unreadCount = Number(await unreadButton.locator('b').textContent())
  await unreadButton.click()
  await expect(page.locator('.announcement-card')).toHaveCount(unreadCount)

  await page.goto('/leave')
  await page.waitForLoadState('networkidle')
  await expect(page.locator('.leave-summary')).toBeVisible()
  await page.locator('.leave-filters button').filter({
    hasText: english ? 'Approved' : '已批准',
  }).click()
  await expect(page.locator('tbody tr').first()).toBeVisible()

  await page.goto('/directory')
  await page.waitForLoadState('networkidle')
  const initialPeople = await page.locator('.person-card').count()
  await page.locator('.department-list button').nth(1).click()
  const filteredPeople = await page.locator('.person-card').count()
  expect(filteredPeople).toBeLessThanOrEqual(initialPeople)
  await expect(page.locator('.directory-metrics')).toBeVisible()

  await page.goto('/meetings')
  await page.waitForLoadState('networkidle')
  await expect(page.locator('.meeting-summary')).toBeVisible()
  await expect(page.getByRole('button', {
    name: english ? 'Book a room' : '预约会议室',
  })).toBeVisible()

  const geometry = await page.evaluate(() => ({
    viewport: document.documentElement.clientWidth,
    scroll: document.documentElement.scrollWidth,
  }))
  expect(geometry.scroll).toBeLessThanOrEqual(geometry.viewport + 1)
  expect(consoleErrors, 'browser console errors').toEqual([])
  expect(serverErrors, 'HTTP 5xx responses').toEqual([])
})

test('organization portal hides privileged actions from a lawyer', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop-zh')
  await enterDemo(page, 'zh-CN')
  await page.route('**/api/**', async (route) => {
    await route.continue({
      headers: {
        ...route.request().headers(),
        'X-Dev-User': 'zhanglawyer',
      },
    })
  })

  await page.goto('/announcements')
  await page.waitForLoadState('networkidle')
  await expect(page.getByRole('button', { name: '发布公告' })).toHaveCount(0)

  await page.goto('/meetings')
  await page.waitForLoadState('networkidle')
  await expect(page.locator('.meeting-row .compact-action')).toHaveCount(0)
})
