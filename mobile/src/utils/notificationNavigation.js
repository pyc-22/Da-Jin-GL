const textOf = row => [row?.action, row?.type, row?.title, row?.content, row?.text, row?.message].filter(Boolean).join(' ').toUpperCase()
const firstId = (row, keys) => keys.map(key => row?.[key] ?? row?.data?.[key] ?? row?.payload?.[key]).find(value => value != null && value !== '')

/**
 * 列表点击与 Android 通知点击共用此解析器，避免两套跳转规则漂移。
 * 后端通知字段会随业务来源变化，优先使用明确业务 ID，再用 action/type 判断模块首页。
 */
export function resolveNotificationTarget(row) {
  const text = textOf(row)
  const approvalId = firstId(row, ['approval_id', 'approvalId', 'approvalID'])
  if (approvalId != null || /APPROVAL|审批/.test(text)) {
    return approvalId != null
      ? { name: 'approval', path: '/manager/approval-center', query: { approvalId: String(approvalId) } }
      : { name: 'approval', path: '/manager/approval-center' }
  }
  const processingId = firstId(row, ['processing_order_id', 'processingOrderId', 'processing_id', 'processingId', 'order_id'])
  if (processingId != null || /PROCESSING|加工|取货|损耗/.test(text)) {
    return processingId != null
      ? { name: 'processing', path: '/processing', query: { id: String(processingId) } }
      : { name: 'processing', path: '/processing' }
  }
  const goodsId = firstId(row, ['goods_id', 'goodsId', 'stock_id', 'stockId'])
  if (goodsId != null || /STOCK|INVENTORY|库存|盘点|入库|出库/.test(text)) {
    return goodsId != null
      ? { name: 'inventory', path: '/inventory', query: { goodsId: String(goodsId) } }
      : { name: 'inventory', path: '/inventory' }
  }
  if (/VISIT|FOLLOW|回访/.test(text)) return { name: 'visits', path: '/visits' }
  if (/BIRTHDAY|生日/.test(text)) return { name: 'birthday', path: '/sales/birthday' }
  return { name: 'notifications', path: '/notifications', hash: row?.notification_id || row?.id || row?.log_id ? `#notification-${row.notification_id ?? row.id ?? row.log_id}` : undefined }
}

export function navigateNotification(router, row) {
  const target = resolveNotificationTarget(row)
  return router.push({ path: target.path, ...(target.query ? { query: target.query } : {}), ...(target.hash ? { hash: target.hash } : {}) })
}
