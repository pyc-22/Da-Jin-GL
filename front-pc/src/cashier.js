const PAYMENT_LABELS = Object.freeze({
  CASH: '现金', WECHAT: '微信', ALIPAY: '支付宝', BANK: '银行卡', BALANCE: '储值', COMBINATION: '组合支付'
})

export const PURITY_OPTIONS = Object.freeze([
  { label: '99.9%', value: '0.999' },
  { label: '99%', value: '0.99' },
  { label: '95%', value: '0.95' },
  { label: '90%', value: '0.9' },
  { label: '75%', value: '0.75' },
  { label: '58.5%', value: '0.585' },
  { label: '其他', value: 'other' }
])

export function formatCashierMoney(value) {
  return new Intl.NumberFormat('zh-CN', {
    style: 'currency', currency: 'CNY', minimumFractionDigits: 2, maximumFractionDigits: 2
  }).format(Number(value || 0))
}

export function parsePurity(choice, custom) {
  const percentage = Number(custom)
  if (choice === 'other') {
    if (!Number.isFinite(percentage) || percentage <= 0 || percentage > 100) return 0
    return Math.round(percentage * 10) / 1000
  }
  const value = Number(choice)
  return Number.isFinite(value) && value > 0 && value <= 1 ? value : 0
}

/**
 * 甲方口径：补金 = 成品实重 − 融后金重。
 * 成品里超出客户融后金的部分都由客户付（含店里下料），损耗（打磨屑）由店里承担、不进补金。
 * 成品比融后金轻时没有补金，多出来的金走回收屑抵扣（= 融后金重 − 成品实重）。
 * 只用于柜面预填与提示，补金克重仍由柜面确认后手填。
 */
export function goldBalance({ melt, finished } = {}) {
  const meltWeight = Number(melt || 0)
  const finishedWeight = Number(finished || 0)
  if (!(meltWeight > 0) || !(finishedWeight > 0)) return { balance: null, topUp: 0, hint: '' }
  const balance = Math.round((finishedWeight - meltWeight) * 1000) / 1000
  if (balance > 0) return { balance, topUp: balance, hint: `差额 +${balance.toFixed(3)}g → 客户需补金 ${balance.toFixed(3)}g（成品实重 − 融后金重，已按此预填，可手改）` }
  return { balance, topUp: 0, hint: `差额 ${balance.toFixed(3)}g → 成品比融后金轻 ${Math.abs(balance).toFixed(3)}g：没有补金，多出的金走回收屑抵扣` }
}

export function buildShiftPreview(model) {
  const paymentLabel = value => {
    const code = String(value || '').trim()
    if (!code || code === '0') return '-'
    return code.split('+').map(part => PAYMENT_LABELS[part.trim().toUpperCase()] || part.trim()).join('+')
  }
  const lines = (model.rows || []).map(row => `${paymentLabel(row.pay_method)}  ${formatCashierMoney(row.amount)}  (${row.count || 0}笔)`).join('\n') || '本班暂无收款'
  return `${model.storeName || '-'}\n交班对账单\n交班时间：${model.confirmedAt || '-'}\n收银员：${model.cashierName || '-'}\n班次：${model.shiftNo || '-'}\n------------------------------\n${lines}\n------------------------------\n现金应收：${formatCashierMoney(model.cashExpected)}\n现金实点：${formatCashierMoney(model.cashActual)}\n现金差额：${formatCashierMoney(model.difference)}\n收款合计：${formatCashierMoney(model.total)}\n差异原因：${model.remark || '-'}`
}

function escapeHtml(value) {
  return String(value ?? '-').replace(/[&<>"']/g, character => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  })[character])
}

export function buildShiftPrintHtml(model, paper = '58') {
  const isA4 = paper === 'a4'
  const paymentLabel = value => {
    const code = String(value || '').trim()
    if (!code || code === '0') return '-'
    return code.split('+').map(part => PAYMENT_LABELS[part.trim().toUpperCase()] || part.trim()).join('+')
  }
  const rows = (model.rows || []).map(row => `<tr><td>${escapeHtml(paymentLabel(row.pay_method))}</td><td>${Number(row.count || 0)}笔</td><td>${escapeHtml(formatCashierMoney(row.amount))}</td></tr>`).join('')
    || '<tr><td colspan="3" class="empty">本班暂无收款</td></tr>'
  const pageSize = isA4 ? 'A4' : '58mm auto'
  const pageWidth = isA4 ? '180mm' : '50mm'
  const bodyPadding = isA4 ? '14mm' : '2mm'
  return `<!doctype html><html><head><meta charset="utf-8"><title>交班对账单</title><style>
    @page{size:${pageSize};margin:${isA4 ? '10mm' : '2mm'}}
    *{box-sizing:border-box}body{margin:0;background:#fff;color:#171717;font-family:Arial,"Microsoft YaHei",sans-serif;font-size:${isA4 ? '14px' : '11px'}}
    .sheet{width:${pageWidth};margin:0 auto;padding:${bodyPadding}}h1{font-size:${isA4 ? '24px' : '16px'};text-align:center;margin:4px 0 14px}.store{text-align:center;font-weight:700;margin-bottom:5px}
    .meta{display:grid;grid-template-columns:${isA4 ? '1fr 1fr' : '1fr'};gap:5px;margin-bottom:12px}.meta span{overflow-wrap:anywhere}
    table{width:100%;border-collapse:collapse;margin:8px 0 12px}th,td{border-bottom:1px solid #bbb;padding:7px 3px;text-align:left}th:last-child,td:last-child{text-align:right}.empty{text-align:center!important;color:#777}
    .summary{border-top:2px solid #222;padding-top:8px}.line{display:flex;justify-content:space-between;gap:10px;margin:6px 0}.line.total{font-size:${isA4 ? '17px' : '13px'};font-weight:700;border-top:1px solid #999;padding-top:8px}.remark{margin-top:12px;overflow-wrap:anywhere}
  </style></head><body><main class="sheet"><div class="store">${escapeHtml(model.storeName)}</div><h1>交班对账单</h1><div class="meta"><span>交班时间：${escapeHtml(model.confirmedAt)}</span><span>收银员：${escapeHtml(model.cashierName)}</span><span>班次：${escapeHtml(model.shiftNo)}</span></div><table><thead><tr><th>支付方式</th><th>笔数</th><th>金额</th></tr></thead><tbody>${rows}</tbody></table><section class="summary"><div class="line"><span>现金应收</span><b>${escapeHtml(formatCashierMoney(model.cashExpected))}</b></div><div class="line"><span>现金实点</span><b>${escapeHtml(formatCashierMoney(model.cashActual))}</b></div><div class="line"><span>现金差额</span><b>${escapeHtml(formatCashierMoney(model.difference))}</b></div><div class="line total"><span>收款合计</span><b>${escapeHtml(formatCashierMoney(model.total))}</b></div></section><div class="remark">差异原因：${escapeHtml(model.remark || '-')}</div></main></body></html>`
}
