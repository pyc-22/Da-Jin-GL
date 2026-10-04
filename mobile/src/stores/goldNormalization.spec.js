import { describe, expect, it } from 'vitest'
import { normalizeGoldRows } from './app.js'

describe('normalizeGoldRows', () => {
  it('maps legacy split price rows to sale and recycle prices', () => {
    const rows = normalizeGoldRows([
      { price_type: '足金', price: 900 },
      { price_type: '回收金价', price: 300 },
      { price_type: '银', price: 20 },
      { price_type: '银回收价', price: 15.89 }
    ])

    expect(rows[0]).toMatchObject({ salePrice: 900, recyclePrice: 300, marketStatus: 'CLOSED' })
    expect(rows[2]).toMatchObject({ salePrice: 20, recyclePrice: 15.89, marketStatus: 'CLOSED' })
  })

  it('keeps new combined fields unchanged', () => {
    const row = normalizeGoldRows([{ price_type: '足金', price: 1, salePrice: 862, recyclePrice: 813, marketStatus: 'OPEN' }])[0]
    expect(row).toMatchObject({ salePrice: 862, recyclePrice: 813, marketStatus: 'OPEN' })
  })
})
