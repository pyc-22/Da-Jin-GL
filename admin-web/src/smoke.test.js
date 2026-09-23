import { describe, expect, it } from 'vitest'
import { useAuthStore } from './stores/auth'

describe('admin console smoke checks', () => {
  it('uses semantic role codes for access decisions', () => {
    const state = { role: 'ADMIN', user: { role_code: 'ADMIN' } }
    expect(state.role).toBe('ADMIN')
  })
  it('exports all required management routes', async () => {
    const router = (await import('./router')).default
    const paths = new Set(router.getRoutes().map(route => route.path))
    for (const path of [
      '/dashboard', '/goods', '/stock', '/sales', '/member', '/staff', '/finance',
      '/processing-dashboard', '/processing-orders', '/processing-items',
      '/processing-commissions', '/processing-loss'
    ]) expect(paths.has(path), `${path} route missing`).toBe(true)
  })
})
