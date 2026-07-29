import { expect, test, type Page, type Route } from '@playwright/test'

const ids = {
  organization: '10000000-0000-0000-0000-000000000001',
  user: '10000000-0000-0000-0000-000000000002',
  office: '10000000-0000-0000-0000-000000000003',
  party: '10000000-0000-0000-0000-000000000004',
  matter: '10000000-0000-0000-0000-000000000005',
  conflict: '10000000-0000-0000-0000-000000000006',
  contract: '10000000-0000-0000-0000-000000000007',
  contractVersion: '10000000-0000-0000-0000-000000000008',
  primaryDocument: '10000000-0000-0000-0000-000000000009',
  primaryVersion: '10000000-0000-0000-0000-000000000010',
  signedDocument: '10000000-0000-0000-0000-000000000011',
  signedVersion: '10000000-0000-0000-0000-000000000012',
}

const currentUser = {
  userId: ids.user,
  organizationId: ids.organization,
  username: 'admin',
  displayName: 'System Administrator',
  authorities: ['ROLE_USER'],
  roles: ['ADMIN', 'CONFLICT_REVIEWER', 'CONTRACT_REVIEWER'],
  permissions: [
    'CONFLICT_CHECK_CREATE', 'CONFLICT_CHECK_VIEW_ALL', 'CONFLICT_CHECK_REVIEW',
    'CONTRACT_CREATE', 'CONTRACT_MANAGE', 'CONTRACT_FINALIZE',
    'CONTRACT_SIGN_ARCHIVE',
  ],
  preferredLocale: 'zh-CN',
  primaryOfficeId: ids.office,
  officeNameZh: '上海办公室',
  officeNameEn: 'Shanghai Office',
  defaultCurrency: 'CNY',
  globalOfficeAccess: true,
  accessibleOffices: [{
    id: ids.office,
    code: 'SHA',
    nameZh: '上海办公室',
    nameEn: 'Shanghai Office',
    timezone: 'Asia/Shanghai',
    defaultCurrency: 'CNY',
    manageable: true,
  }],
}

const conflictCheck = {
  id: ids.conflict,
  requestNumber: 'CC-20260729-001',
  officeId: ids.office,
  officeNameZh: '上海办公室',
  officeNameEn: 'Shanghai Office',
  matterTitle: 'Orion cross-border acquisition',
  status: 'APPROVED',
  riskLevel: 'HIGH',
  decision: 'WAIVER_REQUIRED',
  requestedBy: '10000000-0000-0000-0000-000000000099',
  requestedByName: 'Zhang Lawyer',
  reviewedBy: ids.user,
  reviewedByName: 'System Administrator',
  decisionRationale: 'An existing representation requires informed consent.',
  mitigationPlan: 'Separate teams, ethical wall and written informed consent.',
  createdAt: '2026-07-29T06:00:00Z',
  submittedAt: '2026-07-29T06:01:00Z',
  reviewedAt: '2026-07-29T06:05:00Z',
  restrictedDetails: true,
  parties: [{ partyId: ids.party, proposedRole: 'CLIENT' }],
  hits: [{
    partyId: ids.party,
    partyName: 'Orion Group',
    matterId: ids.matter,
    matterNumber: 'RESTRICTED',
    matterTitle: 'Restricted matter',
    partyRole: 'CLIENT',
    side: 'CLIENT',
    matterStatus: null,
    restricted: true,
  }],
  actions: [
    {
      id: '10000000-0000-0000-0000-000000000020',
      action: 'CREATED',
      actorName: 'Zhang Lawyer',
      riskLevel: 'HIGH',
      occurredAt: '2026-07-29T06:00:00Z',
    },
    {
      id: '10000000-0000-0000-0000-000000000021',
      action: 'DECIDED',
      actorName: 'System Administrator',
      riskLevel: 'HIGH',
      decision: 'WAIVER_REQUIRED',
      rationale: 'An existing representation requires informed consent.',
      occurredAt: '2026-07-29T06:05:00Z',
    },
  ],
}

const contract = {
  id: ids.contract,
  contractNumber: 'CT-2026-001',
  title: 'Cross-border legal services agreement',
  status: 'SIGNED',
  clientId: ids.party,
  clientName: 'Orion Group',
  responsibleUserId: ids.user,
  responsibleName: 'Zhang Lawyer',
  effectiveDate: '2026-08-01',
  expiryDate: '2027-07-31',
  amount: 880000,
  currency: 'CNY',
  matterCount: 1,
  matterIds: [ids.matter],
  currentVersionNumber: 1,
  currentVersionStatus: 'FINAL',
  signatureStatus: 'SIGNED',
  signedAt: '2026-07-29T07:00:00Z',
}

