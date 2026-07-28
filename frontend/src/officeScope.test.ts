import { describe, expect, it } from 'vitest'
import type { CurrentUser } from './api/types'
import { canAccessOffice, canManageOffice, manageableOffices } from './officeScope'

const shanghai = {
  id: 'sha',
  code: 'SHA',
  nameZh: '上海办公室',
  nameEn: 'Shanghai Office',
  timezone: 'Asia/Shanghai',
  defaultCurrency: 'CNY',
  manageable: false,
}

function user(overrides: Partial<CurrentUser> = {}): CurrentUser {
  return {
    userId: 'user-1',
    organizationId: 'org-1',
    username: 'lawyer',
    displayName: 'Lawyer',
    authorities: [],
    preferredLocale: 'zh-CN',
    globalOfficeAccess: false,
    accessibleOffices: [shanghai],
    ...overrides,
  }
}

describe('office scope UI policy', () => {
  it('limits a regular user to assigned offices', () => {
    const current = user()
    expect(canAccessOffice(current, 'sha')).toBe(true)
    expect(canAccessOffice(current, 'dxb')).toBe(false)
    expect(canManageOffice(current, 'sha')).toBe(false)
  })

  it('exposes only manageable offices for local administrators', () => {
    const current = user({
      accessibleOffices: [
        { ...shanghai, manageable: true },
        { ...shanghai, id: 'bjs', code: 'BJS', manageable: false },
      ],
    })
    expect(manageableOffices(current).map((office) => office.id)).toEqual(['sha'])
  })

  it('allows a global administrator to manage every listed office', () => {
    const current = user({ globalOfficeAccess: true })
    expect(canAccessOffice(current, 'dxb')).toBe(true)
    expect(canManageOffice(current, 'dxb')).toBe(true)
    expect(manageableOffices(current)).toHaveLength(1)
  })
})
