import { getStorage, setStorage } from '../utils/storage.js'
// @vitest-environment jsdom
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAppStore } from './app.js'

const mocks = vi.hoisted(() => ({ createInbound: vi.fn(), uploadImage: vi.fn() }))
vi.mock('../api/request.js', () => ({ api: {
  createInbound: mocks.createInbound,
  pendingInbounds: vi.fn().mockResolvedValue([])
} }))
vi.mock('../api/upload.js', () => ({ uploadImage: mocks.uploadImage }))
vi.mock('./auth.js', () => ({ useAuthStore: () => ({ role: 'MANAGER' }) }))

const clientRequestId = 'offline-inbound-001'
const payload = {
  inboundType: 'profit', storeId: 1, clientRequestId,
  items: [{ name: '离线货品', barcode: 'OFF-1', quantity: 1, goldWeight: 1, costPrice: 100, labelPrice: 200, images: [], pieceImages: [] }]
}

describe('pending inbound synchronization', () => {
  beforeEach(() => {
    localStorage.clear(); localStorage.setItem('dajin-user', JSON.stringify({store_id:1,user_id:7}))
    setActivePinia(createPinia())
    mocks.createInbound.mockReset()
    setStorage('dajin-inbound-queue', JSON.stringify([payload]))
    setStorage('dajin-inbound-history', JSON.stringify([{ ...payload, status: 'PENDING', createdAt: Date.now() }]))
  })

  it('keeps a failed item for retry and removes it after one successful synchronization', async () => {
    mocks.createInbound.mockRejectedValueOnce(new Error('临时网络错误')).mockResolvedValueOnce({ inbound_id: 99, status: 'COMPLETED' })
    const store = useAppStore()

    await expect(store.syncPendingInbound(clientRequestId)).rejects.toThrow('临时网络错误')
    expect(JSON.parse(getStorage('dajin-inbound-queue'))).toHaveLength(1)
    expect(JSON.parse(getStorage('dajin-inbound-history'))[0].status).toBe('FAILED')

    await store.syncPendingInbound(clientRequestId)
    expect(mocks.createInbound).toHaveBeenCalledTimes(2)
    expect(JSON.parse(getStorage('dajin-inbound-queue'))).toHaveLength(0)
    expect(JSON.parse(getStorage('dajin-inbound-history'))[0].status).toBe('COMPLETED')

    await expect(store.syncPendingInbound(clientRequestId)).rejects.toThrow('未找到该入库单的待同步数据')
    expect(mocks.createInbound).toHaveBeenCalledTimes(2)
  })
  it('does not upload an existing server photo again while replaying a pending voucher', async () => {
    setStorage('dajin-inbound-queue', JSON.stringify([{ ...payload, items: [{ ...payload.items[0], images: ['/api/file/goods/photo.jpg'], pieceImages: ['/api/file/goods/photo.jpg'] }] }]))
    mocks.createInbound.mockResolvedValue({ inbound_id: 100 })
    const store = useAppStore()
    await store.syncPendingInbound(clientRequestId)
    expect(mocks.uploadImage).not.toHaveBeenCalled()
    expect(mocks.createInbound.mock.calls[0][0].items[0].images).toEqual(['/api/file/goods/photo.jpg'])
  })
})
