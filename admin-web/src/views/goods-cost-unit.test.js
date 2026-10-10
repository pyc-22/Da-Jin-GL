import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const goods = readFileSync(resolve(process.cwd(), 'src/views/Goods.vue'), 'utf8')
const stock = readFileSync(resolve(process.cwd(), 'src/views/Stock.vue'), 'utf8')

describe('goods cost price units and health check', () => {
  it('labels gram and piece prices with explicit units', () => {
    expect(goods).toContain('成本价（元/克）')
    expect(goods).toContain('成本价（元/件）')
    expect(stock).toContain('成本价（元/克）')
    expect(stock).toContain('成本价（元/件）')
  })

  it('warns on suspicious gram cost prices without changing inventory formula', () => {
    expect(goods).toContain('suspiciousGramGoods')
    expect(goods).toContain('ElMessageBox.confirm')
    expect(stock).toContain('库存金额（库存 × 成本价）')
  })
})
