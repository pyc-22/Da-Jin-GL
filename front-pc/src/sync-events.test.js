import { describe, expect, it } from 'vitest'
import { shouldRefreshCatalog, shouldRefreshProcessingReferences } from './sync-events'

describe('cashier catalog realtime sync', () => {
  it('refreshes after a goods master or price update', () => {
    expect(shouldRefreshCatalog('GOODS_UPDATED')).toBe(true)
  })

  it('refreshes after stock changes that affect sale availability', () => {
    expect(shouldRefreshCatalog('STOCK_IN_COMPLETED')).toBe(true)
    expect(shouldRefreshCatalog('STOCK_UPDATED')).toBe(true)
    expect(shouldRefreshCatalog('ORDER_COMPLETED')).toBe(true)
  })

  it('ignores unrelated events', () => {
    expect(shouldRefreshCatalog('MEMBER_UPDATED')).toBe(false)
  })

  it('refreshes processing projects and craftsmen after reference changes', () => {
    expect(shouldRefreshProcessingReferences('PROCESSING_CATALOG_UPDATED')).toBe(true)
    expect(shouldRefreshProcessingReferences('STAFF_UPDATED')).toBe(true)
    expect(shouldRefreshProcessingReferences('MEMBER_UPDATED')).toBe(false)
  })
})
