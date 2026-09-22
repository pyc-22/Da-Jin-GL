function moneyCents(value) {
  const number = Number(value || 0)
  return Number.isFinite(number) ? Math.round(number * 100) : 0
}

export function handoverCheckout(detail) {
  const order = detail.order
  return {
    subtotal: Number(order.total_amount || 0),
    laborFee: Number(order.labor_fee || 0),
    oldMaterialValue: Number(order.old_material_deduct || 0) + Number(order.old_material_excess || 0),
    items: (detail.items || []).map(item => ({
      goodsId: item.goods_id, name: item.item_name, weight: Number(item.weight || 0),
      priceType: 2, unitPrice: Number(item.unit_price || 0),
      amount: Number(item.subtotal || 0) / Number(item.qty || 1),
      laborFee: Number(item.labor_fee || 0), qty: Number(item.qty || 1), stock: 0
    }))
  }
}

export function calculateOldMaterialSettlement(subtotal, discount, laborFee, oldMaterialValue) {
  const rate = Number(discount ?? 1)
  const grossCents = Math.max(0, Math.round((Number(subtotal || 0) * rate + Number(laborFee || 0)) * 100))
  const oldMaterialCents = Math.max(0, moneyCents(oldMaterialValue))
  const appliedCents = Math.min(grossCents, oldMaterialCents)

  return {
    grossAmount: grossCents / 100,
    appliedDeduction: appliedCents / 100,
    excessPayout: (oldMaterialCents - appliedCents) / 100,
    payable: (grossCents - appliedCents) / 100
  }
}
