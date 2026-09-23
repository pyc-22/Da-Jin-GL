import { describe, expect, it } from 'vitest'
import { activatePaymentMethod, paymentInputMethods, reconcilePaymentMethods, togglePaymentMethod } from './payment-methods'

describe('cashier combination payment inputs', () => {
  it('keeps a selected payment input visible when its amount is edited to zero', () => {
    const balance = { code: 'BALANCE', amount: 0, selected: false }
    activatePaymentMethod(balance, 100)
    balance.amount = 0
    expect(paymentInputMethods([balance])).toEqual([balance])
  })

  it('removes the input only when the user explicitly deselects the method', () => {
    const cash = { code: 'CASH', amount: 0, selected: false }
    togglePaymentMethod(cash, 80)
    expect(cash).toMatchObject({ amount: 80, selected: true })
    togglePaymentMethod(cash, 0)
    expect(cash).toMatchObject({ amount: 0, selected: false })
  })

  it('replaces stale methods with an empty configured channel list', () => {
    expect(reconcilePaymentMethods([], [{ code: 'CASH', name: '现金', amount: 80, selected: true }])).toEqual([])
  })

  it('uses configured Chinese names and preserves an in-progress amount by channel code', () => {
    const methods = reconcilePaymentMethods([
      { channel_code: 'UNIONPAY', channel_name: '云闪付', status: 1 },
      { channel_code: 'CASH', channel_name: '现金', status: 0 },
      { channel_code: 'COMBINATION', channel_name: '组合支付', status: 1 }
    ], [{ code: 'UNIONPAY', name: '旧名称', amount: 66, selected: true }])

    expect(methods).toEqual([{ code: 'UNIONPAY', name: '云闪付', amount: 66, selected: true }])
  })
})
