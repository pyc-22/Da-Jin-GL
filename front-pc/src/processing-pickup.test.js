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
    expect(JSON.parse(calls[0][1].body)).toEqual({ type: 'pickup', urls: ['/api/file/pickup-1'], clientRequestId: 'pickup-photo-18' })
    expect(JSON.parse(statusCall.body)).toEqual({ status: 'PICKED_UP', clientRequestId: 'pickup-status-18' })
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

  it('keeps refund collection ahead of pickup and never changes status when photo upload fails', async () => {
    const request = vi.fn().mockRejectedValueOnce(new Error('照片上传失败'))
    await expect(markProcessingPickedUp({ processing_order_id: 18, due_amount: 200, paid_amount: 200, refund_amount: 50 }, request, ['/pickup.jpg']))
      .rejects.toThrow('客户返款未完成')
    expect(request).not.toHaveBeenCalled()

    await expect(markProcessingPickedUp({ processing_order_id: 18, due_amount: 200, paid_amount: 200 }, request, ['/pickup.jpg']))
      .rejects.toThrow('照片上传失败')
    expect(request).toHaveBeenCalledTimes(1)
    expect(request.mock.calls[0][0]).toContain('/photos')
    expect(request.mock.calls.some(([path]) => path.endsWith('/status'))).toBe(false)
  })
})
