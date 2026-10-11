// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({
  processingOrders: vi.fn(), processingCreate: vi.fn(), processingHandover: vi.fn(),
  recycleCreate: vi.fn(), createOrder: vi.fn()
}))
vi.mock('../api/request.js', () => ({ api }))

import { addDraft, addPendingHandover, addProcessingDraft, listDrafts, listPendingHandovers, makeDraftRef } from './pendingProcessing.js'
import { syncPendingProcessing } from './processingSync.js'

const recent = () => new Date().toISOString().slice(0, 19).replace('T', ' ')

describe('mobile pending submit sync (加工/回收/销售 断网补传)', () => {
  beforeEach(() => {
    localStorage.clear()
    localStorage.setItem('dajin-user', JSON.stringify({ user_id: 9, store_id: 1 }))
    Object.values(api).forEach(fn => fn.mockReset())
    api.processingOrders.mockResolvedValue([])
  })

  it('加工草稿：先建单再转交前台，成功后出队', async () => {
    api.processingCreate.mockResolvedValue({ processing_order_id: 12, order_no: 'JG0012' })
    api.processingHandover.mockResolvedValue({ processing_order_id: 12, handover: 1 })
    addProcessingDraft({ ref: 'p1', payload: { customerPhone: '13800000000', processingItemId: 5 }, display: {} })

    const result = await syncPendingProcessing({ knownOrders: [] })

    expect(result.synced).toBe(1)
    expect(api.processingHandover).toHaveBeenCalledWith(12)
    expect(listDrafts('processing')).toHaveLength(0)
  })

  it('转交一直失败时留在待转交队列，恢复后补上', async () => {
    api.processingCreate.mockResolvedValue({ processing_order_id: 12, order_no: 'JG0012' })
    api.processingHandover.mockRejectedValue(new Error('断网'))
    addProcessingDraft({ ref: 'p1', payload: { customerPhone: '13800000000', processingItemId: 5 }, display: {} })
    expect(listDrafts('processing')).toHaveLength(1)

    const first = await syncPendingProcessing({ knownOrders: [] })
    expect(first.failed).toBe(1)
    expect(listPendingHandovers().map(row => row.orderId)).toEqual([12])
    expect(listPendingHandovers()[0]).toEqual(expect.objectContaining({ status: 'FAILED', error: '断网', lastRetryAt: expect.any(String) }))

    api.processingHandover.mockResolvedValue({ handover: 1 })
    // The failed network attempt is backoff-limited; simulate the next retry
    // window opening as a real reconnect would do.
    const key = Object.keys(localStorage).find(item => item.includes('dajin-order-processing-handover'))
    const rows = JSON.parse(localStorage.getItem(key))
    rows[0].nextRetryAt = new Date(Date.now() - 1).toISOString()
    localStorage.setItem(key, JSON.stringify(rows))
    await syncPendingProcessing({ knownOrders: [] })
    expect(listPendingHandovers()).toHaveLength(0)
  })

  it('回收/销售草稿走各自接口并出队', async () => {
    api.recycleCreate.mockResolvedValue({ recycleOrderId: 1 })
    api.createOrder.mockResolvedValue({ orderNo: 'XS001' })
    addDraft('recycle', { ref: makeDraftRef('recycle'), payload: { weight: 10, clientRequestId: 'r1' }, display: {} })
    addDraft('sale', { ref: makeDraftRef('sale'), payload: { clientRequestId: 's1' }, display: {} })

    const result = await syncPendingProcessing()

    expect(result.synced).toBe(2)
    expect(api.recycleCreate).toHaveBeenCalledOnce()
    expect(api.createOrder).toHaveBeenCalledOnce()
    expect(listDrafts('recycle')).toHaveLength(0)
    expect(listDrafts('sale')).toHaveLength(0)
  })

  it('重传带幂等键：服务端返回原单时草稿同样出队，不会重复建单', async () => {
    api.processingCreate.mockResolvedValue({ processing_order_id: 99, order_no: 'JG0099', idempotentReplay: true })
    api.processingHandover.mockResolvedValue({ handover: 1 })
    addProcessingDraft({ ref: 'p1', payload: { customerPhone: '13800000000', processingItemId: 5, clientRequestId: 'p1' }, display: {} })
    expect(listDrafts('processing')).toHaveLength(1)

    const result = await syncPendingProcessing()

    expect(result.synced).toBe(1)
    expect(api.processingCreate).toHaveBeenCalledWith(expect.objectContaining({ clientRequestId: 'p1' }))
    expect(api.processingHandover).toHaveBeenCalledWith(99)
    expect(listDrafts('processing')).toHaveLength(0)
  })

  it('还是不通时草稿留在队列里等下次', async () => {
    api.recycleCreate.mockRejectedValue(new Error('还是不通'))
    addDraft('recycle', { ref: 'r1', payload: {}, display: {} })

    const result = await syncPendingProcessing()

    expect(result.failed).toBe(1)
    expect(listDrafts('recycle')).toHaveLength(1)
    expect(listDrafts('recycle')[0]).toEqual(expect.objectContaining({ status: 'FAILED', error: '还是不通', lastRetryAt: expect.any(String), nextRetryAt: expect.any(String) }))
  })

  it('业务校验失败保留失败详情但不会在下一轮自动循环', async () => {
    api.recycleCreate.mockRejectedValue({ response: { status: 200, data: { code: 400711, message: '参数错误' } } })
    addDraft('recycle', { ref: 'business-error', payload: {}, display: {} })

    const first = await syncPendingProcessing()
    expect(first.failed).toBe(1)
    expect(listDrafts('recycle')[0]).toEqual(expect.objectContaining({ status: 'FAILED', error: '参数错误', nextRetryAt: null }))
    api.recycleCreate.mockClear()
    await syncPendingProcessing()
    expect(api.recycleCreate).not.toHaveBeenCalled()
  })

  it('待转交队列满时不丢失已创建订单的幂等草稿', async () => {
    api.processingCreate.mockResolvedValue({ processing_order_id: 999, order_no: 'JG0999' })
    api.processingHandover.mockRejectedValue(new Error('网络中断'))
    for (let i = 1; i <= 50; i += 1) addPendingHandover({ orderId: i, orderNo: `JG${i}` })
    addProcessingDraft({ ref: 'p-overflow', payload: { clientRequestId: 'p-overflow' }, display: {} })

    const result = await syncPendingProcessing()

    expect(result.failed).toBeGreaterThan(0)
    expect(listDrafts('processing')).toEqual(expect.arrayContaining([expect.objectContaining({ ref: 'p-overflow', status: 'FAILED' })]))
    expect(listPendingHandovers()).toHaveLength(50)
  })
})
