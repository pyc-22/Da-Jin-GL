import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { managerFunctions, salesFunctions, permissionForSection, sectionForRole, tabsForRole } from '../src/config/roles.js'
import { useAuthStore } from '../src/stores/auth.js'

describe('mobile role navigation', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('keeps manager and sales tabs isolated', () => {
    expect(tabsForRole('MANAGER', ['dashboard:view', 'report:view', 'member:view', 'notification:view']).map(x => x.key)).toEqual(['dashboard', 'report', 'member', 'notifications', 'profile'])
    expect(tabsForRole('SALES', ['dashboard:view', 'report:view', 'member:view', 'notification:view']).map(x => x.key)).toEqual(['home', 'performance', 'members', 'notifications', 'profile'])
    expect(tabsForRole('SALES', ['dashboard:view']).some(x => x.key === 'order')).toBe(false)
    expect(tabsForRole('MANAGER', ['*']).some(x => x.key === 'approval')).toBe(false)
    expect(tabsForRole('CASHIER', ['dashboard:view', 'member:view', 'notification:view']).map(x => x.key)).toEqual(['home', 'members', 'notifications', 'profile'])
    expect(managerFunctions.find(x => x.key === 'inbound')?.permission).toBe('stock:inbound:create')
    expect(managerFunctions.find(x => x.key === 'stock-check')?.permission).toBe('stock:check:create')
    expect(salesFunctions.find(x => x.key === 'stock-check')?.permission).toBe('stock:check:create')
  })

  it('opens the member overview entry on the member section used by each role', () => {
    expect(managerFunctions.find(x => x.key === 'member-overview')?.section).toBe('member')
    expect(salesFunctions.find(x => x.key === 'member-overview')?.section).toBe('members')
    expect(sectionForRole('member', 'SALES')).toBe('members')
    expect(sectionForRole('members', 'MANAGER')).toBe('member')
  })

  it('allows sales to count stock without granting approval', () => {
    const auth = useAuthStore()
    auth.user = { role_code: 'SALES', permissions: ['stock:check:view', 'stock:check:create', 'stock:check:submit'] }
    expect(auth.can('stock:check:create')).toBe(true)
    expect(auth.can('stock:check:submit')).toBe(true)
    expect(auth.can('stock:check:approve')).toBe(false)
  })

  it('enforces the stock inbound permission', () => {
    const auth = useAuthStore()
    auth.user = { role_code: 'MANAGER', permissions: '["stock:inbound:create"]' }
    expect(auth.can('stock:inbound:create')).toBe(true)
    auth.user = { role_code: 'SALES', permissions: '[]' }
    expect(auth.can('stock:inbound:create')).toBe(false)
  })

  it('removes a tab as soon as its permission is absent', () => {
    expect(tabsForRole('MANAGER', ['dashboard:view', 'member:view']).map(x => x.key)).toEqual(['dashboard', 'member', 'profile'])
    expect(permissionForSection('report', 'SALES')).toBe('report:view:all')
    expect(permissionForSection('performance', 'SALES')).toBe('report:view')
  })
})
