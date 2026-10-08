import { api } from '../api/request.js'
import {
  addPendingHandover, bumpDraft, listDrafts, listPendingHandovers,
  pendingSubmitSummary, removeDraft, removePendingHandover
} from './pendingProcessing.js'

// 补传是"把本地欠的账补到服务器"，多处都会触发（页面、全局心跳），用模块级锁避免并发重复提交
let syncing = false
export const isProcessingSyncBusy = () => syncing

// 三类开单各自的重传方式。三类请求都带 clientRequestId，服务端按幂等键去重，
// 所以"响应丢了"时重传也只会返回原单，不会重复建单（不依赖客户端猜时间/比克重）。
const SUBMITTERS = {
  processing: async draft => {
    const created = await api.processingCreate(draft.payload)
    if (created?.processing_order_id) {
      try { await api.processingHandover(created.processing_order_id) }
      catch { addPendingHandover({ orderId: created.processing_order_id, orderNo: created.order_no || '' }) }
    }
    return created
  },
  recycle: draft => api.recycleCreate(draft.payload),
  sale: draft => api.createOrder(draft.payload)
}

/**
 * 把「没提交成功的开单」和「已创建但没转交前台的单」补到服务器。
 * 返回 { synced, failed, processing, recycle, sale, handovers, busy }
 */
export async function syncPendingProcessing() {
  const draftsByKind = { processing: listDrafts('processing'), recycle: listDrafts('recycle'), sale: listDrafts('sale') }
  const handovers = listPendingHandovers()
  const total = draftsByKind.processing.length + draftsByKind.recycle.length + draftsByKind.sale.length + handovers.length
  if (!total) return { synced: 0, failed: 0, ...pendingSubmitSummary() }
  if (syncing) return { synced: 0, failed: 0, busy: true, ...pendingSubmitSummary() }
  syncing = true
  let synced = 0, failed = 0
  try {
    for (const kind of ['processing', 'recycle', 'sale']) {
      for (const draft of draftsByKind[kind]) {
        try {
          await SUBMITTERS[kind](draft)
          removeDraft(kind, draft.ref); synced += 1
        } catch { bumpDraft(kind, draft.ref); failed += 1 }
      }
    }
    for (const row of listPendingHandovers()) {
      try { await api.processingHandover(row.orderId); removePendingHandover(row.orderId); synced += 1 }
      catch { failed += 1 }
    }
  } finally { syncing = false }
  return { synced, failed, ...pendingSubmitSummary() }
}

