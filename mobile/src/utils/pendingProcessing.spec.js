// @vitest-environment jsdom
import { beforeEach, describe, expect, it } from 'vitest'
import {
  addPendingHandover, addProcessingDraft, buildProcessingDraftPayload, bumpProcessingDraft,
  findDuplicateOrder, listPendingHandovers, listProcessingDrafts, makeProcessingRef,
  pendingProcessingSummary, removePendingHandover, removeProcessingDraft
} from './pendingProcessing.js'

const draft = (ref, phone = '13800000000', itemId = 5) => ({
  ref, payload: { customerPhone: phone, processingItemId: itemId }, display: { itemName: '戒指加工' }
})

describe('mobile pending processing queue', () => {
  beforeEach(() => {
    localStorage.clear()
    localStorage.setItem('dajin-user', JSON.stringify({ user_id: 9, store_id: 1 }))
  })

  it('keeps failed order submissions as drafts and dedupes by ref', () => {
    const ref = makeProcessingRef()
    addProcessingDraft({ ...draft(ref), payload: { ...draft(ref).payload } })
    addProcessingDraft({ ...draft(ref), tries: 3 })
    const rows = listProcessingDrafts()
    expect(rows).toHaveLength(1)
    expect(rows[0].tries).toBe(3)
    expect(pendingProcessingSummary()).toEqual({ drafts: 1, handovers: 0, total: 1 })
    bumpProcessingDraft(ref)
    expect(listProcessingDrafts()[0].tries).toBe(4)
    removeProcessingDraft(ref)
    expect(listProcessingDrafts()).toHaveLength(0)
  })

  it('tracks orders created on the server but not yet handed to the cashier', () => {
    addPendingHandover({ orderId: 12, orderNo: 'JG0012' })
    addPendingHandover({ orderId: 12, orderNo: 'JG0012' })
    addPendingHandover({ orderId: 13, orderNo: 'JG0013' })
    expect(listPendingHandovers().map(row => row.orderId)).toEqual([12, 13])
    expect(pendingProcessingSummary()).toEqual({ drafts: 0, handovers: 2, total: 2 })
    removePendingHandover(12)
    expect(listPendingHandovers().map(row => row.orderId)).toEqual([13])
  })

  it('builds the payload the same way the create form does', () => {
    const payload = buildProcessingDraftPayload({
      customerName: '张三', customerPhone: '13800000000', itemId: '5', quantity: '2',
      oldGoldWeight: '20', oldGoldFineness: '0.999', craftsmanId: 21, remark: '刻字'
    }, { name: '戒指加工' })
    expect(payload).toMatchObject({
      customerName: '张三', customerPhone: '13800000000', processingItemId: 5, quantity: 2,
      billingWeight: null, craftsmanId: 21, oldGoldWeight: 20, oldGoldFineness: 0.999, remark: '刻字'
    })
  })

  it('tells whether an identical order already exists (manual diagnostics only)', () => {
    const rows = [{ processing_order_id: 99, customer_phone: '13800000000', processing_item_id: 5 }]
    expect(findDuplicateOrder(draft('proc-1'), rows)).toMatchObject({ processing_order_id: 99 })
    expect(findDuplicateOrder(draft('proc-1', '13900000000'), rows)).toBeNull()
    expect(findDuplicateOrder(draft('proc-1', '13800000000', 6), rows)).toBeNull()
  })
})
