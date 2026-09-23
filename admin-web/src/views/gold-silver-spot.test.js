import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const view = readFileSync(resolve(process.cwd(), 'src/views/GoldPrice.vue'), 'utf8')
const api = readFileSync(resolve(process.cwd(), 'src/api/modules.js'), 'utf8')

describe('silver realtime reference price', () => {
  it('loads and displays a silver CNY/gram quote with an explicit apply action', () => {
    expect(api).toContain("silverSpot: () => http.get('/api/gold-price/spot/silver')")
    expect(view).toContain('白银实时回收参考')
    expect(view).toContain('silverSpot.price')
    expect(view).toContain('按行情更新银回收价')
    expect(view).toContain('applySilverSpot')
    expect(view).toContain("=== '银回收价'")
    expect(view).toContain("=== 'SILVER_RECYCLE'")
  })
})
