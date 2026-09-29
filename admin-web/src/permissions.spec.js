import { describe, expect, it } from 'vitest'
import { firstAllowedPage, pagePermissions, redirectForPage } from './permissions'

describe('manager console page permissions', () => {
  it('keeps processing sections separate and finds the first visible destination', () => {
    expect(pagePermissions['processing-orders']).toBe('processing:view')
    expect(pagePermissions['processing-items']).toBe('processing:items')
    expect(pagePermissions['processing-commissions']).toBe('processing:commissions')
    expect(firstAllowedPage(code => code === 'processing:items')).toBe('processing-items')
    expect(firstAllowedPage(() => false)).toBeNull()
    expect(redirectForPage('/system', code => code === 'processing:items')).toBe('/processing-items')
    expect(redirectForPage('/processing-items', code => code === 'processing:items')).toBeNull()
    expect(redirectForPage('/dashboard', () => false)).toBe('/no-access')
  })
})
