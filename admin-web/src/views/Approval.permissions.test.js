// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

const view = fs.readFileSync(path.resolve(process.cwd(), 'src/views/Approval.vue'), 'utf8')
describe('approval permission gates', () => {
  it('requires both generic handling and stock-check approval for stock checks', () => {
    expect(view).toContain("auth.can('approval:handle') && (row?.type !== 'STOCK_CHECK' || auth.can('stock:check:approve'))")
    expect(view).toContain("detail?.approval?.status === 1 && canHandle(detail?.approval)")
  })
})
