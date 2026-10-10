export function createDirectOrderLine(goods, { gramWeight = 0, unitPrice = 0 } = {}) {
  const goodsId = goods?.goods_id ?? goods?.id
  if (goodsId == null) throw new Error('商品不存在')
  if (Number(goods.status ?? 1) !== 1) throw new Error('该商品已下架')

  const availableStock = Math.max(0, Number(goods.available_stock ?? goods.stock ?? 0))
  if (!(availableStock > 0)) throw new Error('该商品库存不足')

  const gramPriced = Number(goods.price_type ?? goods.priceType) === 1
  const weight = Number(gramWeight)
  if (gramPriced && !(weight > 0)) throw new Error('按克商品请先填写计费克重')
  if (gramPriced && weight > availableStock) throw new Error('输入克重超过当前可售库存')

  const price = Number(unitPrice || goods.sale_price || 0)
  if (gramPriced && !(price > 0)) throw new Error('商品金价未获取到，请刷新后重试')
  return {
    goodsId,
    barcode: goods.barcode,
    itemName: goods.name || goods.goods_name,
    qty: 1,
    availableStock,
    pieceNos: [],
    weight: gramPriced ? weight : goods.weight,
    subtotal: gramPriced ? price * weight : Number(goods.sale_price || 0),
    unitPrice: gramPriced ? price : Number(goods.sale_price || 0),
    goldType: goods.gold_type || goods.goldType,
    priceType: Number(goods.price_type ?? goods.priceType ?? 2),
    image: goods.piece_image || goods.images
  }
}

export function hasDirectOrderLine(items, goodsId) {
  return (Array.isArray(items) ? items : []).some(item => String(item.goodsId ?? item.goods_id) === String(goodsId))
}
