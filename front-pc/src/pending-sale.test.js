import { describe, expect, it } from 'vitest'
import { pendingSaleOrderId, shouldReusePendingSale } from './pending-sale'

describe('pending cashier sale state', () => {
  it('reuses an already-created pending order instead of creating another one', () => {
    const order = { orderId: 42, status: 0 }
    expect(shouldReusePendingSale(order)).toBe(true)
    expect(pendingSaleOrderId(order)).toBe(42)
  })

  it('does not treat a completed order as cancellable', () => {
    expect(shouldReusePendingSale({ orderId: 42, status: 1 })).toBe(false)
    expect(pendingSaleOrderId({ orderId: 42, status: 1 })).toBeNull()
  })
})
