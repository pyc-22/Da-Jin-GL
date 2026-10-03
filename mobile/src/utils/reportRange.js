export function reportDate(value = new Date()) {
  return `${value.getFullYear()}-${String(value.getMonth() + 1).padStart(2, '0')}-${String(value.getDate()).padStart(2, '0')}`
}

// Processing statistics use from/to, while the sales reports accept timeType.
export function reportRange(type, start, end, now = new Date()) {
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  let from = new Date(today)
  let to = new Date(today)
  if (type === 'yesterday') {
    from.setDate(from.getDate() - 1)
    to = new Date(from)
  } else if (type === 'week') {
    from.setDate(from.getDate() - ((from.getDay() + 6) % 7))
  } else if (type === 'lastMonth') {
    from = new Date(today.getFullYear(), today.getMonth() - 1, 1)
    to = new Date(today.getFullYear(), today.getMonth(), 0)
  } else if (type === 'custom') {
    return { from: start || reportDate(new Date(today.getFullYear(), today.getMonth(), 1)), to: end || reportDate(today) }
  } else if (type !== 'today') {
    from.setDate(1)
  }
  return { from: reportDate(from), to: reportDate(to) }
}
