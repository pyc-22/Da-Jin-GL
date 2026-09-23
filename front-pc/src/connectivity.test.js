import { describe, expect, it } from 'vitest'
import { isBackendOnline } from './connectivity'

describe('cashier backend connectivity', () => {
  it('reports online when the local backend heartbeat succeeds', () => {
    expect(isBackendOnline({ browserOnline: false, backendReachable: true })).toBe(true)
  })

  it('reports offline when the backend heartbeat fails', () => {
    expect(isBackendOnline({ browserOnline: true, backendReachable: false })).toBe(false)
  })
})