const contractDetail = {
  contract,
  versions: [{
    id: ids.contractVersion,
    versionNumber: 1,
    status: 'FINAL',
    summary: 'Approved liability cap and termination provisions',
    primaryDocumentId: ids.primaryDocument,
    primaryDocumentVersionId: ids.primaryVersion,
    primaryFilename: 'contract-final.pdf',
    primarySha256: 'a'.repeat(64),
    signedDocumentId: ids.signedDocument,
    signedDocumentVersionId: ids.signedVersion,
    signedFilename: 'contract-signed.pdf',
    signedSha256: 'b'.repeat(64),
    signatureStatus: 'SIGNED',
    createdByName: 'Zhang Lawyer',
    createdAt: '2026-07-29T06:10:00Z',
    finalizedByName: 'System Administrator',
    finalizedAt: '2026-07-29T06:45:00Z',
    signedByName: 'System Administrator',
    signedAt: '2026-07-29T07:00:00Z',
  }],
  lifecycle: [
    {
      id: '10000000-0000-0000-0000-000000000030',
      action: 'VERSION_CREATED',
      actorName: 'Zhang Lawyer',
      occurredAt: '2026-07-29T06:10:00Z',
    },
    {
      id: '10000000-0000-0000-0000-000000000031',
      action: 'VERSION_FINALIZED',
      actorName: 'System Administrator',
      occurredAt: '2026-07-29T06:45:00Z',
    },
    {
      id: '10000000-0000-0000-0000-000000000032',
      action: 'SIGNED_FILE_ARCHIVED',
      actorName: 'System Administrator',
      occurredAt: '2026-07-29T07:00:00Z',
    },
  ],
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
    if (path === '/me') return json(route, currentUser)
    if (path === '/notifications') return json(route, [])
    if (path === '/parties') return json(route, [{
      id: ids.party,
      partyType: 'ORGANIZATION',
      displayName: 'Orion Group',
      riskLevel: 'STANDARD',
      aliases: [],
    }])
    if (path === '/conflict-checks') return json(route, [conflictCheck])
    if (path === '/contracts') return json(route, [contract])
    if (path === `/contracts/${ids.contract}`) return json(route, contractDetail)
    if (path === '/clients') return json(route, [])
    if (path === '/matters') return json(route, [])
    if (path === '/organization/users') return json(route, [])
    return json(route, [])
  })
}

test('conflict and contract workbenches expose the complete evidence trail', async ({ page }, testInfo) => {
  const english = testInfo.project.name.includes('-en')
  await installMocks(page)
  await page.addInitScript(({ locale }) => {
    sessionStorage.setItem('law_oa_demo_entered', 'true')
    localStorage.setItem('law_oa_locale', locale)
  }, { locale: english ? 'en-US' : 'zh-CN' })

  await page.goto('/conflicts')
  await expect(page.locator('h2')).toContainText(
    english ? 'Conflict clearance workbench' : '利益冲突工作台',
  )
  await expect(page.getByText('CC-20260729-001').first()).toBeVisible()
  await expect(page.getByText(
    english ? 'Waiver required' : '需豁免',
  )).toBeVisible()
  await expect(page.getByText(
    english
      ? 'Some matter details are redacted by access policy.'
      : '部分案件详情因权限限制已脱敏。',
    { exact: false },
  )).toBeVisible()

  await page.goto('/contracts')
  await expect(page.locator('h2')).toContainText(
    english ? 'Contract lifecycle' : '合同全生命周期',
  )
  await expect(page.getByText('CT-2026-001').first()).toBeVisible()
  await expect(page.getByText('contract-final.pdf')).toBeVisible()
  await expect(page.getByText('contract-signed.pdf')).toBeVisible()
  await expect(page.getByText('SHA-256', { exact: false })).toBeVisible()

  const geometry = await page.evaluate(() => ({
    viewport: document.documentElement.clientWidth,
    scroll: document.documentElement.scrollWidth,
  }))
  expect(geometry.scroll).toBeLessThanOrEqual(geometry.viewport + 1)
})
