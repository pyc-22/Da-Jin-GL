// @vitest-environment jsdom

import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('./api', () => ({ request: vi.fn() }))

import { request } from './api'
import { requestOrQueue, syncQueue, cancelQueuedOrder } from './offline'

describe('offline request handling', () => {
  beforeEach(() => {
    localStorage.clear()
    delete window.dajin
    request.mockReset()
  })

  it('parks a browser conflict without crashing the sync loop', async () => {
    await requestOrQueue('/api/order/create', { clientRequestId: 'conflict-1' }, { backendAvailable: false })
    request.mockRejectedValue(Object.assign(new Error('stock occupied'), { status: 409 }))
    await expect(syncQueue()).resolves.toEqual({ synced: 0, conflicts: 1 })
    expect(JSON.parse(localStorage.getItem('dajin_queue'))[0].status).toBe('CONFLICT')
  })

  it('cancels an offline order before reconnect instead of submitting its sale', async () => {
    await requestOrQueue('/api/order/create', { clientRequestId: 'cancel-1' }, { backendAvailable: false })
    await cancelQueuedOrder('cancel-1')
    request.mockResolvedValue({ cancelled: true })
    await syncQueue()
    expect(request).toHaveBeenCalledTimes(1)
    expect(request.mock.calls[0][0]).toBe('/api/order/cancel-by-client')
    expect(JSON.parse(request.mock.calls[0][1].body).orderClientRequestId).toBe('cancel-1')
  })

  it('syncs browser fallback work even when the native database recovers', async () => {
    window.dajin = { db: { enqueue: vi.fn().mockRejectedValue(new Error('unavailable')), queue: vi.fn().mockResolvedValue([]) } }
    await requestOrQueue('/api/order/create', { clientRequestId: 'fallback-1' }, { backendAvailable: false })
    request.mockResolvedValue({ orderId: 1 })
    await expect(syncQueue()).resolves.toEqual({ synced: 1, conflicts: 0 })
    expect(JSON.parse(localStorage.getItem('dajin_queue'))).toHaveLength(0)
  })

  it('does not queue HTTP authentication failures as successful offline work', async () => {
    request.mockRejectedValue(Object.assign(new Error('请先登录'), { status: 401, body: {} }))

    await expect(requestOrQueue('/api/member', { name: '测试会员' }, { backendAvailable: true }))
      .rejects.toMatchObject({ status: 401 })
    expect(JSON.parse(localStorage.getItem('dajin_queue') || '[]')).toHaveLength(0)
  })

  it('queues a request after connectivity retry fails', async () => {
    request.mockRejectedValue(new TypeError('Failed to fetch'))

    await expect(requestOrQueue('/api/member', { name: '离线会员' }, { backendAvailable: true }))
      .resolves.toMatchObject({ queued: true })
    expect(JSON.parse(localStorage.getItem('dajin_queue') || '[]')).toHaveLength(1)
    expect(request).toHaveBeenCalledTimes(2)
  })
})
