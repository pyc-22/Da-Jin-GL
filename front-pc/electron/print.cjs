const ESC = '\x1b'
const GS = '\x1d'
const enc = value => Buffer.from(String(value), 'utf8')
const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]))

function receiptEscPos(model = {}) {
  const width = model.paperWidth === 58 ? 32 : 48
  const lines = [model.storeName || '打金店', '销售小票', `单号: ${model.billNo || '-'}`, `时间: ${new Date().toLocaleString('zh-CN')}`, '-'.repeat(width)]
  ;(model.items || []).forEach(item => lines.push(`${item.name} x${item.qty || 1}`, `  ${item.detail || ''}  ¥${Number(item.amount || 0).toFixed(2)}`))
  lines.push('-'.repeat(width), `合计: ¥${Number(model.total || 0).toFixed(2)}`, `抵扣: -¥${Number(model.deduct || 0).toFixed(2)}`, `应收: ¥${Number(model.payable || 0).toFixed(2)}`, `支付: ${model.payMethod || '-'}`, '', '谢谢惠顾', '')
  const data = Buffer.concat([Buffer.from(ESC + '@' + ESC + 'a' + '\x01'), ...lines.map(line => Buffer.concat([enc(line), Buffer.from('\n')])), Buffer.from(GS + 'V' + '\x00')])
  return { bytes: data.toString('base64'), text: lines.join('\n'), paperWidth: width }
}

function openDrawerEscPos() { return Buffer.from(ESC + 'p' + '\x00\x19\xfa', 'binary').toString('base64') }

function receiptHtml(model = {}) {
  const width = Number(model.paperWidth) === 80 ? 80 : 58
  const rows = (model.items || []).map(item => `<div class="item"><div>${escapeHtml(item.name)} ×${Number(item.qty || 1)}</div><div class="detail">${escapeHtml(item.detail || '')}<span>¥${Number(item.amount || 0).toFixed(2)}</span></div></div>`).join('')
  return `<!doctype html><html><head><meta charset="utf-8"><style>@page{size:${width}mm auto;margin:0}*{box-sizing:border-box}body{width:${width}mm;margin:0;padding:4mm 3mm;font-family:"Microsoft YaHei",Arial,sans-serif;font-size:11px;color:#111}.center{text-align:center}.title{font-size:16px;font-weight:700;margin-bottom:3mm}.muted{color:#555}.line{border-top:1px dashed #555;margin:3mm 0}.item{margin:2mm 0}.detail{color:#555;font-size:10px;display:flex;justify-content:space-between;gap:4mm}.total{display:flex;justify-content:space-between;font-weight:700;font-size:13px;margin-top:2mm}.footer{text-align:center;margin-top:5mm}</style></head><body><div class="center title">${escapeHtml(model.storeName || '打金店')}</div><div class="center muted">销售小票</div><div>单号：${escapeHtml(model.billNo || '-')}</div><div>时间：${escapeHtml(new Date().toLocaleString('zh-CN'))}</div><div class="line"></div>${rows}<div class="line"></div><div class="total"><span>合计</span><span>¥${Number(model.total || 0).toFixed(2)}</span></div><div class="total"><span>旧金抵扣</span><span>-¥${Number(model.deduct || 0).toFixed(2)}</span></div><div class="total"><span>应收</span><span>¥${Number(model.payable || 0).toFixed(2)}</span></div><div>支付：${escapeHtml(model.payMethod || '-')}</div><div class="footer">谢谢惠顾</div></body></html>`
}

async function printHtml(parent, html, options = {}, BrowserWindowClass) {
  const BrowserWindow = BrowserWindowClass || require('electron').BrowserWindow
  const win = new BrowserWindow({ parent: parent || undefined, show: false, autoHideMenuBar: true, webPreferences: { contextIsolation: true } })
  try {
    await win.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(html))
    if (!options.silent) win.showInactive()
    const printOptions = { silent: Boolean(options.silent), printBackground: true, deviceName: options.deviceName || '' }
    if (options.pageSize) printOptions.pageSize = options.pageSize
    return await new Promise(resolve => win.webContents.print(printOptions, (success, reason) => resolve({ success, reason })))
  } catch (error) {
    return { success: false, reason: error?.message || '打印窗口加载失败' }
  } finally {
    if (!win.isDestroyed()) win.close()
  }
}
module.exports = { receiptEscPos, receiptHtml, openDrawerEscPos, printHtml }
