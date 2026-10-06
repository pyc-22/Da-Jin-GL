export function orderFingerprint(order = {}) {
  return JSON.stringify({
    memberId: order.memberId ?? null,
    memberKeyword: order.memberKeyword || '',
    discount: Number(order.discount || 1),
    laborFee: Number(order.laborFee || 0),
    items: (order.items || []).map(item => ({
      goodsId: item.goodsId ?? null,
      pieceNos: Array.isArray(item.pieceNos) ? item.pieceNos : [],
      qty: Number(item.qty || 0),
      weight: Number(item.weight || 0),
      unitPrice: Number(item.unitPrice || 0),
      subtotal: Number(item.subtotal || 0),
      priceType: item.priceType ?? item.price_type ?? null
    })),
    oldMetals: (order.oldMetals || []).map(item => ({
      materialType: item.materialType || '',
      weight: Number(item.weight || 0),
      purity: Number(item.purity || 0),
      price: Number(item.price || 0)
    }))
  })
}

export function stableOrderClientRequestId(order, storage, now = Date.now, random = Math.random) {
  const fingerprint = orderFingerprint(order)
  const current = storage.get('dajin-order-client-fp', '')
  let clientRequestId = storage.get('dajin-order-client-id', '')
  if (!clientRequestId || current !== fingerprint) {
    clientRequestId = `mobile-${now()}-${random().toString(36).slice(2, 8)}`
    storage.set('dajin-order-client-id', clientRequestId)
    storage.set('dajin-order-client-fp', fingerprint)
  }
  return clientRequestId
}
