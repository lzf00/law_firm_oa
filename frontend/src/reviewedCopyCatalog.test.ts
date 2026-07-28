import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { reviewedCopyCatalog } from './reviewedCopyCatalog'

describe('reviewed customer copy', () => {
  it('keeps every reviewed pair non-empty', () => {
    expect(Object.keys(reviewedCopyCatalog).length).toBeGreaterThan(400)
    for (const pair of Object.values(reviewedCopyCatalog)) {
      expect(pair.zh.trim().length).toBeGreaterThan(0)
      expect(pair.en.trim().length).toBeGreaterThan(0)
    }
  })

  it('does not reintroduce inline Chinese copy, ad-hoc text helpers, or raw statuses', () => {
    const viewsDirectory = fileURLToPath(new URL('./views/', import.meta.url))
    for (const file of readdirSync(viewsDirectory).filter((name) => name.endsWith('.vue'))) {
      const source = readFileSync(`${viewsDirectory}/${file}`, 'utf8')
      expect(source, file).not.toMatch(/[\u4e00-\u9fff]/)
      expect(source, file).not.toMatch(/\btext\s*\(/)
      expect(source, file).not.toMatch(/\{\{[^}]*\.status\s*\}\}/)
    }
  })
})
