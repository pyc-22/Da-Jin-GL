export function filterCatalogProducts(products, keyword = '', categoryId = null) {
  const normalizedKeyword = String(keyword || '').trim().toLowerCase()
  const id = Number(categoryId) || null

  return (products || []).filter(product => {
    const available = product.stock == null || Number(product.stock) > 0
    const enabled = product.status == null || Number(product.status) === 1
    const matchesCategory = !id || Number(product.category_id) === id || Number(product.parent_category_id) === id
    const searchable = `${product.name || ''}${product.barcode || ''}`.toLowerCase()
    return available && enabled && matchesCategory && searchable.includes(normalizedKeyword)
  })
}
