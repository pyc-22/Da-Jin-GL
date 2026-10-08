import { api } from '../api/request.js'
import {
  addPendingHandover, bumpProcessingDraft, findDuplicateOrder, listPendingHandovers,
  listProcessingDrafts, pendingProcessingSummary, removePendingHandover, removeProcessingDraft
} from './pendingProcessing.js'

// 补传是"把本地欠的账补到服务器"，多处都会触发（页面、全局心跳），用模块级锁避免并发重复提交
let syncing = false
export const isProcessingSyncBusy = () => syncing

/**
 * 把「没提交成功的开单」和「已创建但没转交前台的单」补到服务器。
 * knownOrders：调用方已经拉到的加工单列表（用于重复单判断）；没传就自己拉一次。
 * 返回 { synced, failed, drafts, handovers, busy }
 */
export async function syncPendingProcessing({ knownOrders = null } = {}) {
  const drafts = listProcessingDrafts()
  const handovers = listPendingHandovers()
  if (!drafts.length && !handovers.length) return { synced: 0, failed: 0, drafts: 0, handovers: 0 }
  if (syncing) return { synced: 0, failed: 0, drafts: drafts.length, handovers: handovers.length, busy: true }
  syncing = true
  let synced = 0, failed = 0
  try {
    let orders = Array.isArray(knownOrders) ? knownOrders : null
    if (drafts.length && orders === null) {
      try {
        const rows = await api.processingOrders()
        orders = Array.isArray(rows) ? rows : rows?.records || []
      } catch { orders = [] }
    }
    for (const draft of drafts) {
      // 服务器其实已经建好了（只是响应丢了）→ 清掉草稿，绝不重复建单
      if (orders && findDuplicateOrder(draft, orders)) { removeProcessingDraft(draft.ref); synced += 1; continue }
      try {
        const created = await api.processingCreate(draft.payload)
        removeProcessingDraft(draft.ref); synced += 1
        if (created?.processing_order_id) {
          try { await api.processingHandover(created.processing_order_id) }
          catch { addPendingHandover({ orderId: created.processing_order_id, orderNo: created.order_no || '' }) }
        }
      } catch { bumpProcessingDraft(draft.ref); failed += 1 }
    }
    for (const row of listPendingHandovers()) {
      try { await api.processingHandover(row.orderId); removePendingHandover(row.orderId); synced += 1 }
      catch { failed += 1 }
    }
  } finally { syncing = false }
  const summary = pendingProcessingSummary()
  return { synced, failed, drafts: summary.drafts, handovers: summary.handovers }
}
