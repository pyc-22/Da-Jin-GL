export function processingOutstanding(order) {
  if (order?.tail_due_amount != null) return Math.max(0, Number(order.tail_due_amount || 0))
  return Math.max(0, Number(order?.due_amount || 0) - Number(order?.paid_amount || 0))
}

export function processingRefundOutstanding(order) {
  return Math.max(0, Number(order?.refund_amount || 0) - Number(order?.refund_paid_amount || 0))
}

export async function markProcessingPickedUp(order, request, pickupPhotos) {
  if (!order?.processing_order_id) throw new Error('加工单不存在')
  if (processingOutstanding(order) > 0) throw new Error('尾款未收清，请先收尾款')
  if (processingRefundOutstanding(order) > 0) throw new Error('客户返款未完成，请先完成返款')
  const photos = Array.isArray(pickupPhotos) ? pickupPhotos.filter(photo => String(photo || '').trim()) : []
  if (!photos.length) throw new Error('请先添加至少1张取货照片')
  await request(`/api/processing/orders/${order.processing_order_id}/photos`, {
    method: 'POST',
    body: JSON.stringify({ type: 'pickup', urls: photos })
  })
  return request(`/api/processing/orders/${order.processing_order_id}/status`, {
    method: 'PATCH',
    body: JSON.stringify({ status: 'PICKED_UP' })
  })
}
