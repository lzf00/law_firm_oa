import { describe, expect, it } from 'vitest'
import { messages } from './i18n'

describe('locale catalog', () => {
  it('contains the same reviewed keys in Chinese and English', () => {
    const chineseKeys = Object.keys(messages['zh-CN']).sort()
    const englishKeys = Object.keys(messages['en-US']).sort()
    expect(englishKeys).toEqual(chineseKeys)
  })

  it('contains no blank customer-facing translations', () => {
    for (const catalog of Object.values(messages)) {
      for (const value of Object.values(catalog)) {
        expect(value.trim().length).toBeGreaterThan(0)
      }
    }
  })
})
