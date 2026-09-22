import { describe, expect, it } from 'vitest'
import { canManageStaffRole, filterAssignableRoles, staffRoleLabel } from './staffRoles'

const roles = [
  { role_code: 'ADMIN', role_name: '系统管理员' },
  { role_code: 'MANAGER', role_name: '店长' },
  { role_code: 'CASHIER', role_name: '前台' },
  { role_code: 'SALES', role_name: '销售' },
  { role_code: 'CRAFTSMAN', role_name: '打金师傅' }
]

describe('staff role assignment policy', () => {
  it('limits managers to sales, cashier and craftsman accounts', () => {
    expect(filterAssignableRoles(roles, 'MANAGER').map(role => role.role_code)).toEqual(['CASHIER', 'SALES', 'CRAFTSMAN'])
    expect(canManageStaffRole('CASHIER', 'MANAGER')).toBe(true)
    expect(canManageStaffRole('SALES', 'MANAGER')).toBe(true)
    expect(canManageStaffRole('CRAFTSMAN', 'MANAGER')).toBe(true)
    expect(canManageStaffRole('MANAGER', 'MANAGER')).toBe(false)
    expect(canManageStaffRole('ADMIN', 'MANAGER')).toBe(false)
  })

  it('keeps all roles available to administrators', () => {
    expect(filterAssignableRoles(roles, 'ADMIN')).toEqual(roles)
  })

  it('uses the requested cashier label', () => {
    expect(staffRoleLabel(roles[2])).toBe('前台收银')
  })

  it('uses the craftsman label', () => {
    expect(staffRoleLabel(roles[4])).toBe('打金师傅')
  })
})
