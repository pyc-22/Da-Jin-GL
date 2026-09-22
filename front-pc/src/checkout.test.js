import { describe, expect, it } from 'vitest'
import { calculateOldMaterialSettlement, handoverCheckout } from './checkout'

describe('cashier old material settlement', () => {
  it('preserves the saved deduction and whole-order labor on a handover', () => {
    const detail = { order: { total_amount: 1000, discount: 1, labor_fee: 100, old_material_deduct: 300 },
      items: [{ item_name: 'Item', qty: 2, unit_price: 50, weight: 10, subtotal: 1000 }] }
    const checkout = handoverCheckout(detail)
    expect(checkout.items[0].amount).toBe(500)
    expect(calculateOldMaterialSettlement(checkout.subtotal, 1, checkout.laborFee, checkout.oldMaterialValue).payable).toBe(800)
  })
  it('caps the sale deduction and turns the excess into a recycle payout', () => {
    expect(calculateOldMaterialSettlement(268, 1, 0, 299.7)).toEqual({
      grossAmount: 268,
      appliedDeduction: 268,
      excessPayout: 31.7,
      payable: 0
    })
  })

  it('keeps a normal old-material deduction on the sale', () => {
    expect(calculateOldMaterialSettlement(268, 1, 0, 100)).toEqual({
      grossAmount: 268,
      appliedDeduction: 100,
      excessPayout: 0,
      payable: 168
    })
  })

  it('applies the discount before adding labor fees', () => {
    expect(calculateOldMaterialSettlement(200, 0.9, 20, 250)).toEqual({
      grossAmount: 200,
      appliedDeduction: 200,
      excessPayout: 50,
      payable: 0
    })
  })
})
