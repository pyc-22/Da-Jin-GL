const rules = new Set(['NONE', 'TENTH', 'YUAN', 'TAIL_8', 'TAIL_9'])
const numeric = value => value !== null && value !== undefined && value !== '' && Number.isFinite(Number(value))

export const hasQuote = value => numeric(value) && Number(value) > 0
export const quoteMoney = value => hasQuote(value) ? `¥${Number(value).toFixed(2)}` : '—'

// 甲方口径：成色 0.995 及以上（足金线）视作 1，按整克不折；低于才按含金量折算
export const finenessFactor = value => {
  const f = Number(value)
  if (!Number.isFinite(f) || f <= 0) return 1
  return f >= 0.995 ? 1 : f
}

// 旧金折抵 = 克重 × 成色因子(足金线规则) × 单价。三端所有"旧金折抵/回收金额"都必须走这里，
// 避免各页面自己写 weight*成色*单价 而漏掉足金线规则。
export const oldGoldDeduction = (weight, purity, price) =>
  Number(weight || 0) * finenessFactor(purity) * Number(price || 0)

function round(value, rule) {
  if (rule === 'TAIL_8') return Math.floor(value) + 0.8
  if (rule === 'TAIL_9') return Math.floor(value) + 0.9
  const scale = rule === 'YUAN' ? 1 : rule === 'TENTH' ? 10 : 100
  return Math.round((value + Number.EPSILON) * scale) / scale
}

export function pricingPreview(row, recycle = false) {
  if (row.pricingMode !== 'AUTO') return Number(recycle ? row.recyclePrice : row.salePrice)
  if (!hasQuote(row.basePrice)) return null
  const value = Number(row.basePrice) * finenessFactor(row.purityCoefficient)
    + (recycle ? -Number(row.recycleDeduction) : Number(row.markup))
  return round(Math.max(0, value), row.roundingRule)
}

export function autoPricingIssue(row) {
  if (row.pricingMode !== 'AUTO' || Number(row.status ?? 1) === 0) return ''
  if (!numeric(row.purityCoefficient) || Number(row.purityCoefficient) <= 0 || Number(row.purityCoefficient) > 1) return '成色系数必须在0到1之间'
  if (![row.markup, row.recycleDeduction].every(value => numeric(value) && Number(value) >= 0)) return '请填写有效的卖价加价和回收扣减（大于或等于0）'
  if (Number(row.markup) === 0 && Number(row.recycleDeduction) === 0) return '自动定价的卖价加价和回收扣减至少填写一项大于0的金额，确保卖价高于回收价'
  if (!rules.has(row.roundingRule)) return '请选择有效的取整规则'
  if (!hasQuote(row.basePrice)) return '暂无有效基准行情，请先刷新行情或使用手动定价；现价保持不变'
  if (pricingPreview(row) <= pricingPreview(row, true)) return '取整后卖价须高于回收价，请增加加价或扣减，或调整取整规则；现价保持不变'
  if (row.marketStatus === 'ERROR') return '行情异常，请先恢复有效行情后启用自动定价；现价保持不变'
  return ''
}
