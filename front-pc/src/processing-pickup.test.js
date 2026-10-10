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
    const statusCall = calls.find(([path]) => path.endsWith('/status'))[1]
    expect(JSON.parse(statusCall.body)).toEqual(expect.objectContaining({ status: 'PICKED_UP', clientRequestId: expect.any(String) }))
  })

  it('confirms pickup without photos', async () => {
    const request = vi.fn(async () => ({ status: 'PICKED_UP' }))
    const row = { processing_order_id: 18, due_amount: 200, paid_amount: 200 }
    await markProcessingPickedUp(row, request, [])
    expect(request).toHaveBeenCalledTimes(1)
    expect(request.mock.calls[0][0]).toBe('/api/processing/orders/18/status')
  })

  it('keeps unpaid orders in the pending-pickup list', async () => {
    expect(processingOutstanding({ due_amount: 200, paid_amount: 50 })).toBe(150)
    await expect(markProcessingPickedUp({ processing_order_id: 18, due_amount: 200, paid_amount: 50 }, vi.fn())).rejects.toThrow('尾款未收清')
  })
})
