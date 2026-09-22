// @vitest-environment jsdom
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({ currentUser: vi.fn() }))
vi.mock('../api/request.js', () => ({ api: { currentUser: mocks.currentUser } }))

import { useAuthStore } from './auth.js'

describe('auth session refresh', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    mocks.currentUser.mockReset()
  })

  it('replaces cached permissions with the latest server permissions', async () => {
    const auth = useAuthStore()
    auth.token = 'token'
    auth.user = { user_id: 8, role_code: 'SALES', permissions: ['report:view', 'stock:view'] }
    mocks.currentUser.mockResolvedValue({
      user: { user_id: 8, role_code: 'SALES', permissions: ['stock:view'] },
      permissions: ['stock:view']
    })
    await auth.refreshSession()
    expect(auth.permissions).toEqual(['stock:view'])
    expect(auth.can('report:view')).toBe(false)
    expect(JSON.parse(localStorage.getItem('dajin-user')).permissions).toEqual(['stock:view'])
  })
})
