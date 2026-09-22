export const PAYMENT_METHOD_LABELS = Object.freeze({
  CASH: '现金',
  WECHAT: '微信',
  ALIPAY: '支付宝',
  BANK: '银行卡',
  BALANCE: '储值',
  COMBINATION: '组合'
})

export const OPERATION_MODULE_LABELS = Object.freeze({
  SYSTEM: '系统设置',
  PAY: '支付结算',
  ORDER: '销售订单',
  ORDER_REQUEST: '订单请求',
  CATEGORY: '商品分类',
  GOODS: '商品管理',
  STOCK: '库存管理',
  STAFF: '人员管理',
  USER: '用户账号',
  USER_PERMISSION: '员工权限',
  ROLE_PERMISSION: '角色权限',
  SCHEDULE: '排班考勤',
  COMMISSION: '提成管理',
  MEMBER: '会员管理',
  APPROVAL: '审批中心',
  PROCESSING: '加工管理',
  SHIFT: '交班结算',
  GOLD_PRICE: '金价管理',
  RECYCLE: '旧料回收',
  OLD_MATERIAL: '旧料管理',
  NOTIFICATION: '消息通知'
})

export const OPERATION_ACTION_LABELS = Object.freeze({
  CREATE: '新增',
  UPDATE: '编辑',
  DELETE: '删除',
  ENABLE: '启用',
  DISABLE: '停用',
  RECORD: '记录',
  OPEN: '创建',
  CANCEL: '取消',
  CANCELLED: '已取消',
  CONFIG_UPDATE: '修改参数',
  BACKUP: '手动备份',
  UPSERT: '保存',
  RULE_CREATE: '新增规则',
  RULE_UPDATE: '编辑规则',
  USER_PERMISSION_UPDATE: '修改员工权限',
  ROLE_PERMISSION_UPDATE: '修改角色权限',
  IN: '入库',
  OUT: '出库',
  RESERVE: '预占库存',
  RELEASE: '释放库存',
  REFUND: '退款',
  CONFIRM: '确认交班',
  READ: '标记已读',
  REMIND: '生成提醒',
  UPDATE_PRICE: '更新金价',
  CREATE_TYPE: '新增贵金属类型',
  UPDATE_TYPE: '编辑贵金属类型',
  ENABLE_TYPE: '启用贵金属类型',
  DISABLE_TYPE: '停用贵金属类型',
  DELETE_TYPE: '删除贵金属类型',
  STOCK_INBOUND_CREATE: '创建入库单',
  CATEGORY_AUTO_CREATE: '自动创建分类',
  STOCK_OUT_APPROVAL: '提交出库审批',
  STOCK_IN: '商品入库',
  STOCK_OUT: '商品出库',
  STOCK_CHECK_SUBMIT: '提交库存盘点',
  STOCK_CHECK_APPROVE: '通过库存盘点',
  STOCK_CHECK_REJECT: '驳回库存盘点',
  OLD_MATERIAL_IN: '旧料入库',
  OLD_MATERIAL_OUT: '旧料出库',
  CATEGORY_CREATE: '新增加工分类',
  CATEGORY_UPDATE: '编辑加工分类',
  CATEGORY_ENABLE: '启用加工分类',
  CATEGORY_DISABLE: '停用加工分类',
  CATEGORY_DELETE: '删除加工分类',
  ITEM_CREATE: '新增加工项目',
  ITEM_UPDATE: '编辑加工项目',
  ITEM_ENABLE: '启用加工项目',
  ITEM_DISABLE: '停用加工项目',
  ITEM_DELETE: '删除加工项目',
  ORDER_CREATE: '创建加工单',
  ORDER_UPDATE: '编辑加工单',
  ORDER_ASSIGN: '分配加工师傅',
  ORDER_STATUS: '更新加工状态',
  ORDER_PAYMENT: '登记加工收款',
  ORDER_STORE_GOLD: '登记补金',
  ORDER_WEIGHING: '登记称重损耗',
  PRINT_REQUEST: '申请打印',
  PROCESSING_HANDOVER: '转交前台',
  PRINT_JOB_DONE: '完成打印',
  PRINT_JOB_IGNORE: '忽略打印',
  COMMISSION_PAY: '发放加工提成',
  RESIDUAL_MATERIAL_IN: '剩余旧料入库',
  PROCESSING_READY: '发送取货通知'
})

export const FINANCE_TYPE_LABELS = Object.freeze({
  INCOME: '收入',
  EXPENSE: '支出'
})

export const FINANCE_BUSINESS_LABELS = Object.freeze({
  SALE_INCOME: '销售收款',
  PROCESSING_INCOME: '加工收款',
  OTHER_INCOME: '其他收入',
  SALE_REFUND: '销售退款',
  RECYCLE_EXPENSE: '回收付款',
  OTHER_EXPENSE: '其他支出'
})

const APPROVAL_REASON_LABELS = Object.freeze({
  DISCOUNT: '折扣低于审批阈值',
  REFUND: '退货退款申请',
  RECYCLE: '大额回收审批申请',
  STOCK_CHECK: '库存盘点差异审批',
  TRADE_IN: '以旧换新审批申请',
  STOCK_OUT: '手动出库审批'
})

const EMPTY_VALUES = new Set([null, undefined, '', 0, '0'])

function pad(value) {
  return String(value).padStart(2, '0')
}

