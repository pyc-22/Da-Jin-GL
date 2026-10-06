import { describe, expect, it, vi } from 'vitest'
import { stableOrderClientRequestId } from './orderIdentity.js'

const order = () => ({ memberId: 7, discount: 1, laborFee: 0, items: [{ goodsId: 9, qty: 1, weight: 2, unitPrice: 800, subtotal: 1600, pieceNos: [] }], oldMetals: [] })

describe('stable mobile order identity', () => {
  it('reuses an id for retries of the same cart and rotates after the cart changes', () => {
    const values = new Map()
    const storage = { get: (key, fallback = '') => values.has(key) ? values.get(key) : fallback, set: (key, value) => values.set(key, value) }
    const first = stableOrderClientRequestId(order(), storage, () => 100, () => 0.1)
    const retry = stableOrderClientRequestId(order(), storage, () => 200, () => 0.2)
    const next = stableOrderClientRequestId({ ...order(), items: [{ ...order().items[0], weight: 3 }] }, storage, () => 300, () => 0.3)
    expect(retry).toBe(first)
    expect(next).not.toBe(first)
  })
})
