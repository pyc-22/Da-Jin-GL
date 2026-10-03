export const pagePermissions = {
  dashboard: 'dashboard:view', goods: 'goods:search', 'gold-price': 'gold:view', stock: 'stock:view',
  approval: 'approval:view', sales: 'order:checkout', member: 'member:view', visits: 'member:follow',
  staff: 'staff:manage', commission: 'report:commission|commission:manage', finance: 'report:daily|report:monthly|report:recycle|shift:confirm', system: 'system:manage',
  'processing-dashboard': 'report:processing', 'processing-orders': 'processing:view',
  'processing-items': 'processing:items', 'processing-commissions': 'processing:commissions', 'processing-loss': 'processing:loss'
}

export const canAccessPage = (page, can) => String(pagePermissions[page] || '').split('|').some(code => can(code))
export const firstAllowedPage = can => Object.keys(pagePermissions).find(page => canAccessPage(page, can)) || null

export function redirectForPage(path, can) {
  const first = firstAllowedPage(can)
  if (path === '/no-access' && !first) return null
  if (path === '/login' || path === '/' || path === '/no-access') return first ? `/${first}` : '/no-access'
  const required = pagePermissions[path.slice(1)]
  return required && !canAccessPage(path.slice(1), can) ? (first ? `/${first}` : '/no-access') : null
}
