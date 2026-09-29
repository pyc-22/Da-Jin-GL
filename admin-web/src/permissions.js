export const pagePermissions = {
  dashboard: 'dashboard:view', goods: 'goods:search', 'gold-price': 'gold:view', stock: 'stock:view',
  approval: 'approval:view', sales: 'order:checkout', member: 'member:view', visits: 'member:follow',
  staff: 'staff:manage', commission: 'report:view:all', finance: 'report:view:all', system: 'system:manage',
  'processing-dashboard': 'processing:view', 'processing-orders': 'processing:view',
  'processing-items': 'processing:items', 'processing-commissions': 'processing:commissions', 'processing-loss': 'processing:loss'
}

export const firstAllowedPage = can => Object.keys(pagePermissions).find(page => can(pagePermissions[page])) || null

export function redirectForPage(path, can) {
  const first = firstAllowedPage(can)
  if (path === '/no-access' && !first) return null
  if (path === '/login' || path === '/' || path === '/no-access') return first ? `/${first}` : '/no-access'
  const required = pagePermissions[path.slice(1)]
  return required && !can(required) ? (first ? `/${first}` : '/no-access') : null
}
