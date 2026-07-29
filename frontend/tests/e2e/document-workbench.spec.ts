import { expect, test, type Page, type Route } from '@playwright/test'

const matter = {
  id: '10000000-0000-0000-0000-000000000001',
  matterNumber: 'ML-2026-001',
  title: 'Cross-border acquisition',
  matterType: 'TRANSACTION',
  status: 'ACTIVE',
  confidentialityLevel: 'CONFIDENTIAL',
  responsibleUserId: '20000000-0000-0000-0000-000000000001',
  responsibleName: 'Zhang Lawyer',
  memberCount: 3,
  workingLanguage: 'zh-CN',
  billingCurrency: 'CNY',
}

const currentUser = {
  userId: matter.responsibleUserId,
  organizationId: '30000000-0000-0000-0000-000000000001',
  username: 'zhanglawyer',
  displayName: 'Zhang Lawyer',
  authorities: ['ROLE_USER'],
  roles: ['LAWYER'],
  permissions: ['DOCUMENT_GOVERNANCE_VIEW', 'DOCUMENT_GOVERNANCE_MANAGE', 'LEGAL_HOLD_MANAGE'],
  preferredLocale: 'zh-CN',
  globalOfficeAccess: true,
  accessibleOffices: [],
}

const document = {
  id: '40000000-0000-0000-0000-000000000001',
  matterId: matter.id,
  logicalName: 'Engagement agreement',
  documentType: 'CASE_FILE',
  confidentialityLevel: 'CONFIDENTIAL',
  currentVersionId: '50000000-0000-0000-0000-000000000002',
  versionNumber: 2,
  versionStatus: 'DRAFT',
  signatureStatus: 'UNSIGNED',
  originalFilename: 'engagement-v2.pdf',
  sizeBytes: 2048,
  createdAt: '2026-07-29T06:00:00Z',
  ingestionStatus: 'AVAILABLE',
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
    const url = new URL(route.request().url())
    const path = url.pathname.replace(/^.*\/api/, '')
    if (path === '/auth/config') return json(route, { authMode: 'dev' })
    if (path === '/me') return json(route, currentUser)
    if (path === '/matters') return json(route, [matter])
    if (path === '/contracts') return json(route, [])
    if (path === '/organization/users') {
      return json(route, [{
        id: '20000000-0000-0000-0000-000000000002',
        username: 'assistant',
        displayName: 'Legal Assistant',
        status: 'ACTIVE',
      }])
    }
    if (path === '/documents') return json(route, [document])
    if (path === `/documents/${document.id}/versions`) {
      return json(route, [
        {
          id: document.currentVersionId,
          versionNumber: 2,
          filename: 'engagement-v2.pdf',
          contentType: 'application/pdf',
          detectedContentType: 'application/pdf',
          sizeBytes: 2048,
          sha256: 'a'.repeat(64),
          status: 'DRAFT',
          signatureStatus: 'UNSIGNED',
          ingestionStatus: 'AVAILABLE',
          current: true,
          createdBy: currentUser.userId,
          createdByName: currentUser.displayName,
          createdAt: '2026-07-29T06:00:00Z',
          scanCompletedAt: '2026-07-29T06:00:02Z',
        },
        {
          id: '50000000-0000-0000-0000-000000000001',
          versionNumber: 1,
          filename: 'engagement-v1.pdf',
          contentType: 'application/pdf',
          sizeBytes: 1900,
          sha256: 'b'.repeat(64),
          status: 'DRAFT',
          signatureStatus: 'UNSIGNED',
          ingestionStatus: 'AVAILABLE',
          current: false,
          createdBy: currentUser.userId,
          createdByName: currentUser.displayName,
          createdAt: '2026-07-28T06:00:00Z',
        },
      ])
    }
    if (path === `/documents/${document.id}/grants`) return json(route, [])
    if (path === '/document-governance/templates') {
      return json(route, [{
        id: '60000000-0000-0000-0000-000000000001',
        code: 'ENGAGEMENT',
        nameZh: '委托协议模板',
        nameEn: 'Engagement Template',
        category: 'ENGAGEMENT',
        status: 'ACTIVE',
        versionNumber: 2,
        title: 'Engagement Agreement',
        bodyMarkdown: '# Engagement',
        updatedAt: '2026-07-29T06:00:00Z',
      }])
    }
    if (path === '/document-governance/clauses') return json(route, [])
    if (path === '/document-governance/legal-holds') return json(route, [])
    if (path === '/document-governance/retention-rules') return json(route, [])
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

test('document center exposes the complete low-cost lawyer workflow', async ({ page }, testInfo) => {
  const english = testInfo.project.name.includes('-en')
  await page.goto('/documents')
  await expect(page.locator('.document-detail')).toBeVisible()
  await expect(page.locator('.version-list article')).toHaveCount(2)
  await expect(page.getByRole('button', { name: english ? 'Preview' : '在线预览' }).first()).toBeVisible()
  await expect(page.getByRole('button', { name: english ? 'Secure download' : '安全下载' }).first()).toBeVisible()
  await expect(page.getByRole('button', { name: english ? 'Upload new version' : '上传新版本' })).toBeVisible()
  await page.getByRole('button', { name: english ? 'Manage access' : '访问授权' }).click()
  await expect(page.getByText(english ? 'Document access' : '文档访问授权', { exact: true })).toBeVisible()
  const geometry = await page.evaluate(() => ({
    viewport: document.documentElement.clientWidth,
    scroll: document.documentElement.scrollWidth,
  }))
  expect(geometry.scroll).toBeLessThanOrEqual(geometry.viewport + 1)
})

test('governance manager can reach creation and version-management controls', async ({ page }, testInfo) => {
  const english = testInfo.project.name.includes('-en')
  await page.goto('/document-governance')
  await expect(page.getByRole('button', {
    name: english ? 'New template' : '新建模板',
  })).toBeVisible()
  await expect(page.locator('.record-action')).toHaveCount(1)
  await page.getByRole('button', {
    name: english ? 'New template' : '新建模板',
  }).click()
  await expect(page.getByText(
    english ? 'Create document template' : '新建文档模板',
    { exact: true },
  )).toBeVisible()
  await expect(page.getByRole('button', {
    name: english ? 'Save & apply' : '保存并生效',
  })).toBeVisible()
})
