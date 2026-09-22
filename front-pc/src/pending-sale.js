export function pendingSaleOrderId(order) {
  if (!order || ![0, 3].includes(Number(order.status))) return null
  const id = order.orderId ?? order.order_id ?? order.id
  return id == null || id === '' ? null : id
}

export function shouldReusePendingSale(order) {
  return pendingSaleOrderId(order) != null
}
