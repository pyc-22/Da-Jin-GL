import { api } from '../api/request.js'
import {
  addPendingHandover, isRetryableSubmitError, markDraftFailed, markPendingHandoverFailed, listDrafts, listPendingHandovers,
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
    let handoverFailed = false
    let handoverQueued = false
    if (created?.processing_order_id) {
      try { await api.processingHandover(created.processing_order_id) }
      catch (error) {
        handoverFailed = true
        // Keep the already-created order safe when the local handover queue
        // is full. The original draft remains available for an idempotent
        // retry, but it must not be treated as a failed create operation.
        try {
          const message = error?.response?.data?.message || error?.message || '转交前台失败'
          addPendingHandover({ orderId: created.processing_order_id, orderNo: created.order_no || '', status: 'FAILED', error: message })
          markPendingHandoverFailed(created.processing_order_id, message, isRetryableSubmitError(error))
          handoverQueued = true
        } catch (queueError) {
          handoverFailed = true
        }
      }
    }
    return handoverFailed ? { ...created, handoverFailed: true, handoverQueued } : created
  },
  recycle: draft => api.recycleCreate(draft.payload),
  sale: draft => api.createOrder(draft.payload)
}

/** Retry one visible draft from the pending list without touching other rows. */
export async function retryPendingDraft(kind, ref) {
  const draft = listDrafts(kind).find(row => row.ref === ref)
  if (!draft) return { ok: false, missing: true }
  if (syncing) return { ok: false, busy: true }
  syncing = true
  try {
    const result = await SUBMITTERS[kind](draft)
    if (result?.handoverFailed && !result?.handoverQueued) {
      markDraftFailed(kind, ref, '加工单已创建，但待转交队列已满，请稍后重试', true)
      return { ok: false, error: new Error('加工单已创建，但待转交队列已满，请稍后重试'), result }
    }
    removeDraft(kind, ref)
    return { ok: !(result?.handoverFailed && !result?.handoverQueued), result }
  } catch (error) {
    markDraftFailed(kind, ref, error?.response?.data?.message || error?.message || '同步失败', isRetryableSubmitError(error))
    return { ok: false, error }
  } finally { syncing = false }
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
          if ((draft.status === 'FAILED' && !draft.nextRetryAt) || (draft.nextRetryAt && Date.parse(draft.nextRetryAt) > Date.now())) { failed += 1; continue }
          const result = await SUBMITTERS[kind](draft)
          if (result?.handoverFailed && !result?.handoverQueued) {
            markDraftFailed(kind, draft.ref, '加工单已创建，但待转交队列已满，请稍后重试', true)
            failed += 1
          } else {
            removeDraft(kind, draft.ref)
            synced += 1
          }
          if (result?.handoverFailed) failed += 1
        } catch (error) {
          markDraftFailed(kind, draft.ref, error?.response?.data?.message || error?.message || '同步失败', isRetryableSubmitError(error))
          failed += 1
        }
      }
    }
    // Use the snapshot captured before draft retries so a handover queued by
    // the current pass is not retried twice in the same cycle.
    for (const row of handovers) {
      try {
        if ((row.status === 'FAILED' && !row.nextRetryAt) || (row.nextRetryAt && Date.parse(row.nextRetryAt) > Date.now())) { failed += 1; continue }
        await api.processingHandover(row.orderId); removePendingHandover(row.orderId); synced += 1
      } catch (error) {
        markPendingHandoverFailed(row.orderId, error?.response?.data?.message || error?.message || '转交前台失败', isRetryableSubmitError(error))
        failed += 1
      }
    }
  } finally { syncing = false }
  return { synced, failed, ...pendingSubmitSummary() }
}

