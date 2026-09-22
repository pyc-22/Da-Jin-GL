import { describe, expect, it, vi } from 'vitest'
import { markProcessingPickedUp, processingOutstanding } from './processing-pickup'

describe('processing pickup', () => {
  it('saves pickup photos before changing the order to picked up', async () => {
    const calls = []
    const request = vi.fn(async (path, options) => {
      calls.push([path, options])
      return path.endsWith('/photos') ? { pickup_photos: '["/api/file/pickup-1"]' } : { status: 'PICKED_UP' }
    })
    const row = { processing_order_id: 18, due_amount: 200, paid_amount: 200 }
    await markProcessingPickedUp(row, request, ['/api/file/pickup-1'])
    expect(calls.map(([path]) => path)).toEqual([
      '/api/processing/orders/18/photos',
      '/api/processing/orders/18/status'
    ])
    expect(request).toHaveBeenLastCalledWith('/api/processing/orders/18/status', {
      method: 'PATCH',
      body: JSON.stringify({ status: 'PICKED_UP' })
    })
  })

  it('does not change status when pickup photos are missing', async () => {
    const request = vi.fn()
    const row = { processing_order_id: 18, due_amount: 200, paid_amount: 200 }
    await expect(markProcessingPickedUp(row, request, [])).rejects.toThrow('请先添加至少1张取货照片')
    expect(request).not.toHaveBeenCalled()
  })

  it('keeps unpaid orders in the pending-pickup list', async () => {
    expect(processingOutstanding({ due_amount: 200, paid_amount: 50 })).toBe(150)
    await expect(markProcessingPickedUp({ processing_order_id: 18, due_amount: 200, paid_amount: 50 }, vi.fn())).rejects.toThrow('尾款未收清')
  })
})
