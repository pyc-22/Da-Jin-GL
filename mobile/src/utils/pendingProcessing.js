import { getStorage, setStorage } from './storage.js'

// 键名用 dajin-order- 前缀 → storage.js 会按"门店:账号"隔离，同一台手机换账号不会串单
const DRAFT_KEY = 'dajin-order-processing-drafts'      // 开单没提交到服务器（断网/服务异常）
const HANDOVER_KEY = 'dajin-order-processing-handover' // 已创建成功，但"转交前台"没成功
const MAX_ROWS = 50

const readList = key => {
  try {
    const rows = JSON.parse(getStorage(key, '[]') || '[]')
    return Array.isArray(rows) ? rows : []
  } catch { return [] }
}
const writeList = (key, rows) => setStorage(key, JSON.stringify(rows.slice(0, MAX_ROWS)))

export const makeProcessingRef = () => `proc-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`

export function listProcessingDrafts() { return readList(DRAFT_KEY) }

export function addProcessingDraft(draft) {
  const rows = readList(DRAFT_KEY).filter(row => row.ref !== draft.ref)
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
  rows.push({ ...entry, createdAt: entry.createdAt || new Date().toISOString() })
  writeList(HANDOVER_KEY, rows)
  return rows.length
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

export function buildProcessingDraftPayload(form, item) {
  return {
    customerName: form.customerName,
    customerPhone: form.customerPhone,
    processingItemId: Number(form.itemId),
    quantity: Number(form.quantity),
    billingWeight: null,
    ...(form.memberId ? { memberId: Number(form.memberId) } : {}),
    ...(form.craftsmanId ? { craftsmanId: Number(form.craftsmanId) } : {}),
    ...(form.pickupDate ? { pickupDate: form.pickupDate } : {}),
    oldGoldWeight: Number(form.oldGoldWeight),
    oldGoldFineness: Number(form.oldGoldFineness),
    ...(form.remark ? { remark: form.remark } : {})
  }
}

/**
 * 已经存在同样的单子吗？（同门店 + 同客户手机 + 同项目，且是最近 thisWithinMinutes 分钟内创建的）
 * 用来避免"服务器其实已经创建成功、但响应在路上丢了"时草稿补传造成重复单。
 */
export function findDuplicateOrder(draft, orders, withinMinutes = 30) {
  const payload = draft?.payload || {}
  const phone = String(payload.customerPhone || '').trim()
  if (!phone) return null
  const itemId = Number(payload.processingItemId || 0)
  const cutoff = Date.now() - Math.max(1, Number(withinMinutes) || 30) * 60000
  const rows = Array.isArray(orders) ? orders : []
  return rows.find(row => {
    if (String(row.customer_phone || '').trim() !== phone) return false
    if (itemId && Number(row.processing_item_id || row.item_id || 0) !== itemId) return false
    const at = Date.parse(String(row.create_time || '').replace(' ', 'T'))
    return !Number.isFinite(at) || at >= cutoff
  }) || null
}
