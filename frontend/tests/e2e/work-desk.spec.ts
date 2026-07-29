import { expect, test, type Page, type Route } from '@playwright/test'

const ids = {
  organization: '30000000-0000-0000-0000-000000000001',
  user: '30000000-0000-0000-0000-000000000002',
  collaborator: '30000000-0000-0000-0000-000000000003',
  office: '30000000-0000-0000-0000-000000000004',
  matter: '30000000-0000-0000-0000-000000000005',
  task: '30000000-0000-0000-0000-000000000006',
  event: '30000000-0000-0000-0000-000000000007',
  approval: 'approval-task-1',
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
}

async function installMocks(page: Page) {
  let taskStatus = 'IN_PROGRESS'
  let taskVersion = 1
  const taskEvents = [{
    id: ids.event,
    action: 'CREATED',
    actorUserId: ids.user,
    actorName: 'Zhang Lawyer',
    fromStatus: null,
    toStatus: 'TODO',
    note: 'Task created',
    occurredAt: '2026-07-29T07:00:00Z',
  }]
  const matter = {
    id: ids.matter,
    matterNumber: 'MAT-2026-001',
    title: 'Orion arbitration',
    matterType: 'ARBITRATION',
    status: 'ACTIVE',
    confidentialityLevel: 'CONFIDENTIAL',
    responsibleUserId: ids.user,
    responsibleName: 'Zhang Lawyer',
    memberCount: 2,
    workingLanguage: 'en-US',
    billingCurrency: 'CNY',
  }
  const task = () => ({
    id: ids.task,
    title: 'Review evidence index',
    description: 'Verify exhibit numbering, page references and source files.',
    status: taskStatus,
    priority: 'HIGH',
    ownerUserId: ids.user,
    ownerName: 'Zhang Lawyer',
    assignerName: 'Zhang Lawyer',
    dueAt: '2026-08-02T09:00:00Z',
    createdAt: '2026-07-29T07:00:00Z',
    commentCount: 1,
    officeId: ids.office,
    officeNameZh: '上海办公室',
    officeNameEn: 'Shanghai Office',
    relatedBusinessType: 'MATTER',
    relatedBusinessId: ids.matter,
    relatedBusinessLabel: 'MAT-2026-001 · Orion arbitration',
    version: taskVersion,
    participantCount: 1,
    eventCount: taskEvents.length,
  })
  const approval = {
    id: ids.approval,
    name: 'Partner review',
    processInstanceId: 'process-1',
    businessKey: `MATTER:${ids.matter}`,
    businessType: 'MATTER',
    businessId: ids.matter,
    candidateGroup: 'MANAGING_PARTNER',
    status: 'PENDING',
    createdAt: '2026-07-29T06:00:00Z',
  }

  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    const path = url.pathname.replace(/^.*\/api/, '')
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
      username: 'zhang',
      displayName: 'Zhang Lawyer',
      authorities: ['ROLE_USER'],
      roles: ['LAWYER', 'MANAGING_PARTNER'],
      permissions: ['TASK_CREATE', 'TASK_MANAGE', 'TASK_VIEW_ALL', 'WORKFLOW_APPROVE'],
      preferredLocale: 'zh-CN',
      primaryOfficeId: ids.office,
      officeNameZh: '上海办公室',
      officeNameEn: 'Shanghai Office',
      globalOfficeAccess: true,
      accessibleOffices: [],
    })
    if (path === '/notifications/unread-count') return json(route, { count: 3 })
    if (path === '/notifications') return json(route, { items: [], page: 1, size: 30, total: 0 })
    if (path === '/organization/users') return json(route, [
      { id: ids.user, username: 'zhang', displayName: 'Zhang Lawyer', status: 'ACTIVE' },
      { id: ids.collaborator, username: 'lee', displayName: 'Lee Paralegal', status: 'ACTIVE' },
    ])
    if (path === '/matters') return json(route, [matter])
    if (path === '/deadlines') return json(route, [{
      id: '30000000-0000-0000-0000-000000000008',
      matterId: ids.matter,
      matterNumber: matter.matterNumber,
      matterTitle: matter.title,
      title: 'File statement of defence',
      dueAt: '2026-08-05T09:00:00Z',
      priority: 'URGENT',
      deadlineType: 'ARBITRATION',
      status: 'OPEN',
      ownerName: 'Zhang Lawyer',
    }])
    if (path === '/work-tasks' && route.request().method() === 'GET') return json(route, [task()])
    if (path === `/work-tasks/${ids.task}` && route.request().method() === 'GET') {
      return json(route, {
        task: task(),
        participants: [{
          userId: ids.collaborator,
          displayName: 'Lee Paralegal',
          participantRole: 'COLLABORATOR',
          joinedAt: '2026-07-29T07:00:00Z',
        }],
        comments: [{
          id: '30000000-0000-0000-0000-000000000009',
          authorName: 'Lee Paralegal',
          content: 'First-pass cross-reference completed.',
          createdAt: '2026-07-29T08:00:00Z',
        }],
        events: taskEvents,
      })
    }
    if (path === `/work-tasks/${ids.task}/status`) {
      const payload = route.request().postDataJSON() as {
        status: string
        expectedVersion: number
        note?: string
      }
      expect(payload.expectedVersion).toBe(taskVersion)
      taskStatus = payload.status
      taskVersion += 1
      taskEvents.unshift({
        id: '30000000-0000-0000-0000-000000000010',
        action: payload.status === 'DONE' ? 'COMPLETED' : 'STATUS_CHANGED',
        actorUserId: ids.user,
        actorName: 'Zhang Lawyer',
        fromStatus: 'IN_PROGRESS',
        toStatus: payload.status,
        note: payload.note ?? '',
        occurredAt: '2026-07-29T09:00:00Z',
      })
      return json(route, task())
    }
    if (path === '/workflows/tasks') return json(route, [approval])
    if (path === '/workflows/inbox') return json(route, [])
    if (path === '/seals/requests') return json(route, [{
      id: '30000000-0000-0000-0000-000000000011',
      sealName: 'Firm seal',
      purpose: 'Engagement letter',
      copies: 2,
      status: 'SUBMITTED',
      requestedByName: 'Zhang Lawyer',
      createdAt: '2026-07-29T05:00:00Z',
    }])
    return json(route, [])
  })
}

