import { expect, test, type Page, type Route } from '@playwright/test'

const ids = {
  organization: '20000000-0000-0000-0000-000000000001',
  user: '20000000-0000-0000-0000-000000000002',
  office: '20000000-0000-0000-0000-000000000003',
  matter: '20000000-0000-0000-0000-000000000004',
  deadline: '20000000-0000-0000-0000-000000000005',
  event: '20000000-0000-0000-0000-000000000006',
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
}

async function installMocks(page: Page) {
  let status = 'OPEN'
  let version = 0
  const events = [{
    id: ids.event,
    action: 'CREATED',
    actorDisplayName: 'Zhang Lawyer',
    fromStatus: null,
    toStatus: 'OPEN',
    note: 'Deadline registered',
    occurredAt: '2026-07-29T07:00:00Z',
  }]
  const deadline = () => ({
    id: ids.deadline,
    matterId: ids.matter,
    matterNumber: 'MAT-2026-001',
    matterTitle: 'Orion arbitration',
    title: 'Submit statement of defence',
    dueAt: '2026-08-15T09:00:00Z',
    deadlineType: 'ARBITRATION',
    priority: 'URGENT',
    status,
    ownerUserId: ids.user,
    ownerName: 'Zhang Lawyer',
    reminderPolicy: '{"daysBefore":[7,3,1]}',
    sourceType: 'COURT_ORDER',
    sourceReference: 'Tribunal Procedural Order No. 2',
    calculationNote: 'Thirty calendar days from service on 16 July 2026.',
    createdByName: 'Zhang Lawyer',
    version,
    eventCount: events.length,
    reminderCount: 1,
  })

  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname.replace(/^.*\/api/, '')
    if (path === '/auth/config') return json(route, { authMode: 'dev' })
    if (path === '/tenant') return json(route, {
      organizationId: ids.organization,
      brandNameZh: '文森律师事务所',
      brandNameEn: 'Winson Law Firm',
      shortNameZh: '文森',
      shortNameEn: 'WINSON',
      defaultLocale: 'zh-CN',
      supportedLocales: ['zh-CN', 'en-US'],
      primaryTimezone: 'Asia/Shanghai',
      baseCurrency: 'CNY',
    })
    if (path === '/me') return json(route, {
      userId: ids.user,
      organizationId: ids.organization,
      username: 'admin',
      displayName: 'System Administrator',
      authorities: ['ROLE_USER'],
      roles: ['ADMIN'],
      permissions: ['DEADLINE_VIEW_ALL', 'DEADLINE_MANAGE'],
      preferredLocale: 'zh-CN',
      primaryOfficeId: ids.office,
      globalOfficeAccess: true,
      accessibleOffices: [],
    })
    if (path === '/notifications') return json(route, [])
    if (path === '/matters') return json(route, [{
      id: ids.matter,
      matterNumber: 'MAT-2026-001',
      title: 'Orion arbitration',
      matterType: 'ARBITRATION',
      status: 'ACTIVE',
      confidentialityLevel: 'CONFIDENTIAL',
      responsibleUserId: ids.user,
      responsibleName: 'Zhang Lawyer',
      memberCount: 1,
      workingLanguage: 'en-US',
      billingCurrency: 'CNY',
    }])
    if (path === `/matters/${ids.matter}/workspace`) return json(route, {
      team: [{ userId: ids.user, displayName: 'Zhang Lawyer', memberRole: 'LEAD' }],
    })
    if (path === '/deadlines') return json(route, [deadline()])
    if (path === `/deadlines/${ids.deadline}` && route.request().method() === 'GET') {
      return json(route, {
        deadline: deadline(),
        events,
        reminders: [{
          id: '20000000-0000-0000-0000-000000000007',
          recipientName: 'Zhang Lawyer',
          reminderDate: '2026-08-08',
          daysBefore: 7,
          createdAt: '2026-08-08T01:00:00Z',
        }],
      })
    }
    if (path === `/deadlines/${ids.deadline}/complete`) {
      const payload = route.request().postDataJSON() as { expectedVersion: number; note: string }
      expect(payload.expectedVersion).toBe(0)
      expect(payload.note).toContain('Filed')
      status = 'COMPLETED'
      version = 1
      events.push({
        id: '20000000-0000-0000-0000-000000000008',
        action: 'COMPLETED',
        actorDisplayName: 'System Administrator',
        fromStatus: 'OPEN',
        toStatus: 'COMPLETED',
        note: payload.note,
        occurredAt: '2026-07-29T08:00:00Z',
      })
      return json(route, { deadline: deadline(), events, reminders: [] })
    }
    return json(route, [])
  })
}

test('deadline workbench preserves source, ownership and completion evidence', async ({ page }, testInfo) => {
  const english = testInfo.project.name.includes('-en')
  await installMocks(page)
  await page.addInitScript(({ locale }) => {
    sessionStorage.setItem('law_oa_demo_entered', 'true')
    localStorage.setItem('law_oa_locale', locale)
  }, { locale: english ? 'en-US' : 'zh-CN' })

  await page.goto('/deadlines')
  await expect(page.locator('h2')).toContainText(
    english ? 'Legal deadline workbench' : '法律期限工作台',
  )
  await expect(page.getByText('Submit statement of defence').first()).toBeVisible()
  await expect(page.getByText('Tribunal Procedural Order No. 2')).toBeVisible()
  await expect(page.getByText(
    english ? 'Immutable disposition trail' : '不可篡改处置记录',
  )).toBeVisible()

  await page.getByRole('button', {
    name: english ? 'Complete' : '确认完成',
    exact: true,
  }).click()
  await page.locator('.el-message-box textarea').fill('Filed through tribunal portal')
  await page.locator('.el-message-box__btns .el-button--primary').click()
  await expect(page.getByText(english ? 'Completed' : '已完成').first()).toBeVisible()

  const geometry = await page.evaluate(() => ({
    viewport: document.documentElement.clientWidth,
    scroll: document.documentElement.scrollWidth,
  }))
  expect(geometry.scroll).toBeLessThanOrEqual(geometry.viewport + 1)
})
