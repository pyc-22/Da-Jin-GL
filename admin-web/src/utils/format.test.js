import { describe, expect, it } from 'vitest'
import { financeBusinessType, formatApprovalReason, formatFinanceBusiness, formatFinanceType, formatMoney, formatOperationAction, formatOperationModule, formatPaymentMethod, formatShiftContent, formatTime } from './format'

describe('management display formatting', () => {
  it('renders a UTC timestamp in Beijing time without an ISO separator', () => {
    expect(formatTime('2026-09-03T06:38:47')).toBe('2026-09-03 14:38:47')
  })

  it('renders supported payment channels in Chinese and handles missing values', () => {
    expect(formatPaymentMethod('CASH+WECHAT')).toBe('现金+微信')
    expect(formatPaymentMethod('CASH:¥100.00+WECHAT:¥98.00')).toBe('现金+微信')
    expect(formatPaymentMethod(null)).toBe('-')
    expect(formatPaymentMethod(0)).toBe('-')
    expect(formatPaymentMethod('UNIONPAY', [{ channel_code: 'UNIONPAY', channel_name: '云闪付' }])).toBe('云闪付')
  })

  it('renders operational module and action codes in Chinese', () => {
    expect(formatOperationModule('GOLD_PRICE')).toBe('金价管理')
    expect(formatOperationModule('OLD_MATERIAL')).toBe('旧料管理')
    expect(formatOperationAction('STOCK_CHECK_APPROVE')).toBe('通过库存盘点')
    expect(formatOperationAction('ORDER_PAYMENT')).toBe('登记加工收款')
  })

  it('formats monetary values with a currency sign and two decimal places', () => {
    expect(formatMoney(248)).toBe('¥248.00')
  })

  it('renders finance direction codes in Chinese and preserves unknown values', () => {
    expect(formatFinanceType('INCOME')).toBe('收入')
    expect(formatFinanceType('expense')).toBe('支出')
    expect(formatFinanceType('ADJUSTMENT')).toBe('ADJUSTMENT')
    expect(formatFinanceType(null)).toBe('-')
  })

  it('distinguishes sales, processing, refunds and recycle payments', () => {
    expect(financeBusinessType({ type: 'INCOME', category: 'SALE' })).toBe('SALE_INCOME')
    expect(financeBusinessType({ type: 'INCOME', related_bill_no: 'JG20260916001' })).toBe('PROCESSING_INCOME')
    expect(formatFinanceBusiness({ type: 'EXPENSE', category: 'SALE_REFUND' })).toBe('销售退款')
    expect(formatFinanceBusiness({ type: 'EXPENSE', category: 'RECYCLE' })).toBe('回收付款')
    expect(formatFinanceBusiness(null)).toBe('其他收入')
  })

  it('renders Java map shift records with Chinese labels', () => {
    expect(formatShiftContent('{shiftNo=SHIFT-20260917001, cashSystem=1200.5, cashActual=1200, cashDifference=-0.5, remark=备用金误差}')).toBe(
      '班次：SHIFT-20260917001；系统现金：¥1200.50；实点现金：¥1200.00；现金差额：-¥0.50；备注：备用金误差'
    )
  })

  it('renders JSON shift records and empty remarks in Chinese', () => {
    expect(formatShiftContent('{"shiftNo":"SHIFT-001","cashSystem":100,"cashActual":100,"cashDifference":0,"remark":""}')).toBe(
      '班次：SHIFT-001；系统现金：¥100.00；实点现金：¥100.00；现金差额：¥0.00；备注：无'
    )
  })

  it('preserves legacy plain-text shift records', () => {
    expect(formatShiftContent('历史交班备注')).toBe('历史交班备注')
  })

  it('filters legacy technical fields and calculates a missing cash difference', () => {
    expect(formatShiftContent('{cashSystem=-3003.85, cashActual=0, remark=补录, clientRequestId=TOKEN}')).toBe(
      '班次：-；系统现金：-¥3003.85；实点现金：¥0.00；现金差额：¥3003.85；备注：补录'
    )
  })

  it('renders approval reasons in Chinese without changing Chinese descriptions', () => {
    expect(formatApprovalReason({ type: 'REFUND', reason: 'test again' })).toBe('退货退款申请')
    expect(formatApprovalReason({ type: 'DISCOUNT', reason: '折扣低于配置阈值' })).toBe('折扣低于配置阈值')
    expect(formatApprovalReason({ type: 'STOCK_CHECK', reason: '' })).toBe('库存盘点差异审批')
  })
})
