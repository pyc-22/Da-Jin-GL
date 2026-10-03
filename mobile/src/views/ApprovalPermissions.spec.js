import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { mobileFunctions, permissionForSection } from '../config/roles'
import { canHandleApproval } from '../utils/approvalPermissions'

describe('approval access', () => {
  it('checks the row type, role and pending status', () => {
    const auth = { role: 'MANAGER', can: code => code === 'approval:handle' }
    expect(canHandleApproval(auth, { type: 'MEMBER_CLAIM', status: 1 })).toBe(true)
    expect(canHandleApproval(auth, { type: 'STOCK_CHECK', status: 1 })).toBe(false)
    auth.can = () => true
    expect(canHandleApproval(auth, { type: 'STOCK_CHECK', status: 1 })).toBe(true)
    expect(canHandleApproval(auth, { type: 'STOCK_CHECK', status: 3 })).toBe(false)
    auth.role = 'SALES'
    expect(canHandleApproval(auth, { type: 'REFUND', status: 1 })).toBe(false)
  })
  it('allows read-only and non-stock approval users into the center', () => {
    expect(permissionForSection('approval', 'MANAGER')).toBe('approval:view')
    expect(mobileFunctions.find(item => item.key === 'approval').permission).toBe('approval:view')
    const routes = readFileSync(new URL('../main.js', import.meta.url), 'utf8')
    expect(routes).toMatch(/path: '\/manager\/approval-center'.*permission: 'approval:view'/)
  })
})
