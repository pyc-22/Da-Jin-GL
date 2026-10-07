const rowsOf = data => Array.isArray(data) ? data : data?.records || []
const numberOrNull = value => value == null || value === '' || !Number.isFinite(Number(value)) ? null : Number(value)
export function mergeDocuments(sales, processing, recycle) {
  // Sales reports may repeat order-level payment amounts for multiple goods rows.
  const grouped = new Map()
  rowsOf(sales).forEach((row, index) => {
    const id = row.order_id ?? row.order_no ?? 'row-' + index
    if (!grouped.has(id)) grouped.set(id, { ...row, lines: [] })
    grouped.get(id).lines.push(row)
  })
  const saleDocuments = [...grouped.entries()].map(([id, row]) => ({
    key: 'sale-' + id, type: 'sale', typeLabel: '销售', id: row.order_id, no: row.order_no || String(id),
    title: [...new Set(row.lines.map(line => line.goods_name).filter(Boolean))].join('、') || '销售单',
    amount: numberOrNull(row.actual_paid ?? row.discounted_amount ?? row.amount),
    amountLabel: row.actual_paid != null ? '实收' : '金额', time: row.date || row.create_time || '',
    customer: row.member_name || row.customer_name || '散客', salesperson: row.employee_name || '',
    status: row.remaining_amount != null ? (Number(row.remaining_amount) > 0 ? '待收尾款' : '已结清') : '状态待同步',
    tone: row.remaining_amount == null ? 'muted' : Number(row.remaining_amount) > 0 ? 'err' : 'ok', raw: row
  }))
  // 已取货的单允许柜面在手机端手动“删除”：只置 mobile_archived 标记并从手机列表隐藏，
  // 管理端列表与账务/库存/提成记录不受影响，所以这里过滤掉已隐藏的行。
  const processingDocuments = rowsOf(processing).filter(row => Number(row.mobile_archived) !== 1).map(row => ({
    key: 'processing-' + row.processing_order_id, type: 'processing', typeLabel: '加工', id: row.processing_order_id,
    no: row.order_no, title: row.item_name_snapshot || '加工单', amount: numberOrNull(row.due_amount), amountLabel: '应收',
    time: row.create_time || '', customer: row.customer_name || row.member_name || '散客',
    status: ({ PENDING: Number(row.handover) === 1 ? '已转交前台' : '待加工', PROCESSING: '加工中', COMPLETED: '待取货', PICKED_UP: '已取货' })[row.status] || '状态待同步',
    tone: row.status === 'PICKED_UP' ? 'ok' : 'gold', raw: row
  }))
  const recycleDocuments = rowsOf(recycle).map(row => ({
    key: 'recycle-' + row.recycle_order_id, type: 'recycle', typeLabel: '回收', id: row.recycle_order_id,
    no: row.bill_no, title: row.material_type || '旧料回收', amount: numberOrNull(row.total_amount), amountLabel: '回收金额',
    time: row.create_time || '', customer: row.customer_name || row.member_name || '散客',
    status: row.status == null ? '回收记录' : ({ PENDING: '待处理', PAID: '已付款', COMPLETED: '已完成', REJECTED: '已驳回' }[String(row.status)] || '状态 ' + row.status),
    tone: ['PAID', 'COMPLETED'].includes(row.status) ? 'ok' : 'muted', raw: row
  }))
  return [...saleDocuments, ...processingDocuments, ...recycleDocuments].sort((a,b) => String(b.time).localeCompare(String(a.time)) || a.key.localeCompare(b.key))
}