test('workspace, task evidence and approval decisions form one low-friction flow', async ({ page }, testInfo) => {
  const english = testInfo.project.name.includes('-en')
  await installMocks(page)
  await page.addInitScript(({ locale }) => {
    sessionStorage.setItem('law_oa_demo_entered', 'true')
    localStorage.setItem('law_oa_locale', locale)
  }, { locale: english ? 'en-US' : 'zh-CN' })

  await page.goto('/')
  await expect(page.locator('h2')).toContainText(
    english ? 'Good' : '好',
  )
  await expect(page.getByText(english ? 'What deserves attention now' : '现在最值得处理')).toBeVisible()
  await expect(page.getByText('Review evidence index').first()).toBeVisible()
  await expect(page.getByText('File statement of defence').first()).toBeVisible()

  await page.goto(`/tasks?id=${ids.task}`)
  await expect(page.locator('h2')).toContainText(
    english ? 'Team task desk' : '协作任务工作台',
  )
  await expect(page.getByText('First-pass cross-reference completed.')).toBeVisible()
  await expect(page.getByText(english ? 'Evidence timeline' : '证据时间线')).toBeVisible()
  await page.getByRole('button', {
    name: english ? 'Complete' : '完成任务',
    exact: true,
  }).click()
  const dialog = page.getByRole('dialog')
  await dialog.locator('textarea').fill('Reviewed and filed in the matter workspace.')
  await dialog.getByRole('button', {
    name: english ? 'Confirm and record' : '确认并留痕',
    exact: true,
  }).click()
  await expect(page.locator('.task-detail .status-pill')).toHaveText(
    english ? 'Done' : '已完成',
  )

  await page.goto(`/approvals?taskId=${ids.approval}`)
  await expect(page.locator('h2')).toContainText(
    english ? 'Approval decision desk' : '审批决策中心',
  )
  await expect(page.getByText('Partner review').first()).toBeVisible()
  await expect(page.getByText(
    english ? 'Decision updates the business record' : '决定将同步回业务记录',
  )).toBeVisible()
  await expect(page.getByRole('button', {
    name: english ? 'Approve' : '通过',
    exact: true,
  }).first()).toBeVisible()

  const geometry = await page.evaluate(() => ({
    viewport: document.documentElement.clientWidth,
    scroll: document.documentElement.scrollWidth,
  }))
  expect(geometry.scroll).toBeLessThanOrEqual(geometry.viewport + 1)
})
