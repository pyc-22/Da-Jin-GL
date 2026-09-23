export const MAX_INBOUND_PHOTOS = 4

export function normalizeInboundPhotoSlots(quantity, pieces = []) {
  const count = Math.min(MAX_INBOUND_PHOTOS, Math.max(1, Math.round(Number(quantity) || 1)))
  const normalized = Array.isArray(pieces) ? pieces.slice(0, count).map(value => value || '') : []
  while (normalized.length < count) normalized.push('')
  return normalized
}

export function isInboundPhotoSizeError(error) {
  return String(error?.message || error || '').includes('超过2MB')
}
