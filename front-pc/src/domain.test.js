import { describe, expect, it } from 'vitest'
import { buildReceiptPreview } from './escpos'

describe('front cashier calculations', () => {
  it('builds a standard thermal receipt preview for 58mm paper', () => {
    const text = buildReceiptPreview({ storeName: '郑州旗舰店', billNo: 'XS20260901001', items: [{ name: '素圈戒指', qty: 2, detail: '5.2g x ¥612', amount: 6364.8 }], total: 6364.8, deduct: 578, payable: 5786.8, payMethod: '现金:¥3,000.00 + 微信:¥2,786.80', paperWidth: 58 })
    expect(text).toContain('XS20260901001')
    expect(text).toContain('应收: ¥5786.80')
    expect(text.split('\n')[4]).toHaveLength(32)
  })

  it('keeps a payment combination human-readable', () => {
    const text = buildReceiptPreview({ items: [], total: 100, deduct: 0, payable: 100, payMethod: '现金:¥40.00 + 储值:¥60.00' })
    expect(text).toContain('现金:¥40.00 + 储值:¥60.00')
  })

  it('prints the old-material valuation, applied deduction, and excess payout separately', () => {
    const text = buildReceiptPreview({
      items: [], total: 268, oldMaterialValue: 299.7, deduct: 268,
      excessPayout: 31.7, payoutMethod: '现金', payable: 0, payMethod: '无需收款'
    })

    expect(text).toContain('旧金估值: ¥299.70')
    expect(text).toContain('本单抵扣: -¥268.00')
    expect(text).toContain('回收返款: ¥31.70 (现金)')
    expect(text).toContain('应收: ¥0.00')
  })
})
