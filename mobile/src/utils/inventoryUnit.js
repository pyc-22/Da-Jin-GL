export function isGramPriced(row) {
  return Number(row?.price_type ?? row?.priceType) === 1
}

export function inventoryValue(row) {
  return Number(row?.available_stock ?? row?.stock ?? row?.quantity ?? 0)
}

export function inventoryText(row) {
  const value = inventoryValue(row)
  return isGramPriced(row)
    ? `${value.toFixed(3)}g`
    : `${Math.max(0, Math.floor(value))}件`
}

export function categoryInventoryText(row) {
  const grams = Number(row?.gram_quantity ?? 0)
  const pieces = Number(row?.piece_quantity ?? 0)
  if (grams > 0 && pieces > 0) return `${grams.toFixed(3)}g · ${Math.floor(pieces)}件`
  if (grams > 0) return `${grams.toFixed(3)}g`
  return `${Math.max(0, Math.floor(pieces || Number(row?.quantity || 0)))}件`
}
