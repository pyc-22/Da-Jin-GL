import { describe, expect, it } from 'vitest'
import { mergeGoldPriceUpdate, resolveGoldReferences } from './gold-pricing'

describe('cashier gold price references', () => {
  it('uses combined recycle fields before legacy split rows', () => {
    const prices = resolveGoldReferences([
      { price_type: '足金', price: 900, salePrice: 900, recyclePrice: 860 },
      { price_type: '回收金价', price: 578 },
      { price_type: '银', price: 12, salePrice: 12, recyclePrice: 10 },
      { price_type: '银回收价', price: 0 }
    ])

    expect(prices).toMatchObject({ goldSpot: 900, recycleSpot: 860, silverSaleSpot: 12, silverRecycleSpot: 10 })
    expect(prices.goldMap['回收金价']).toBe(860)
    expect(prices.goldMap['银回收价']).toBe(10)
  })

  it('keeps legacy split rows working when combined fields are absent', () => {
    const prices = resolveGoldReferences([
      { price_type: '足金', price: 900 },
      { price_type: '回收金价', price: 578 },
      { price_type: '银', price: 12 },
      { price_type: '银回收价', price: 10 }
    ])

    expect(prices).toMatchObject({ goldSpot: 900, recycleSpot: 578, silverSaleSpot: 12, silverRecycleSpot: 10 })
  })

  it('merges a realtime update without touching unrelated order state', () => {
    const rows = [{ price_type: '足金', price: 900, salePrice: 900, recyclePrice: 850 }]
    const updated = mergeGoldPriceUpdate(rows, { priceType: '足金', price: 900, salePrice: 900, recyclePrice: 860, source: 'MANUAL' })
    expect(updated).toEqual([{ price_type: '足金', price: 900, salePrice: 900, recyclePrice: 860, source: 'MANUAL', pricingSource: 'MANUAL' }])
    expect(rows[0].recyclePrice).toBe(850)
  })

  it('honors an explicitly saved zero recycle price', () => {
    const prices = resolveGoldReferences([
      { price_type: '足金', price: 900, salePrice: 900, recyclePrice: 0, source: 'MANUAL', pricingSource: 'MANUAL' },
      { price_type: '回收金价', price: 578 }
    ])

    expect(prices.recycleSpot).toBe(0)
    expect(prices.goldMap['回收金价']).toBe(0)
  })

  it('mirrors a split recycle event onto the combined metal row', () => {
    const rows = [
      { price_type: '足金', salePrice: 900, recyclePrice: 850, source: 'MANUAL' },
      { price_type: '回收金价', price: 850 },
      { price_type: '银', salePrice: 12, recyclePrice: 0 },
      { price_type: '银回收价', price: 0 }
    ]
    const updated = mergeGoldPriceUpdate(rows, { priceType: '回收金价', price: 860, recyclePrice: 860, source: 'MANUAL' })
    const silverUpdated = mergeGoldPriceUpdate(updated, { priceType: '银回收价', price: 10, recyclePrice: 10, source: 'MANUAL' })

    expect(resolveGoldReferences(silverUpdated)).toMatchObject({ recycleSpot: 860, silverRecycleSpot: 10 })
    expect(rows[0].recyclePrice).toBe(850)
    expect(rows[2].recyclePrice).toBe(0)
  })
})
