import { describe, expect, it } from 'vitest'
import { formatLegalCode, formatLegalCodeList, hasCompleteLegalLabels } from './legalFormat'

describe('legal terminology formatter', () => {
  it('formats workflow and matter states in both locales', () => {
    expect(formatLegalCode('CONFLICT_REVIEW', 'zh-CN')).toBe('冲突审查中')
    expect(formatLegalCode('CONFLICT_REVIEW', 'en-US')).toBe('Conflict review')
    expect(formatLegalCode('APPROVED', 'en-US')).toBe('Approved')
  })

  it('formats roles, priorities and document terminology', () => {
    expect(formatLegalCode('RESPONSIBLE', 'zh-CN')).toBe('承办负责人')
    expect(formatLegalCode('URGENT', 'en-US')).toBe('Urgent')
    expect(formatLegalCode('CASE_FILE', 'en-US')).toBe('Matter file')
  })

  it('falls back safely for tenant-defined codes', () => {
    expect(formatLegalCode('CUSTOM_PRACTICE', 'en-US')).toBe('CUSTOM_PRACTICE')
    expect(formatLegalCode(undefined, 'zh-CN')).toBe('—')
  })

  it('formats comma-separated organization roles', () => {
    expect(formatLegalCodeList('ADMIN, LAWYER', 'en-US')).toBe('System administrator, Lawyer')
    expect(formatLegalCodeList('MANAGING_PARTNER', 'zh-CN')).toBe('管理合伙人')
  })

  it('keeps every reviewed label complete in both locales', () => {
    expect(hasCompleteLegalLabels()).toBe(true)
  })
})
