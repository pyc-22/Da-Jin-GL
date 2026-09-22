const TAB_PRESETS = {
  MANAGER: [
    { key: 'dashboard', label: '首页', permission: 'dashboard:view' },
    { key: 'report', label: '报表', permission: 'report:view' },
    { key: 'member', label: '会员', permission: 'member:view' },
    { key: 'notifications', label: '消息', permission: 'notification:view' },
    { key: 'profile', label: '我的' }
  ],
  SALES: [
    { key: 'home', label: '首页', permission: 'dashboard:view' },
    { key: 'performance', label: '报表', permission: 'report:view' },
    { key: 'members', label: '会员', permission: 'member:view' },
    { key: 'notifications', label: '消息', permission: 'notification:view' },
    { key: 'profile', label: '我的' }
  ]
}

const normalizedPermissions = (permissions) => {
  if (Array.isArray(permissions)) return permissions
  try { return JSON.parse(permissions || '[]') } catch { return [] }
}

export const tabsForRole = (role, permissions = []) => {
  const normalizedRole = role === 'ADMIN' ? 'MANAGER' : role
  const granted = normalizedPermissions(permissions)
  return (TAB_PRESETS[normalizedRole] || TAB_PRESETS.SALES).filter(tab => !tab.permission || role === 'ADMIN' || granted.includes('*') || granted.includes(tab.permission))
}

export const permissionForSection = (section, role) => {
  // Legacy section routes keep the stricter manager-only permission; the detailed
  // report page uses the dedicated report:view route for sales accounts.
  if (section === 'report' && role === 'SALES') return 'report:view:all'
  return ({
  dashboard: 'dashboard:view', home: 'dashboard:view', report: 'report:view', performance: 'report:view',
  member: 'member:view', members: 'member:view', goods: 'goods:manage', order: 'order:create',
  inbound: 'stock:inbound:create', stockCheck: 'stock:check:create', notifications: 'notification:view', approval: 'stock:check:approve'
  })[section]
}

// 移动端统一功能清单：所有角色共用，入口显示与否完全由角色权限（管理端-人员-角色权限）控制
export const mobileFunctions = [
  { key: 'order', label: '移动开单', section: 'order', permission: 'order:create' },
  { key: 'approval', label: '审批中心', section: 'approval', permission: 'stock:check:approve' },
  { key: 'goods-manage', label: '货品管理', section: 'goods', permission: 'goods:manage' },
  { key: 'inventory', label: '库存概览', path: '/inventory', permission: 'stock:view' },
  { key: 'inbound', label: '盘点入库', path: '/inbound/create', permission: 'stock:inbound:create' },
  { key: 'stock-check', label: '库存盘点', path: '/stock-check/create', permission: 'stock:check:create' },
  { key: 'goods', label: '货品查询', path: '/goods/search', permission: 'goods:search' },
  { key: 'shift', label: '交班结算', path: '/shift', permission: 'shift:confirm' },
  { key: 'calc', label: '换新报价', path: '/sales/calc', permission: 'order:create' },
  { key: 'recycle', label: '回收登记', path: '/sales/recycle', permission: 'recycle:view' },
  { key: 'processing', label: '加工订单', path: '/processing', permission: 'processing:view' },
  { key: 'todo', label: '待处理', path: '/todo', permission: 'processing:view' },
  { key: 'deposit', label: '客存金台账', path: '/manager/deposit', permission: 'processing:view' },
  { key: 'daily', label: '经营日报', path: '/manager/daily', permission: 'report:view:all' },
  { key: 'report', label: '经营报表', path: '/report', permission: 'report:view' },
  { key: 'member-overview', label: '会员总览', section: 'member', permission: 'member:view' },
  { key: 'visits', label: '客户回访', path: '/visits', permission: 'member:follow' },
  { key: 'birthday', label: '生日提醒', path: '/sales/birthday', permission: 'member:view' },
  { key: 'activity', label: '活动素材', path: '/activity', permission: 'member:follow' }
]

export const sectionForRole = (section, role) => {
  const normalizedRole = role === 'ADMIN' ? 'MANAGER' : role
  if (section === 'member' || section === 'members') {
    return normalizedRole === 'MANAGER' ? 'member' : 'members'
  }
  return section
}

export const managerFunctions = mobileFunctions.map(item => ({
  ...item,
  section: sectionForRole(item.section, 'MANAGER')
}))

export const salesFunctions = mobileFunctions.map(item => ({
  ...item,
  section: sectionForRole(item.section, 'SALES')
}))
