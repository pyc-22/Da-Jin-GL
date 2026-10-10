export function formatPrintTime(value = new Date()) {
  if (typeof value === 'string') {
    const text = value.trim().replace('T', ' ')
    if (/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}/.test(text)) return text.slice(0, 16)
  }
  const date = value instanceof Date ? value : new Date(value)
  if (Number.isNaN(date.getTime())) return '-'
  const pad = n => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

export function buildReceiptPreview(model) {
  const width = model.paperWidth === 58 ? 32 : 48
  const lines = [model.storeName || '打金店', '销售小票', `单号: ${model.billNo || '-'}`, `时间: ${formatPrintTime(model.printTime)}`, '-'.repeat(width)]
  ;(model.items || []).forEach(i => lines.push(`${i.name} x${i.qty || 1}`, `  ${i.detail || ''}  ¥${Number(i.amount || 0).toFixed(2)}`))
  lines.push('-'.repeat(width), `合计: ¥${Number(model.total || 0).toFixed(2)}`)
  if (Number(model.oldMaterialValue || 0) > 0) lines.push(`旧金估值: ¥${Number(model.oldMaterialValue).toFixed(2)}`)
  lines.push(`本单抵扣: -¥${Number(model.deduct || 0).toFixed(2)}`)
  if (Number(model.excessPayout || 0) > 0) lines.push(`回收返款: ¥${Number(model.excessPayout).toFixed(2)} (${model.payoutMethod || '-'})`)
  lines.push(`原应收: ¥${Number(model.originalDue ?? model.payable ?? 0).toFixed(2)}`)
  if (Number(model.settlementDiscount || 0) > 0) lines.push(`优惠: -¥${Number(model.settlementDiscount).toFixed(2)}`)
  lines.push(`应收: ¥${Number(model.payable || 0).toFixed(2)}`, `实收: ¥${Number(model.actualPaid ?? model.payable ?? 0).toFixed(2)}`)
  if (Number(model.remaining || 0) > 0) lines.push(`待收: ¥${Number(model.remaining).toFixed(2)}`)
  lines.push(`支付: ${model.payMethod || '-'}`, '', '谢谢惠顾')
  return lines.join('\n')
}
