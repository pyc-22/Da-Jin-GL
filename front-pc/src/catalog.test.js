import { describe, expect, it } from 'vitest'
import { filterCatalogProducts } from './catalog'

describe('cashier catalog search', () => {
  const products = [
    { goods_id: 1, barcode: 'TEST001', name: '测试古法手镯', category_id: 5, parent_category_id: 1 },
    { goods_id: 2, barcode: 'TEST002', name: '足金素圈戒指', category_id: 8, parent_category_id: 1 },
    { goods_id: 3, barcode: 'TEST003', name: 'S925银耳钉', category_id: 21, parent_category_id: 3 },
    { goods_id: 4, barcode: 'TEST004', name: '无分类商品' }
  ]

  it('restores the entire catalog after a keyword search is cleared', () => {
    expect(filterCatalogProducts(products, 'TEST001', null)).toHaveLength(1)
    expect(filterCatalogProducts(products, '', null)).toEqual(products)
  })

  it('matches products by own category id or parent category id', () => {
    expect(filterCatalogProducts(products, '', 1)).toEqual([products[0], products[1]])
    expect(filterCatalogProducts(products, '', 3)).toEqual([products[2]])
  })

  it('applies keyword search and category selection without mutating the catalog', () => {
    const result = filterCatalogProducts(products, '古法', 1)

    expect(result).toEqual([products[0]])
    expect(products).toHaveLength(4)
  })

  it('keeps uncategorized products visible only under the full tab', () => {
    expect(filterCatalogProducts(products, '', 2)).toEqual([])
    expect(filterCatalogProducts(products, '', null)).toEqual(products)
  })

  it('hides sold-out and disabled products even when they remain in the local cache', () => {
    const cached = [
      { goods_id: 10, name: '已售商品', stock: 0, status: 1 },
      { goods_id: 11, name: '已禁用商品', stock: 1, status: 0 },
      { goods_id: 12, name: '可售商品', stock: 1, status: 1 }
    ]

    expect(filterCatalogProducts(cached, '', null)).toEqual([cached[2]])
  })
})
