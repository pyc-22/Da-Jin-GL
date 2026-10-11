import { getStorage, setStorage } from './storage.js'

// 键名用 dajin-order- 前缀 → storage.js 会按"门店:账号"隔离，同一台手机换账号不会串单
const DRAFT_KEY = 'dajin-order-processing-drafts'      // 加工开单没提交到服务器（断网/服务异常）
const RECYCLE_DRAFT_KEY = 'dajin-order-recycle-drafts' // 回收开单没提交成功
const SALE_DRAFT_KEY = 'dajin-order-sale-drafts'       // 销售开单没提交成功
const HANDOVER_KEY = 'dajin-order-processing-handover' // 已创建成功，但"转交前台"没成功
const MAX_ROWS = 50

// 各类"开单"的统一键位（都带幂等键重传，服务端不会重复建单）
export const DRAFT_KINDS = Object.freeze({ processing: DRAFT_KEY, recycle: RECYCLE_DRAFT_KEY, sale: SALE_DRAFT_KEY })

const readList = key => {
  try {
    const rows = JSON.parse(getStorage(key, '[]') || '[]')
    return Array.isArray(rows) ? rows : []
  } catch { return [] }
}
const writeList = (key, rows) => setStorage(key, JSON.stringify(rows))

export function canAddDraft(kind = 'processing', ref = '') {
  const key = DRAFT_KINDS[kind] || DRAFT_KEY
  const rows = readList(key)
  return rows.some(row => row.ref === ref) || rows.length < MAX_ROWS
}

