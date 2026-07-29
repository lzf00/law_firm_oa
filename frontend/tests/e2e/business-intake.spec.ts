import { expect, test, type Page, type Route } from '@playwright/test'

const ids = {
  organization: '40000000-0000-0000-0000-000000000001',
  user: '40000000-0000-0000-0000-000000000002',
  office: '40000000-0000-0000-0000-000000000003',
  clientParty: '40000000-0000-0000-0000-000000000004',
  opposingParty: '40000000-0000-0000-0000-000000000005',
  client: '40000000-0000-0000-0000-000000000006',
  matter: '40000000-0000-0000-0000-000000000007',
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
}

async function installMocks(page: Page) {
  const partyUpdates: Array<Record<string, unknown>> = []
  const matterCreates: Array<Record<string, unknown>> = []
  let clientParty = {
    id: ids.clientParty,
    partyType: 'ORGANIZATION',
    displayName: 'Orion Technologies Ltd.',
    unifiedSocialCreditCode: 'REG-ORION-2026',
    riskLevel: 'NORMAL',
    aliases: ['Orion Tech'],
    notes: 'Confirm the group parent before accepting any cross-border instruction.',
  }
  const opposingParty = {
    id: ids.opposingParty,
    partyType: 'ORGANIZATION',
    displayName: 'Northstar Supply FZE',
    unifiedSocialCreditCode: 'REG-NORTHSTAR-2026',
    riskLevel: 'HIGH',
    aliases: ['Northstar'],
    notes: 'Existing opposing party in a procurement dispute.',
  }
  const client = {
    id: ids.client,
    partyId: ids.clientParty,
    clientNumber: 'CL-2026-019',
    displayName: clientParty.displayName,
    partyType: 'ORGANIZATION',
    ownerUserId: ids.user,
    ownerName: 'Zhang Lawyer',
    status: 'ACTIVE',
    source: 'Partner referral',
  }
  const office = {
    id: ids.office,
    code: 'DXB',
    nameZh: '迪拜总部',
    nameEn: 'Dubai Headquarters',
    countryCode: 'AE',
    cityZh: '迪拜',
    cityEn: 'Dubai',
    timezone: 'Asia/Dubai',
    defaultCurrency: 'AED',
    memberCount: 12,
  }
  const matter = {
    id: ids.matter,
    matterNumber: 'WS-2026-018',
    title: 'Orion regional distribution advice',
    matterType: 'CROSS_BORDER',
    status: 'ACTIVE',
    confidentialityLevel: 'CONFIDENTIAL',
    responsibleUserId: ids.user,
    responsibleName: 'Zhang Lawyer',
    memberCount: 3,
    officeId: ids.office,
    officeNameZh: office.nameZh,
    officeNameEn: office.nameEn,
    countryCode: 'AE',
    jurisdiction: 'United Arab Emirates',
    workingLanguage: 'en-US',
    billingCurrency: 'AED',
  }

  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    const path = url.pathname.replace(/^.*\/api/, '')
    const method = route.request().method()
    if (path === '/auth/config') return json(route, { authMode: 'dev' })
    if (path === '/tenant') return json(route, {
      organizationId: ids.organization,
      brandNameZh: '文森律师事务所',
      brandNameEn: 'Winson Law Firm',
      shortNameZh: '文森',
      shortNameEn: 'WINSON',
      defaultLocale: 'zh-CN',
      supportedLocales: ['zh-CN', 'en-US'],
      primaryTimezone: 'Asia/Dubai',
      baseCurrency: 'AED',
    })
    if (path === '/me') return json(route, {
      userId: ids.user,
      organizationId: ids.organization,
      username: 'zhang',
      displayName: 'Zhang Lawyer',
      authorities: ['ROLE_USER'],
      roles: ['LAWYER', 'MANAGING_PARTNER'],
      permissions: [
        'PARTY_CREATE', 'PARTY_MANAGE', 'CLIENT_CREATE',
        'CLIENT_MANAGE', 'MATTER_CREATE', 'MATTER_MANAGE',
      ],
      preferredLocale: 'zh-CN',
      primaryOfficeId: ids.office,
      officeNameZh: office.nameZh,
      officeNameEn: office.nameEn,
      globalOfficeAccess: true,
      accessibleOffices: [],
    })
    if (path === '/notifications/unread-count') return json(route, { count: 0 })
    if (path === '/notifications') return json(route, { items: [], page: 1, size: 30, total: 0 })
    if (path === '/organization/users') return json(route, [
      { id: ids.user, username: 'zhang', displayName: 'Zhang Lawyer', status: 'ACTIVE' },
    ])
    if (path === '/offices') return json(route, [office])
    if (path === '/clients') return json(route, [client])
    if (path === '/parties' && method === 'GET') return json(route, [clientParty, opposingParty])
    if (path === `/parties/${ids.clientParty}` && method === 'PUT') {
      const payload = route.request().postDataJSON() as Record<string, unknown>
      partyUpdates.push(payload)
      clientParty = { ...clientParty, ...payload }
      return json(route, clientParty)
    }
    if (path === '/matters' && method === 'GET') return json(route, [matter])
    if (path === '/matters' && method === 'POST') {
      const payload = route.request().postDataJSON() as Record<string, unknown>
      matterCreates.push(payload)
      return json(route, {
        summary: {
          ...matter,
          id: '40000000-0000-0000-0000-000000000008',
          matterNumber: payload.matterNumber,
          title: payload.title,
          status: 'CONFLICT_REVIEW',
        },
        parties: payload.parties,
      })
    }
    return json(route, [])
  })

  return { partyUpdates, matterCreates }
}

