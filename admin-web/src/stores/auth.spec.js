// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from './auth'
import { authApi } from '../api/modules'
vi.mock('../api/modules', () => ({ authApi: { me: vi.fn(), login: vi.fn() } }))
beforeEach(() => { localStorage.clear(); setActivePinia(createPinia()); vi.clearAllMocks() })
describe('auth refresh', () => {
  it('coalesces simultaneous refreshes and reuses the valid cache', async () => {
    let resolve
    authApi.me.mockReturnValue(new Promise(done => { resolve = done }))
    const auth = useAuthStore(); auth.token = 'test'
    const first = auth.refresh(); const second = auth.refresh()
    resolve({ user: { role_code: 'MANAGER', permissions: ['stock:view'] } })
    await Promise.all([first, second]); await auth.refresh()
    expect(authApi.me).toHaveBeenCalledTimes(1)
  })
  it('forces a permission event refresh within the cache window', async () => {
    authApi.me.mockResolvedValue({ user: { permissions: ['stock:view'] } })
    const auth = useAuthStore(); auth.token = 'test'
    await auth.refresh()
    authApi.me.mockResolvedValue({ user: { permissions: [] } })
    await auth.refresh(true)
    expect(auth.can('stock:view')).toBe(false)
    expect(authApi.me).toHaveBeenCalledTimes(2)
  })
  it('does not restore an in-flight user after logout', async () => {
    let resolve
    authApi.me.mockReturnValue(new Promise(done => { resolve = done }))
    const auth = useAuthStore(); auth.token = 'test'
    const refresh = auth.refresh(); auth.logout()
    resolve({ user: { permissions: ['*'] } }); await refresh
    expect(auth.user).toBeNull()
    expect(localStorage.getItem('dajin_admin_user')).toBeNull()
  })
})
