const CATALOG_REFRESH_EVENTS = new Set([
  'GOODS_UPDATED',
  'STOCK_IN_COMPLETED',
  'STOCK_UPDATED',
  'ORDER_COMPLETED'
])

export function shouldRefreshCatalog(eventType) {
  return CATALOG_REFRESH_EVENTS.has(String(eventType || ''))
}

const PROCESSING_REFERENCE_REFRESH_EVENTS = new Set([
  'PROCESSING_CATALOG_UPDATED',
  'STAFF_UPDATED'
])

export function shouldRefreshProcessingReferences(eventType) {
  return PROCESSING_REFERENCE_REFRESH_EVENTS.has(String(eventType || ''))
}
