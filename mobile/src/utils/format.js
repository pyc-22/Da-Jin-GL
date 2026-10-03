export function maskPhone(phone) {
  const s = String(phone || '')
  return s.length === 11 ? s.replace(/(\d{3})\d{4}(\d{4})/, '$1****$2') : s
}

export function formatTags(tags) {
  if (!tags) return '普通'
  try {
    const v = JSON.parse(tags)
    if (Array.isArray(v)) return v.length ? v.join('、') : '普通'
  } catch {}
  return String(tags)
}

const APPROVAL_REASON_LABELS = Object.freeze({
  DISCOUNT: '折扣低于审批阈值',
  SALE_PAYMENT_DISCOUNT: '成品优惠收款低于85折',
  PROCESSING_PAYMENT_DISCOUNT: '加工优惠收款低于85折',
  REFUND: '退货退款申请',
  RECYCLE: '大额回收审批申请',
  STOCK_CHECK: '库存盘点差异审批',
  TRADE_IN: '以旧换新审批申请',
  STOCK_OUT: '手动出库审批',
  MEMBER_CLAIM: '会员认领申请'
})

export function formatApprovalReason(record = {}) {
  const source = record || {}
  const reason = String(source.reason ?? '').trim()
  const fallback = APPROVAL_REASON_LABELS[String(source.type ?? '').toUpperCase()] || '业务审批申请'
  if (!reason) return fallback
  if (/^[{[]/.test(reason)) {
    try {
      const data = JSON.parse(reason)
      if (data.originalDue != null && data.actualPaid != null) return `原应收 ¥${Number(data.originalDue).toFixed(2)}，实收 ¥${Number(data.actualPaid).toFixed(2)}，优惠 ¥${Number(data.discount || 0).toFixed(2)}`
    } catch {}
    return fallback
  }
  if (!/[\u3400-\u9fff]/.test(reason)) return fallback
  return reason
}
