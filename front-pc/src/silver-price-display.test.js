import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const app = readFileSync(resolve(process.cwd(), 'src/App.vue'), 'utf8')

describe('cashier silver prices', () => {
  it('shows independent silver sale and recycle prices', () => {
    expect(app).toContain('resolveGoldReferences')
    expect(app).toContain('silverSaleSpot')
    expect(app).toContain('silverRecycleSpot')
    expect(app).toContain('银卖价')
    expect(app).toContain('银回收价')
  })
})
