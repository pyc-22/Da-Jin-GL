import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const home = readFileSync(resolve(process.cwd(), 'src/views/RoleHome.vue'), 'utf8')
const store = readFileSync(resolve(process.cwd(), 'src/stores/app.js'), 'utf8')

describe('mobile silver prices', () => {
  it('shows independent silver sale and recycle prices for every role home', () => {
    expect(store).toContain('silverSale:')
    expect(store).toContain('silverRecycle:')
    expect(store).toContain("=== 'SILVER_RECYCLE'")
    expect(home).toContain('money(app.silverSale?.price)')
    expect(home).toContain('money(app.silverRecycle?.price)')
    expect(home).toContain('银卖价')
    expect(home).toContain('银回收价')
  })
})