export const makeProcessingRef = () => `proc-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
export const makeDraftRef = kind => `${kind}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`

/**
 * 这次提交失败还能靠重试救回来吗？
 * 业务校验类错误（成色不合法、未配置回收价等 4xxxx / 4xx）重试也不会成功，不该进待同步队列；
 * 网络中断、超时、5xx 才存草稿等联网补传。
 */
export function isRetryableSubmitError(error) {
  const status = Number(error?.response?.status || 0)
  const code = Number(error?.response?.data?.code || 0)
  // The API uses HTTP 200 for business failures, with 400xxx/409xxx in the
  // response body.  Do not put those deterministic validation/state errors
  // into an automatic retry loop.  Transport errors and 5xx remain retryable.
  if (code >= 400000 && code < 500000) return false
  if (status >= 400 && status < 500) return false
  return true
}

// 通用草稿操作（kind: processing / recycle / sale）
export function listDrafts(kind = 'processing') { return readList(DRAFT_KINDS[kind] || DRAFT_KEY) }

export function addDraft(kind, draft) {
  const key = DRAFT_KINDS[kind] || DRAFT_KEY
  const rows = readList(key).filter(row => row.ref !== draft.ref)
  if (rows.length >= MAX_ROWS) throw new Error('待同步草稿已达上限，请先处理或删除已有草稿')
  rows.push({ ...draft, createdAt: draft.createdAt || new Date().toISOString(), tries: Number(draft.tries || 0) })
  writeList(key, rows)
  return rows.length
}

export function removeDraft(kind, ref) {
  const key = DRAFT_KINDS[kind] || DRAFT_KEY
  const rows = readList(key).filter(row => row.ref !== ref)
  writeList(key, rows)
  return rows.length
}

export function bumpDraft(kind, ref, errorMessage = '') {
  markDraftFailed(kind, ref, errorMessage, true)
}

/** Keep the row visible after a deterministic business error, but do not
 * schedule it for automatic retries.  A user may still invoke manual retry. */
export function markDraftFailed(kind, ref, errorMessage = '', retryable = true) {
  const key = DRAFT_KINDS[kind] || DRAFT_KEY
  writeList(key, readList(key).map(row => {
    if (row.ref !== ref) return row
    const next = { ...row, tries: Number(row.tries || 0) + 1, status: 'FAILED', error: errorMessage || row.error || '同步失败', lastRetryAt: new Date().toISOString() }
    next.nextRetryAt = retryable
      ? new Date(Date.now() + Math.min(3600000, 1000 * (2 ** Math.min(Number(row.tries || 0), 10)))).toISOString()
      : null
    return next
  }))
}

export function pendingSubmitSummary() {
  const processing = readList(DRAFT_KEY).length
  const recycle = readList(RECYCLE_DRAFT_KEY).length
  const sale = readList(SALE_DRAFT_KEY).length
  const handovers = readList(HANDOVER_KEY).length
  return { processing, recycle, sale, handovers, total: processing + recycle + sale + handovers }
}

export function listProcessingDrafts() { return readList(DRAFT_KEY) }

export function addProcessingDraft(draft) {
  const rows = readList(DRAFT_KEY).filter(row => row.ref !== draft.ref)
  if (rows.length >= MAX_ROWS) throw new Error('待同步草稿已达上限，请先处理或删除已有草稿')
  rows.push({ ...draft, createdAt: draft.createdAt || new Date().toISOString(), tries: Number(draft.tries || 0) })
  writeList(DRAFT_KEY, rows)
  return rows.length
}

export function removeProcessingDraft(ref) {
  const rows = readList(DRAFT_KEY).filter(row => row.ref !== ref)
  writeList(DRAFT_KEY, rows)
  return rows.length
}

export function bumpProcessingDraft(ref) {
  const rows = readList(DRAFT_KEY).map(row => row.ref === ref ? { ...row, tries: Number(row.tries || 0) + 1 } : row)
  writeList(DRAFT_KEY, rows)
}

export function listPendingHandovers() { return readList(HANDOVER_KEY) }

export function addPendingHandover(entry) {
  const rows = readList(HANDOVER_KEY).filter(row => Number(row.orderId) !== Number(entry.orderId))
  if (rows.length >= MAX_ROWS) throw new Error('待转交队列已达上限，请先处理或删除已有记录')
  rows.push({ ...entry, createdAt: entry.createdAt || new Date().toISOString(), tries: Number(entry.tries || 0), status: entry.status || 'PENDING' })
  writeList(HANDOVER_KEY, rows)
  return rows.length
}

export function bumpPendingHandover(orderId, errorMessage = '') {
  markPendingHandoverFailed(orderId, errorMessage, true)
}

export function markPendingHandoverFailed(orderId, errorMessage = '', retryable = true) {
  writeList(HANDOVER_KEY, readList(HANDOVER_KEY).map(row => {
    if (Number(row.orderId) !== Number(orderId)) return row
    const next = { ...row, tries: Number(row.tries || 0) + 1, status: 'FAILED', error: errorMessage || row.error || '转交前台失败', lastRetryAt: new Date().toISOString() }
    next.nextRetryAt = retryable
      ? new Date(Date.now() + Math.min(3600000, 1000 * (2 ** Math.min(Number(row.tries || 0), 10)))).toISOString()
      : null
    return next
  }))
}

export function removePendingHandover(orderId) {
  const rows = readList(HANDOVER_KEY).filter(row => Number(row.orderId) !== Number(orderId))
  writeList(HANDOVER_KEY, rows)
  return rows.length
}

export function pendingProcessingSummary() {
  const drafts = readList(DRAFT_KEY).length
  const handovers = readList(HANDOVER_KEY).length
  return { drafts, handovers, total: drafts + handovers }
}

export function buildProcessingDraftPayload(form, item, clientRequestId = '') {
  const items = Array.isArray(form.items) && form.items.length
    ? form.items.map(piece => ({ processingItemId: Number(piece.processingItemId), quantity: 1 }))
    : Array.from({ length: Number(form.quantity) }, () => ({ processingItemId: Number(form.itemId), quantity: 1 }))
  return {
    customerName: form.customerName,
    customerPhone: form.customerPhone,
    processingItemId: Number(form.itemId),
    quantity: Number(form.quantity),
    items,
    billingWeight: null,
    ...(clientRequestId ? { clientRequestId } : {}),
    ...(form.memberId ? { memberId: Number(form.memberId) } : {}),
    ...(form.craftsmanId ? { craftsmanId: Number(form.craftsmanId) } : {}),
    ...(form.pickupDate ? { pickupDate: form.pickupDate } : {}),
    oldGoldWeight: Number(form.oldGoldWeight),
    oldGoldFineness: Number(form.oldGoldFineness),
    ...(form.remark ? { remark: form.remark } : {})
  }
}

/**
 * 已经存在同样的单子吗？仅用于诊断/人工排查；补传不再依赖它。
 * （服务端已按 clientRequestId 幂等，客户端再做时间比较会因时区差异误判，见 processingSync.js）
 */
export function findDuplicateOrder(draft, orders) {
  const payload = draft?.payload || {}
  const phone = String(payload.customerPhone || '').trim()
  if (!phone) return null
  const itemId = Number(payload.processingItemId || 0)
  const rows = Array.isArray(orders) ? orders : []
  return rows.find(row => {
    if (String(row.customer_phone || '').trim() !== phone) return false
    return !itemId || Number(row.processing_item_id || row.item_id || 0) === itemId
  }) || null
}
