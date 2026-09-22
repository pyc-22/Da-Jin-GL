import { describe, expect, it } from 'vitest'
import { buildProcessingPrintHtml, buildShiftPreview, buildShiftPrintHtml, parsePurity, PURITY_OPTIONS } from './cashier'

describe('cashier old material and shift helpers', () => {
  it('supports the required purity choices and parses custom 96.5%', () => {
    expect(PURITY_OPTIONS.map(item => item.label)).toEqual(['99.9%', '99%', '95%', '90%', '75%', '58.5%', '其他'])
    expect(parsePurity('other', 96.5)).toBe(0.965)
    expect(parsePurity('other', 100.1)).toBe(0)
  })

  it('builds a complete shift handover preview with standard money formatting', () => {
    const model = {
      storeName: '测试金店', cashierName: '收银员A', shiftNo: 'SHIFT-001', confirmedAt: '2026-09-04 14:30:00',
      rows: [{ pay_method: 'CASH+WECHAT', amount: 1234.56, count: 2 }], cashExpected: 1234.56,
      cashActual: 1334.56, difference: 100, remark: '备用金', total: 1234.56
    }
    const text = buildShiftPreview(model)

    expect(text).toContain('测试金店')
    expect(text).toContain('收银员：收银员A')
    expect(text).toContain('班次：SHIFT-001')
    expect(text).toContain('现金+微信  ¥1,234.56  (2笔)')
    expect(text).toContain('现金应收：¥1,234.56')
    expect(text).toContain('现金实点：¥1,334.56')
    expect(text).toContain('现金差额：¥100.00')
    expect(buildShiftPrintHtml(model, '58')).toContain('@page{size:58mm auto')
    expect(buildShiftPrintHtml(model, 'a4')).toContain('@page{size:A4')
    expect(buildShiftPrintHtml(model, 'a4')).toContain('差异原因：备用金')
  })

  it('builds the processing work order as a single-page A4 document', () => {
    const html = buildProcessingPrintHtml({ orderNo: '加工-001', quantity: 1, itemName: '戒指' })
    expect(html).toContain('@page{size:A4 portrait;margin:7mm}')
    expect(html).toContain('打印格式：A4 纵向')
    expect(html).not.toContain('size:A5')
  })
})
