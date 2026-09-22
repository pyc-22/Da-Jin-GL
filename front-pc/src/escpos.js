export function formatPrintTime(value = new Date()) {
  const date = value instanceof Date ? value : new Date(value)
  if (Number.isNaN(date.getTime())) return '-'
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23'
  }).formatToParts(date).reduce((result, part) => ({ ...result, [part.type]: part.value }), {})
  return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}:${parts.second}`
}

export function buildReceiptPreview(model) {
  const width = model.paperWidth === 58 ? 32 : 48
  const lines = [model.storeName || '打金店', '销售小票', `单号: ${model.billNo || '-'}`, `时间: ${formatPrintTime(model.printTime)}`, '-'.repeat(width)]
  ;(model.items || []).forEach(i => lines.push(`${i.name} x${i.qty || 1}`, `  ${i.detail || ''}  ¥${Number(i.amount || 0).toFixed(2)}`))
  lines.push('-'.repeat(width), `合计: ¥${Number(model.total || 0).toFixed(2)}`)
  if (Number(model.oldMaterialValue || 0) > 0) lines.push(`旧金估值: ¥${Number(model.oldMaterialValue).toFixed(2)}`)
  lines.push(`本单抵扣: -¥${Number(model.deduct || 0).toFixed(2)}`)
  if (Number(model.excessPayout || 0) > 0) lines.push(`回收返款: ¥${Number(model.excessPayout).toFixed(2)} (${model.payoutMethod || '-'})`)
  lines.push(`应收: ¥${Number(model.payable || 0).toFixed(2)}`, `支付: ${model.payMethod || '-'}`, '', '谢谢惠顾')
  return lines.join('\n')
}
