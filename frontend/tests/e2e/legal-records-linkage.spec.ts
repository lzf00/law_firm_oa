import { expect, test, type Page, type Route } from '@playwright/test'

const ids = {
  organization: '70000000-0000-0000-0000-000000000001',
  user: '70000000-0000-0000-0000-000000000002',
  matter: '70000000-0000-0000-0000-000000000003',
  contract: '70000000-0000-0000-0000-000000000004',
  document: '70000000-0000-0000-0000-000000000005',
  documentVersion: '70000000-0000-0000-0000-000000000006',
  archive: '70000000-0000-0000-0000-000000000007',
}

const currentUser = {
  userId: ids.user,
  organizationId: ids.organization,
  username: 'records.manager',
  displayName: 'Records Manager',
  authorities: ['ROLE_USER'],
  roles: ['RECORDS_MANAGER'],
  permissions: ['ARCHIVE_CREATE', 'ARCHIVE_VIEW_ALL', 'ARCHIVE_MANAGE', 'ARCHIVE_CLOSE'],
  preferredLocale: 'zh-CN',
  globalOfficeAccess: true,
  accessibleOffices: [],
}

const matter = {
  id: ids.matter,
  matterNumber: 'ML-2026-088',
  title: 'Orion cross-border acquisition',
  matterType: 'TRANSACTION',
  status: 'ACTIVE',
  confidentialityLevel: 'CONFIDENTIAL',
  responsibleUserId: ids.user,
  responsibleName: 'Records Manager',
  memberCount: 4,
  workingLanguage: 'zh-CN',
  billingCurrency: 'CNY',
}

const archive = {
  id: ids.archive,
  archiveNumber: 'AR-2026-088',
  title: 'Acquisition contract evidence',
  matterId: ids.matter,
  matterNumber: matter.matterNumber,
  matterTitle: matter.title,
  retentionPolicyCode: 'LITIGATION_10Y',
  status: 'OPEN',
  createdBy: ids.user,
  createdByName: 'Records Manager',
  createdAt: '2026-07-29T08:00:00Z',
  itemCount: 1,
  items: [{
    documentId: ids.document,
    documentVersionId: ids.documentVersion,
    logicalName: 'Signed acquisition agreement',
    documentType: 'CONTRACT_SIGNED',
    sequenceNumber: 1,
    versionNumber: 2,
    filename: 'acquisition-signed-v2.pdf',
    sha256: 'af38d4e8c5b1'.padEnd(64, '0'),
    versionStatus: 'FINAL',
    signatureStatus: 'SIGNED',
    contractId: ids.contract,
    contractNumber: 'CT-2026-088',
  }],
  lifecycle: [{
    id: '70000000-0000-0000-0000-000000000008',
    action: 'ITEM_ADDED',
    actorName: 'Records Manager',
    documentId: ids.document,
    documentVersionId: ids.documentVersion,
    occurredAt: '2026-07-29T08:05:00Z',
  }],
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(body),
  })
}

async function installMocks(page: Page) {
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname.replace(/^.*\/api/, '')
    if (path === '/auth/config') return json(route, { authMode: 'dev' })
    if (path === '/me') return json(route, currentUser)
    if (path === '/matters') return json(route, [matter])
    if (path === '/archives' && route.request().method() === 'GET') return json(route, [archive])
    if (path === `/archives/${ids.archive}/candidates`) return json(route, [])
    if (path === `/archives/${ids.archive}/close`) {
      return json(route, {
        ...archive,
        status: 'ARCHIVED',
        archivedAt: '2026-07-29T08:10:00Z',
        archivedByName: 'Records Manager',
        lifecycle: [
          ...archive.lifecycle,
          {
            id: '70000000-0000-0000-0000-000000000009',
            action: 'CLOSED',
            actorName: 'Records Manager',
            comment: 'Register checked',
            occurredAt: '2026-07-29T08:10:00Z',
          },
        ],
      })
    }
    if (path === '/notifications/unread-count') return json(route, { count: 0 })
    return json(route, [])
  })
}

test.beforeEach(async ({ page }, testInfo) => {
  const english = testInfo.project.name.includes('-en')
  await page.addInitScript(({ locale }) => {
    sessionStorage.setItem('law_oa_demo_entered', 'true')
    localStorage.setItem('law_oa_locale', locale)
  }, { locale: english ? 'en-US' : 'zh-CN' })
  await installMocks(page)
})

test('archive pins exact contract evidence and closes an immutable register', async ({ page }, testInfo) => {
  const english = testInfo.project.name.includes('-en')
  await page.goto(`/archives?archiveId=${ids.archive}`)

  await expect(page.getByText('AR-2026-088').first()).toBeVisible()
  await expect(page.getByText('acquisition-signed-v2.pdf')).toBeVisible()
  await expect(page.getByText('V2 · SHA-256 af38d4e8c5b1', { exact: false })).toBeVisible()
  await expect(page.getByText('CT-2026-088', { exact: false })).toBeVisible()

  await page.getByRole('button', {
    name: english ? 'Close archive' : '确认封卷',
  }).click()
  await expect(page.getByText(
    english ? 'Confirm archive closure' : '封卷确认',
    { exact: true },
  )).toBeVisible()
  await page.getByPlaceholder(
    english ? 'Add a review conclusion or handover note' : '填写检查结论或移交说明',
  ).fill('Register checked')

  const closeRequest = page.waitForRequest((request) =>
    request.url().includes(`/archives/${ids.archive}/close`)
    && request.method() === 'POST')
  await page.getByRole('button', {
    name: english ? 'Freeze register & close' : '冻结目录并封卷',
  }).click()
  const request = await closeRequest
  expect(request.postDataJSON()).toEqual({ comment: 'Register checked' })
  await expect(page.getByText(english ? 'Closed' : '已封卷').first()).toBeVisible()

  const geometry = await page.evaluate(() => ({
    viewport: document.documentElement.clientWidth,
    scroll: document.documentElement.scrollWidth,
  }))
  expect(geometry.scroll).toBeLessThanOrEqual(geometry.viewport + 1)
})
