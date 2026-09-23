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

/**
 * Build the single-page A4 processing work order.  The four weighing fields are
 * intentionally left blank so the workshop can complete them by hand after
 * production (finished weight, leftovers, melted weight and added material).
 */
export function buildProcessingPrintHtml(model = {}) {
  const esc = escapeHtml
  const money = value => formatCashierMoney(value)
  const line = (label, value = '') => `<div class="field"><span>${esc(label)}</span><b>${esc(value || ' ')}</b></div>`
  return `<!doctype html><html><head><meta charset="utf-8"><title>加工工单</title><style>
    @page{size:A4 portrait;margin:7mm}*{box-sizing:border-box}
    body{margin:0;color:#171717;font-family:Arial,"Microsoft YaHei",sans-serif;font-size:11px;line-height:1.35}
    .sheet{width:196mm;max-width:100%;margin:0 auto;padding:5mm 7mm;border:1px solid #333;break-inside:avoid;page-break-inside:avoid}
    h1{text-align:center;font-size:22px;letter-spacing:5px;margin:0 0 3px}.subtitle{text-align:center;color:#555;margin-bottom:8px}
    .meta{display:grid;grid-template-columns:1fr 1fr;gap:4px 18px;border-bottom:1px solid #333;padding-bottom:7px}.meta span{display:block}.meta b{font-weight:600;margin-left:5px}
    h2{font-size:13px;border-left:4px solid #a66b2c;padding-left:7px;margin:9px 0 5px}.rows{border:1px solid #555}.row{display:grid;grid-template-columns:1fr 1fr;border-bottom:1px solid #bbb;min-height:24px}.row:last-child{border-bottom:0}.row span,.row b{padding:4px 7px}.row span{background:#f7f4ef}.row b{text-align:right;font-weight:600}
    .calc{display:grid;grid-template-columns:1fr 1fr;gap:0;border:1px solid #555}.calc .cell{padding:5px 7px;border-bottom:1px solid #bbb}.calc .cell:nth-last-child(-n+2){border-bottom:0}.calc .label{color:#555}.calc .value{text-align:right;font-weight:700}
    .handwrite{margin-top:8px;border:1px solid #555}.handwrite .field{display:grid;grid-template-columns:34mm 1fr;min-height:27px;border-bottom:1px solid #bbb}.handwrite .field:last-child{border-bottom:0}.handwrite .field span{padding:6px 7px;background:#f7f4ef;font-weight:600}.handwrite .field b{padding:6px 7px;border-bottom:1px dashed #777;margin:0 8px;font-weight:400}
    .remark{margin-top:8px;border:1px solid #555;min-height:30px;padding:6px}.footer{margin-top:8px;display:flex;justify-content:space-between;color:#555;font-size:10px}.sign{margin-top:10px;display:grid;grid-template-columns:1fr 1fr;gap:20px}.sign div{border-bottom:1px solid #333;padding-bottom:4px}
    @media print{.sheet{border:0;padding:0;width:100%;max-width:none}}
  </style></head><body><main class="sheet"><h1>加工工单</h1><div class="subtitle">请按工单要求完成加工并做好称重记录</div><div class="meta"><span>门店：<b>${esc(model.storeName || '-')}</b></span><span>工单号：<b>${esc(model.orderNo || '-')}</b></span><span>创建时间：<b>${esc(model.createdAt || '-')}</b></span><span>预计取货：<b>${esc(model.pickupDate || '-')}</b></span><span>客户姓名：<b>${esc(model.customerName || '-')}</b></span><span>联系电话：<b>${esc(model.customerPhone || '-')}</b></span></div><h2>加工信息</h2><div class="rows"><div class="row"><span>加工项目</span><b>${esc(model.itemName || '-')}</b></div><div class="row"><span>加工数量</span><b>${esc(`${model.quantity || 0} 件`)}</b></div><div class="row"><span>加工师傅</span><b>${esc(model.craftsmanName || '暂未分配')}</b></div><div class="row"><span>客户带来旧金</span><b>${esc(model.oldGoldWeight ? `${model.oldGoldWeight}g · ${Number(model.oldGoldFineness || 0) * 100}%` : '无')}</b></div><div class="row"><span>余料处理</span><b>${esc(model.residualHandling || '客户带走')}</b></div></div><h2>费用</h2><div class="calc"><div class="cell label">加工工费</div><div class="cell value">${esc(money(model.laborFee))}</div><div class="cell label">旧料抵扣</div><div class="cell value">-${esc(money(model.residualDeduction))}</div><div class="cell label">应收金额</div><div class="cell value">${esc(money(model.dueAmount))}</div><div class="cell label">已收定金</div><div class="cell value">${esc(money(model.deposit))}</div><div class="cell label">尾款待收</div><div class="cell value">${esc(money(model.remainingAmount))}</div></div><h2>生产称重记录（手写）</h2><div class="handwrite">${line('成品实重（g）')}${line('余料（g）')}${line('融后金重（g）')}${line('加料（g）')}</div><div class="remark"><b>备注：</b>${esc(model.remark || '')}</div><div class="sign"><div>加工师傅签字：</div><div>客户取货签字：</div></div><div class="footer"><span>打印格式：A4 纵向</span><span>打印时间：${esc(model.printedAt || '-')}</span></div></main></body></html>`
}
