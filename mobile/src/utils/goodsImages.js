export function parseGoodsImages(raw) {
  if (Array.isArray(raw)) return raw.filter(Boolean).map(String)
  const value = String(raw || '').trim()
  if (!value) return []
  if (/^(https?:|data:|blob:|\/)/i.test(value)) return [value]
  try {
    const parsed = JSON.parse(value)
    return Array.isArray(parsed) ? parsed.filter(Boolean).map(String) : []
  } catch {
    return []
  }
}

export function normalizeGoodsImageUrl(raw, apiBase = '') {
  const value = String(raw || '').trim()
  if (!value || /^(https?:|data:|blob:)/i.test(value) || !value.startsWith('/')) return value
  const base = String(apiBase || '').replace(/\/$/, '')
  return /^https?:\/\//i.test(base) ? `${base}${value}` : value
}

export function firstGoodsImage(goods, apiBase = '') {
  const pieceImage = parseGoodsImages(goods?.piece_image)[0]
  const catalogImage = parseGoodsImages(goods?.images)[0]
  return normalizeGoodsImageUrl(pieceImage || catalogImage || '', apiBase)
}
