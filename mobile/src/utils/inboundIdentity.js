function text(value) {
  return value === undefined || value === null ? '' : String(value).trim()
}

export function parseInboundScanPayload(raw) {
  const value = text(raw)
  if (!value) return { barcode: '', pieceNo: '' }

  let source = null
  try {
    const parsed = JSON.parse(value)
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) source = parsed
  } catch {}

  if (!source && value.includes('=')) {
    try {
      const query = value.includes('?') ? value.slice(value.indexOf('?') + 1) : value
      source = Object.fromEntries(new URLSearchParams(query).entries())
    } catch {}
  }
  if (!source) return { barcode: value, pieceNo: '' }

  const pick = (...keys) => keys.map(key => source[key]).find(item => text(item))
  const explicitPieceNo = pick('pieceNo', 'pieceCode', 'uid', 'tagId', 'singleCode', '单件码')
  const legacySerial = pick('serialNo', 'serial')
  return {
    barcode: text(pick('goodsBarcode', 'productBarcode', 'barcode', 'barCode', 'sku', 'productCode', 'code') || (!explicitPieceNo ? legacySerial : '')),
    pieceNo: text(explicitPieceNo),
    name: text(pick('name', 'goodsName', 'productName', 'title')),
    categoryName: text(pick('categoryName', 'category', 'type', '品类')),
    parentCategoryName: text(pick('parentCategoryName', 'parentCategory', '一级分类')),
    goldWeight: Number(pick('goldWeight', 'weight', 'gram', 'grams', '金重') || 0),
    labelPrice: Number(pick('labelPrice', 'salePrice', 'price', 'tagPrice', '标签价') || 0),
    costPrice: Number(pick('costPrice', 'cost', '成本价') || 0),
    certificateNo: text(pick('certificateNo', 'certificate', 'certNo', '证书号'))
  }
}

export function sameInboundIdentity(item, scanned) {
  const pieceNo = text(scanned?.pieceNo)
  if (pieceNo) return text(item?.pieceNo) === pieceNo
  const barcode = text(scanned?.barcode)
  return Boolean(barcode) && !text(item?.pieceNo) && text(item?.barcode) === barcode
}

export function inboundItemKey(item) {
  if (text(item?.pieceNo)) return `piece-${text(item.pieceNo)}`
  if (item?.goodsId !== undefined && item?.goodsId !== null) return `goods-${item.goodsId}`
  return `manual-${text(item?.barcode) || Date.now()}`
}
