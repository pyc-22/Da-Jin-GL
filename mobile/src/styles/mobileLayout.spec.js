import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const css = readFileSync(resolve(process.cwd(), 'src/styles/app.css'), 'utf8')

describe('mobile layout safeguards', () => {
  it('reserves space for the fixed bottom navigation and safe area', () => {
    expect(css).toContain('.shell{min-height:100vh;padding-bottom:calc(120px + env(safe-area-inset-bottom))')
    expect(css).toContain('.report-page{padding-bottom:calc(128px + env(safe-area-inset-bottom))')
  })

  it('allows long list content to wrap and keeps report actions visible', () => {
    expect(css).toContain('.list-card{')
    expect(css).toContain('overflow-wrap:anywhere')
    expect(css).toContain('.report-row-action{grid-column:2;grid-row:2')
    expect(css).toContain('.detail-drawer-body{')
    expect(css).toContain('.kpi-grid{grid-template-columns:repeat(2,minmax(0,1fr))}')
  })

  it('keeps normal vertical scrolling on the page axis', () => {
    expect(css).toContain('.shell{min-height:100vh;padding-bottom:calc(120px + env(safe-area-inset-bottom));overflow-x:clip;touch-action:pan-y}')
  })
})