/** Render backend UTC timestamps consistently for every management screen. */
export function formatTime(value) {
  if (value === null || value === undefined || value === '') return '-'
  if (typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value)) return value

  let source = value
  if (typeof value === 'string' && !/[zZ]$|[+-]\d{2}:?\d{2}$/.test(value)) {
    source = `${value.replace(' ', 'T')}Z`
  }

  const date = value instanceof Date ? value : new Date(source)
  if (Number.isNaN(date.getTime())) return '-'

  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23'
  }).formatToParts(date).reduce((result, part) => ({ ...result, [part.type]: part.value }), {})
  return `${parts.year}-${pad(parts.month)}-${pad(parts.day)} ${pad(parts.hour)}:${pad(parts.minute)}:${pad(parts.second)}`
}

export function formatMoney(value) {
  const amount = Number(value)
  return `¥${Number.isFinite(amount) ? amount.toFixed(2) : '0.00'}`
}

function formatSignedMoney(value) {
  const amount = Number(value)
  if (!Number.isFinite(amount)) return '¥0.00'
  return amount < 0 ? `-¥${Math.abs(amount).toFixed(2)}` : `¥${amount.toFixed(2)}`
}

function parseShiftContent(value) {
  const text = String(value ?? '').trim()
  if (!text.startsWith('{') || !text.endsWith('}')) return null

  try {
    const parsed = JSON.parse(text)
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) return parsed
  } catch {
    // Current backend records are persisted with Java Map.toString(), not JSON.
  }

  const fields = {}
  const body = text.slice(1, -1)
  const fieldPattern = /(shiftNo|cashSystem|cashActual|cashDifference|difference|remark)=([\s\S]*?)(?=,\s*[A-Za-z][A-Za-z0-9_]*=|$)/g
  for (const match of body.matchAll(fieldPattern)) fields[match[1]] = match[2].trim()
  return Object.keys(fields).length ? fields : null
}

export function formatShiftContent(value) {
  const text = String(value ?? '').trim()
  if (!text) return '-'
  const content = parseShiftContent(text)
  if (!content) return text

  const recordedDifference = content.cashDifference ?? content.difference
  const difference = recordedDifference ?? (Number(content.cashActual || 0) - Number(content.cashSystem || 0))
  return [
    `班次：${content.shiftNo || '-'}`,
    `系统现金：${formatSignedMoney(content.cashSystem)}`,
    `实点现金：${formatSignedMoney(content.cashActual)}`,
    `现金差额：${formatSignedMoney(difference)}`,
    `备注：${String(content.remark ?? '').trim() || '无'}`
  ].join('；')
}

export function formatPaymentMethod(value, channels = []) {
  if (EMPTY_VALUES.has(value)) return '-'
  const dynamicLabels = Object.fromEntries((Array.isArray(channels) ? channels : []).map(channel => [
    String(channel.channel_code ?? channel.channelCode ?? '').toUpperCase(),
    channel.channel_name ?? channel.channelName
  ]).filter(([code, name]) => code && name))
  return String(value).split('+').map((part) => {
    // Older finance records may persist the display amount as `CASH:¥100.00`.
    // The payment column intentionally renders only the normalized channel name.
    const [code] = part.trim().split(':')
    const normalized = code.toUpperCase()
    const label = dynamicLabels[normalized] || PAYMENT_METHOD_LABELS[normalized] || code
    return label
  }).join('+') || '-'
}

export function formatOperationModule(value) {
  const code = String(value ?? '').trim()
  return OPERATION_MODULE_LABELS[code.toUpperCase()] || code || '-'
}

export function formatOperationAction(value) {
  const code = String(value ?? '').trim()
  return OPERATION_ACTION_LABELS[code.toUpperCase()] || code || '-'
}

export function formatFinanceType(value) {
  if (EMPTY_VALUES.has(value)) return '-'
  const code = String(value).trim()
  return FINANCE_TYPE_LABELS[code.toUpperCase()] || code
}

export function financeBusinessType(record = {}) {
  const source = record || {}
  if (source.business_type) return String(source.business_type).toUpperCase()
  const type = String(source.type || '').toUpperCase()
  const category = String(source.category || '').toUpperCase()
  if (type === 'EXPENSE' && category === 'SALE_REFUND') return 'SALE_REFUND'
  if (type === 'EXPENSE' && category === 'RECYCLE') return 'RECYCLE_EXPENSE'
  if (type === 'EXPENSE') return 'OTHER_EXPENSE'
  if (category === 'SALE' || String(source.related_bill_no || '').toUpperCase().startsWith('XS')) return 'SALE_INCOME'
  if (category === 'PROCESSING_FEE' || String(source.related_bill_no || '').toUpperCase().startsWith('JG')) return 'PROCESSING_INCOME'
  return 'OTHER_INCOME'
}

export function formatFinanceBusiness(record) {
  const code = typeof record === 'string' ? record.toUpperCase() : financeBusinessType(record)
  return FINANCE_BUSINESS_LABELS[code] || code || '-'
}

export function formatApprovalReason(record = {}) {
  const source = record || {}
  const reason = String(source.reason ?? '').trim()
  const fallback = APPROVAL_REASON_LABELS[String(source.type ?? '').toUpperCase()] || '业务审批申请'
  if (!reason) return fallback
  if (/^[{[]/.test(reason) || !/[\u3400-\u9fff]/.test(reason)) return fallback
  return reason
}

export const ORDER_STATUS = Object.freeze({
  0: { label: '待结算', type: 'info' },
  1: { label: '已完成', type: 'success' },
  2: { label: '已退单', type: 'danger' },
  3: { label: '待审批', type: 'warning' },
  4: { label: '已取消', type: 'info' }
})

export function orderStatus(value) {
  return ORDER_STATUS[Number(value)] || { label: '-', type: 'info' }
}
