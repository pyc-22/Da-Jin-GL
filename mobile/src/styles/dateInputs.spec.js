import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'

const css = readFileSync(new URL('./app.css', import.meta.url), 'utf8')

describe('mobile date input styling', () => {
  it('forces readable light native date controls in iOS webviews', () => {
    expect(css).toContain('input[type="date"],input[type="datetime-local"]')
    expect(css).toContain('color-scheme:light')
    expect(css).toContain('-webkit-text-fill-color:var(--ink)')
  })
})
