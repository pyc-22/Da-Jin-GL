export function togglePaymentMethod(method, remaining) {
  if (method.selected) {
    method.selected = false
    method.amount = 0
    return
  }
  method.selected = true
  method.amount = Math.max(0, Number(remaining || 0))
}

export function activatePaymentMethod(method, remaining) {
  method.selected = true
  method.amount = Math.max(0, Number(remaining || 0))
}

export function paymentInputMethods(methods) {
  return methods.filter(method => method.selected)
}

export function reconcilePaymentMethods(channels, currentMethods = []) {
  const currentByCode = new Map((Array.isArray(currentMethods) ? currentMethods : []).map(method => [
    String(method?.code || '').trim().toUpperCase(), method
  ]))

  return (Array.isArray(channels) ? channels : []).flatMap(channel => {
    const code = String(channel?.channel_code ?? channel?.channelCode ?? '').trim().toUpperCase()
    const name = String(channel?.channel_name ?? channel?.channelName ?? '').trim()
    if (!code || !name || Number(channel?.status ?? 1) !== 1 || code === 'COMBINATION') return []
    const previous = currentByCode.get(code)
    return [{
      ...previous,
      code,
      name,
      amount: Number(previous?.amount || 0),
      selected: Boolean(previous?.selected)
    }]
  })
}
