import { api } from '../api/request.js'
import {
  addPendingHandover, bumpDraft, findDuplicateOrder, listDrafts, listPendingHandovers,
  pendingSubmitSummary, removeDraft, removePendingHandover
} from './pendingProcessing.js'

// 补传是"把本地欠的账补到服务器"，多处都会触发（页面、全局心跳），用模块级锁避免并发重复提交
let syncing = false
export const isProcessingSyncBusy = () => syncing

// 三类开单各自的提交方式：加工单建完还要转交前台；回收/销售单服务端有幂等键，重传不会重复建单
const SUBMITTERS = {
  processing: draft => api.processingCreate(draft.payload).then(async created => {
    if (created?.processing_order_id) {
      try { await api.processingHandover(created.processing_order_id) }
      catch { addPendingHandover({ orderId: created.processing_order_id, orderNo: created.order_no || '' }) }
    }
    return created
  }),
  recycle: draft => api.recycleCreate(draft.payload),
  sale: draft => api.createOrder(draft.payload)
}

/**
 * 把「没提交成功的开单」和「已创建但没转交前台的单」补到服务器。
 * knownOrders：调用方已经拉到的加工单列表（用于重复单判断）；没传就自己拉一次。
 * 返回 { synced, failed, processing, recycle, sale, handovers, busy }
 */
export async function syncPendingProcessing({ knownOrders = null } = {}) {
  const processingDrafts = listDrafts('processing')
  const recycleDrafts = listDrafts('recycle')
  const saleDrafts = listDrafts('sale')
  const handovers = listPendingHandovers()
  if (!processingDrafts.length && !recycleDrafts.length && !saleDrafts.length && !handovers.length) {
    return { synced: 0, failed: 0, ...pendingSubmitSummary() }
  }
  if (syncing) return { synced: 0, failed: 0, busy: true, ...pendingSubmitSummary() }
  syncing = true
  let synced = 0, failed = 0
  try {
    let orders = Array.isArray(knownOrders) ? knownOrders : null
    if (processingDrafts.length && orders === null) {
      try {
        const rows = await api.processingOrders()
        orders = Array.isArray(rows) ? rows : rows?.records || []
      } catch { orders = [] }
    }
    for (const [kind, drafts] of [['processing', processingDrafts], ['recycle', recycleDrafts], ['sale', saleDrafts]]) {
      for (const draft of drafts) {
        // 加工单没有幂等键（历史数据）：服务器其实已经建好了就清草稿，绝不重复建单
        if (kind === 'processing' && orders && findDuplicateOrder(draft, orders)) { removeDraft(kind, draft.ref); synced += 1; continue }
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

