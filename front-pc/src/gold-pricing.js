function finitePrice(value) {
  const number = Number(value)
  return Number.isFinite(number) && number >= 0 ? number : null
}

function rowName(row) {
  return String(row?.price_type || row?.priceType || row?.type_name || row?.name || row?.type_code || row?.code || '').trim()
}

function isRecycleRow(row) {
  return /recycle/i.test(String(row?.type_code || row?.code || '')) || rowName(row).includes('回收')
}

function firstDefined(...values) {
  for (const value of values) {
    const number = finitePrice(value)
    if (number != null) return number
  }
  return null
}

function firstPositive(...values) {
  const defined = []
  for (const value of values) {
    const number = finitePrice(value)
    if (number != null) {
      defined.push(number)
      if (number > 0) return number
    }
  }
  return defined[0] ?? null
}

function rowValue(row, camelName, legacyName) {
  return row?.[camelName] ?? row?.[legacyName]
}

function explicitCombinedValue(row, camelName, legacyName) {
  const value = finitePrice(rowValue(row, camelName, legacyName))
  if (value == null) return null
  // Current API rows and realtime events carry a source when the combined
  // sale/recycle fields are authoritative, including an intentional zero.
  if (value > 0 || row?.pricingSource != null) return value
  return null
}

function findRow(rows, names, codes = []) {
  return rows.find(row => names.includes(rowName(row)) || codes.includes(String(row?.type_code || row?.code || '').trim().toUpperCase())) || null
}

export function resolveGoldReferences(rows) {
  const list = Array.isArray(rows) ? rows : []
  const gold = findRow(list, ['足金'], ['GOLD'])
  const goldRecycle = findRow(list, ['回收金价'], ['RECYCLE'])
  const silver = findRow(list, ['银'], ['SILVER'])
  const silverRecycle = findRow(list, ['银回收价'], ['SILVER_RECYCLE'])

  const goldSale = firstDefined(rowValue(gold, 'salePrice', 'sale_price'), gold?.price) ?? 612
  const recycle = explicitCombinedValue(gold, 'recyclePrice', 'recycle_price')
    ?? firstPositive(rowValue(goldRecycle, 'recyclePrice', 'recycle_price'), goldRecycle?.price)
    ?? 578
  const silverSale = firstDefined(rowValue(silver, 'salePrice', 'sale_price'), silver?.price) ?? 0
  const silverRecyclePrice = explicitCombinedValue(silver, 'recyclePrice', 'recycle_price')
    ?? explicitCombinedValue(silverRecycle, 'recyclePrice', 'recycle_price')
    ?? firstPositive(silverRecycle?.price)
    ?? 0

  const map = {}
  for (const row of list) {
    const name = rowName(row)
    if (!name) continue
    map[name] = isRecycleRow(row)
      ? (explicitCombinedValue(row, 'recyclePrice', 'recycle_price') ?? firstPositive(row.price) ?? recycle)
      : (firstDefined(rowValue(row, 'salePrice', 'sale_price'), row.price) ?? 0)
    const code = String(row?.type_code || row?.code || '').trim()
    if (code) map[code] = map[name]
  }
  // Legacy callers still use these two names; prefer the combined row values.
  map['回收金价'] = recycle
  map.RECYCLE = recycle
  map['银回收价'] = silverRecyclePrice
  map.SILVER_RECYCLE = silverRecyclePrice
  return { goldSpot: goldSale, recycleSpot: recycle, silverSaleSpot: silverSale, silverRecycleSpot: silverRecyclePrice, goldMap: map }
}

export function mergeGoldPriceUpdate(rows, update) {
  const list = Array.isArray(rows) ? rows.map(row => ({ ...row })) : []
  const priceType = String(update?.priceType || '').trim()
  if (!priceType) return list
  const index = list.findIndex(row => rowName(row) === priceType)
  const next = {
    price_type: priceType,
    price: update.price,
    salePrice: update.salePrice,
    recyclePrice: update.recyclePrice,
    source: update.source,
    pricingSource: update.source,
    quoteTime: update.quoteTime,
    marketStatus: update.marketStatus,
    pricingMode: update.pricingMode
  }
  if (index >= 0) list[index] = { ...list[index], ...next }
  else list.push(next)

  // Legacy configurations keep recycle prices in a separate row. Mirror a
  // split recycle event onto its combined row so the latter cannot mask the
  // newly saved value during reference resolution.
  const canonicalName = priceType === '回收金价' || priceType.toUpperCase() === 'RECYCLE'
    ? '足金'
    : priceType === '银回收价' || priceType.toUpperCase() === 'SILVER_RECYCLE'
      ? '银'
      : null
  if (canonicalName) {
    const canonicalIndex = list.findIndex(row => rowName(row) === canonicalName)
    if (canonicalIndex >= 0 && canonicalIndex !== index) {
      const recycle = update.recyclePrice ?? update.recycle_price ?? update.price
      list[canonicalIndex] = {
        ...list[canonicalIndex],
        recyclePrice: recycle,
        recycle_price: recycle,
        pricingSource: update.source,
        source: update.source ?? list[canonicalIndex].source,
        quoteTime: update.quoteTime ?? list[canonicalIndex].quoteTime,
        marketStatus: update.marketStatus ?? list[canonicalIndex].marketStatus,
        pricingMode: update.pricingMode ?? list[canonicalIndex].pricingMode
      }
    }
  }
  return list
}
