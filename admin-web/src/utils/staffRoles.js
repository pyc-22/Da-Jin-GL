const MANAGER_ASSIGNABLE_ROLES = new Set(['SALES', 'CASHIER', 'CRAFTSMAN'])

export function filterAssignableRoles(roles = [], operatorRole = '') {
  if (String(operatorRole).toUpperCase() === 'ADMIN') return roles
  return roles.filter(role => MANAGER_ASSIGNABLE_ROLES.has(String(role.role_code || '').toUpperCase()))
}

export function canManageStaffRole(targetRole = '', operatorRole = '') {
  if (String(operatorRole).toUpperCase() === 'ADMIN') return true
  return MANAGER_ASSIGNABLE_ROLES.has(String(targetRole).toUpperCase())
}

export function staffRoleLabel(role = {}) {
  return ({ ADMIN: '系统管理员', MANAGER: '店长', CASHIER: '前台收银', SALES: '销售', CRAFTSMAN: '打金师傅' })[String(role.role_code || '').toUpperCase()] || role.role_name || '未命名角色'
}
