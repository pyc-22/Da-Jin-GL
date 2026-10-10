import { describe, expect, it } from 'vitest'
import { createDirectOrderLine, hasDirectOrderLine } from './directOrder.js'

describe('direct goods order line', () => {
  it('uses one piece for a piece-priced item', () => {
    expect(createDirectOrderLine({ goods_id: 7, name: '戒指', status: 1, stock: 3, price_type: 2, sale_price: 500 })).toMatchObject({
      goodsId: 7, qty: 1, availableStock: 3, subtotal: 500, unitPrice: 500
    })
  })

  it('uses the requested weighing and current unit price for a gram-priced item', () => {
    expect(createDirectOrderLine({ goods_id: 8, name: '手镯', status: 1, available_stock: 12, price_type: 1 }, { gramWeight: 2.5, unitPrice: 900 })).toMatchObject({
      goodsId: 8, qty: 1, weight: 2.5, subtotal: 2250, unitPrice: 900
    })
  })

  it('rejects missing or over-stock gram weights, and unavailable goods', () => {
    const gram = { goods_id: 8, status: 1, available_stock: 2, price_type: 1 }
    expect(() => createDirectOrderLine(gram, { unitPrice: 900 })).toThrow('请先填写计费克重')
    expect(() => createDirectOrderLine(gram, { gramWeight: 2.1, unitPrice: 900 })).toThrow('超过当前可售库存')
    expect(() => createDirectOrderLine({ ...gram, status: 0 }, { gramWeight: 1, unitPrice: 900 })).toThrow('已下架')
    expect(() => createDirectOrderLine({ ...gram, available_stock: 0 }, { gramWeight: 1, unitPrice: 900 })).toThrow('库存不足')
  })

  it('recognizes an already-added direct-order item so route replay does not duplicate it', () => {
    expect(hasDirectOrderLine([{ goodsId: 8 }], 8)).toBe(true)
    expect(hasDirectOrderLine([{ goodsId: 8 }], 9)).toBe(false)
  })
})
