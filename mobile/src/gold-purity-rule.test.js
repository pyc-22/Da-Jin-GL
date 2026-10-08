import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const read = path => readFileSync(resolve(process.cwd(), path), 'utf8')
const roleHome = read('src/views/RoleHome.vue')
const salesOrder = read('src/views/home/SalesOrderSection.vue')
const recycle = read('src/views/RecycleCreate.vue')

// 甲方口径：成色 0.995 及以上（足金线）按整克，不折成色；低于才按含金量折算。
// 旧金折抵金额必须走 utils/goldPricing.js 的 oldGoldDeduction，不允许页面自己写 weight*成色*单价。
describe('mobile old-gold deduction uses the full-gold line', () => {
  it('never multiplies weight by the raw fineness to get money', () => {
    expect(roleHome).not.toMatch(/\*\s*Number\(m\.purity \|\| 0\)\s*\*/)
    expect(salesOrder).not.toContain('m.weight*finenessFactor')
    expect(recycle).not.toMatch(/\*\s*finenessFactor\(purity\.value\)\s*\*\s*Number\(recycle/)
  })

  it('routes every old-gold amount through the shared helper', () => {
    expect(roleHome).toContain('oldGoldDeduction(m.weight,m.purity')
    expect(salesOrder).toContain('oldGoldDeduction(m.weight, m.purity')
    expect(recycle).toContain('oldGoldDeduction(weight.value, purity.value, recycle.value)')
  })
})