test('relationship master and matter intake preserve evidence and connect parties', async ({ page }, testInfo) => {
  const english = testInfo.project.name.includes('-en')
  const captured = await installMocks(page)
  await page.addInitScript(({ locale }) => {
    sessionStorage.setItem('law_oa_demo_entered', 'true')
    localStorage.setItem('law_oa_locale', locale)
  }, { locale: english ? 'en-US' : 'zh-CN' })

  await page.goto(`/parties?id=${ids.clientParty}`)
  await expect(page.locator('h2')).toContainText(english ? 'Clients and interested parties' : '客户与利益相关主体')
  await expect(page.getByText('Confirm the group parent before accepting any cross-border instruction.')).toBeVisible()
  await expect(page.getByText('Partner referral')).toBeVisible()
  await page.getByRole('button', { name: english ? 'Edit party' : '编辑主体' }).click()
  const partyDialog = page.getByRole('dialog')
  await expect(partyDialog.locator('textarea')).toHaveValue(
    'Confirm the group parent before accepting any cross-border instruction.',
  )
  await partyDialog.getByRole('button', {
    name: english ? 'Save party profile' : '保存主体档案',
  }).click()
  await expect.poll(() => captured.partyUpdates.length).toBe(1)
  expect(captured.partyUpdates[0].notes).toBe(
    'Confirm the group parent before accepting any cross-border instruction.',
  )

  await page.goto('/matters')
  await expect(page.locator('h2')).toContainText(english ? 'Matter center' : '案件中心')
  await expect(page.getByText('Orion regional distribution advice').first()).toBeVisible()
  await page.getByRole('button', { name: english ? 'Start matter intake' : '发起案件立项' }).click()
  const matterDialog = page.getByRole('dialog')
  await matterDialog.getByLabel(english ? 'Matter number' : '案件编号').fill('WS-2026-020')
  await matterDialog.getByLabel(english ? 'Matter title' : '案件名称').fill('Orion procurement dispute')
  await matterDialog.getByRole('button', { name: /Northstar Supply FZE/ }).click()
  await matterDialog.getByRole('button', {
    name: english ? 'Create and start conflict review' : '创建并进入冲突审查',
  }).click()

  await expect.poll(() => captured.matterCreates.length).toBe(1)
  const payload = captured.matterCreates[0] as {
    clientIds: string[]
    parties: Array<{ partyId: string; side: string }>
  }
  expect(payload.clientIds).toEqual([ids.client])
  expect(payload.parties).toEqual(expect.arrayContaining([
    expect.objectContaining({ partyId: ids.clientParty, side: 'CLIENT' }),
    expect.objectContaining({ partyId: ids.opposingParty, side: 'OPPOSING' }),
  ]))

  const geometry = await page.evaluate(() => ({
    viewport: document.documentElement.clientWidth,
    scroll: document.documentElement.scrollWidth,
  }))
  expect(geometry.scroll).toBeLessThanOrEqual(geometry.viewport + 1)
})
