import { expect, test } from '@playwright/test'
import axe from 'axe-core'

const routes = [
  '/',
  '/matters',
  '/parties',
  '/conflicts',
  '/contracts',
  '/documents',
  '/deadlines',
  '/approvals',
  '/archives',
  '/announcements',
  '/tasks',
  '/leave',
  '/expenses',
  '/meetings',
  '/directory',
  '/offices',
  '/document-governance',
  '/finance',
  '/admin',
]

test('all commercial routes are bilingual, responsive and accessible', async ({ page }, testInfo) => {
  const english = testInfo.project.name.includes('-en')
  const consoleErrors: string[] = []
  const serverErrors: string[] = []
  const accessibilityErrors: string[] = []
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

  for (const route of routes) {
    await page.goto(route)
    await page.waitForLoadState('networkidle')
    await expect(page.locator('main#main-content')).toBeVisible()
    await expect(page.locator('h2').first()).toBeVisible()
    const geometry = await page.evaluate(() => ({
      viewport: document.documentElement.clientWidth,
      scroll: document.documentElement.scrollWidth,
    }))
    expect(geometry.scroll, `${route} has horizontal overflow`).toBeLessThanOrEqual(
      geometry.viewport + 1,
    )
    await page.addScriptTag({ content: axe.source })
    const results = await page.evaluate(async () => {
      const runner = (window as typeof window & {
        axe: {
          run: (options: unknown) => Promise<{
            violations: Array<{
              id: string
              impact: string
              nodes: Array<{ target: string[] }>
            }>
          }>
        }
      }).axe
      return runner.run({
        runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21aa'] },
      })
    })
    const critical = results.violations.filter((violation) =>
      violation.impact === 'critical' || violation.impact === 'serious')
    for (const violation of critical) {
      const targets = violation.nodes
        .flatMap((node: { target: string[] }) => node.target)
        .slice(0, 5)
        .join(', ')
      accessibilityErrors.push(`${route}: ${violation.id} (${targets})`)
    }
  }
  expect(accessibilityErrors, 'serious accessibility violations').toEqual([])
  expect(consoleErrors, 'browser console errors').toEqual([])
  expect(serverErrors, 'HTTP 5xx responses').toEqual([])
})
