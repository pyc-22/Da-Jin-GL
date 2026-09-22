import http from './request'
export const authApi = { login: data => http.post('/api/auth/login', data) }
export const dashboardApi = { get: () => http.get('/api/admin/dashboard'), performance: params => http.get('/api/report/performance', { params }) }
export const goodsApi = { list: params => http.get('/api/goods/list', { params }), create: data => http.post('/api/goods', data), update: (id, data) => http.put(`/api/goods/${id}`, data), status: (id, status) => http.patch(`/api/goods/${id}/status`, null, { params: { status } }), categories: () => http.get('/api/admin/categories'), createCategory: data => http.post('/api/admin/categories', data), updateCategory: (id, data) => http.put(`/api/admin/categories/${id}`, data), deleteCategory: id => http.delete(`/api/admin/categories/${id}`) }
export const goldApi = { current: () => http.get('/api/gold-price/current'), spot: () => http.get('/api/gold-price/spot'), silverSpot: () => http.get('/api/gold-price/spot/silver'), types: () => http.get('/api/gold-price/types'), typesAll: () => http.get('/api/gold-price/types/all'), createType: data => http.post('/api/gold-price/types', data), updateType: (id, data) => http.put(`/api/gold-price/types/${id}`, data), deleteType: id => http.delete(`/api/gold-price/types/${id}`), history: params => http.get('/api/gold-price/history', { params }), save: data => http.post('/api/gold-price', data) }
export const stockApi = { overview: () => http.get('/api/admin/stock/overview'), in: data => http.post('/api/stock/in', data), out: data => http.post('/api/stock/out', data), processIn: data => http.post('/api/stock/process-in', data), processOut: data => http.post('/api/stock/process-out', data), inRecords: () => http.get('/api/admin/stock/in'), outRecords: () => http.get('/api/admin/stock/out'), old: () => http.get('/api/stock/old-material'), createOldMaterial: data => http.post('/api/stock/old-material', data), updateOldMaterial: (id, data) => http.put(`/api/stock/old-material/${id}`, data), oldIn: data => http.post('/api/stock/old-material/in', data), oldOut: data => http.post('/api/stock/old-material/out', data), oldMaterialTypes: () => http.get('/api/stock/old-material/types'), createOldMaterialType: data => http.post('/api/stock/old-material/types', data), updateOldMaterialType: (id, data) => http.put(`/api/stock/old-material/types/${id}`, data), oldMaterialTypeStatus: (id, status) => http.patch(`/api/stock/old-material/types/${id}/status`, null, { params: { status } }), deleteOldMaterialType: id => http.delete(`/api/stock/old-material/types/${id}`), warnings: () => http.get('/api/admin/stock/warnings'), checks: () => http.get('/api/admin/stock/checks'), check: data => http.post('/api/stock/check', data), suppliers: () => http.get('/api/stock/suppliers'), supplierCreate: data => http.post('/api/stock/suppliers', data), supplierUpdate: (id, data) => http.put(`/api/stock/suppliers/${id}`, data) }
export const approvalApi = { pending: () => http.get('/api/approval/pending'), history: () => http.get('/api/admin/approval/history'), detail: id => http.get(`/api/approval/${id}`), approve: (id, body = {}) => http.post(`/api/approval/${id}/approve`, body), reject: (id, remark) => http.post(`/api/approval/${id}/reject`, { remark }) }
export const salesApi = { list: params => http.get('/api/admin/sales', { params }), detail: id => http.get(`/api/order/${id}`), refund: data => http.post('/api/order/refund', data) }
export const memberApi = { list: params => http.get('/api/member/list', { params }), pool: params => http.get('/api/member/pool', { params }), create: data => http.post('/api/member', data), update: (id, data) => http.put(`/api/member/${id}`, data), detail: id => http.get(`/api/member/${id}`), consume: id => http.get(`/api/member/${id}/consume`), balanceRecords: id => http.get(`/api/member/${id}/balance-records`), balance: (id, data) => http.post(`/api/member/${id}/balance`, data), assign: (id, salesId) => http.post(`/api/member/${id}/assign`, { salesId }), claim: id => http.post(`/api/member/${id}/claim`) }
export const visitApi = { tasks: params => http.get('/api/visit/tasks', { params }), stats: () => http.get('/api/visit/stats'), assign: (id, salesId) => http.put(`/api/visit/tasks/${id}/assign`, { salesId }) }
export const staffApi = { list: params => http.get('/api/admin/staff', { params }), detail: id => http.get(`/api/admin/staff/${id}`), roles: () => http.get('/api/admin/roles'), checkUsername: params => http.get('/api/admin/staff/check-username', { params }), create: data => http.post('/api/admin/staff', data), update: (id, data) => http.put(`/api/admin/staff/${id}`, data), status: (id, status) => http.put(`/api/admin/staff/${id}/status`, { status }), delete: id => http.delete(`/api/admin/staff/${id}`), permissionTree: () => http.get('/api/admin/permissions/tree'), userPermissions: id => http.get(`/api/admin/staff/${id}/permissions`), saveUserPermissions: (id, permissions) => http.put(`/api/admin/staff/${id}/permissions`, { permissions }), schedules: params => http.get('/api/admin/schedules', { params }), saveSchedule: data => http.post('/api/admin/schedules', data), deleteSchedule: id => http.delete(`/api/admin/schedules/${id}`) }
export const commissionApi = { rules: () => http.get('/api/admin/commission/rules'), records: params => http.get('/api/admin/commission/records', { params }), save: data => http.post('/api/admin/commission/rules', data), calculate: data => http.post('/api/commission/calculate', data) }
export const financeApi = { daily: params => http.get('/api/report/daily', { params }), monthly: params => http.get('/api/report/monthly', { params }), records: params => http.get('/api/admin/finance/records', { params }), shifts: () => http.get('/api/admin/finance/shifts'), summary: params => http.get('/api/admin/finance/summary', { params }), grossProfit: params => http.get('/api/admin/finance/gross-profit', { params }), recycle: params => http.get('/api/report/recycle', { params }) }
export const systemApi = { store: () => http.get('/api/system/store-info'), updateStore: data => http.put('/api/admin/store', data), config: () => http.get('/api/system/config'), updateConfig: data => http.put('/api/admin/config', data), logs: params => http.get('/api/admin/logs', { params }), templates: () => http.get('/api/system/print-template'), channels: () => http.get('/api/pay/channels'), createChannel: data => http.post('/api/pay/channels', data), updateChannel: (id, data) => http.put(`/api/pay/channels/${id}`, data), deleteChannel: id => http.delete(`/api/pay/channels/${id}`), backup: () => http.post('/api/admin/backups', {}, { timeout: 120000 }) }
export const uploadApi = { image: file => { const form = new FormData(); form.append('file', file); return http.post('/api/upload', form) } }
export const processingApi = {
  categories: params => http.get('/api/processing/categories', { params }),
  createCategory: data => http.post('/api/processing/categories', data),
  updateCategory: (id, data) => http.put(`/api/processing/categories/${id}`, data),
  categoryStatus: (id, status) => http.patch(`/api/processing/categories/${id}/status`, null, { params: { status } }),
  deleteCategory: id => http.delete(`/api/processing/categories/${id}`),
  items: params => http.get('/api/processing/items', { params }),
  createItem: data => http.post('/api/processing/items', data),
  updateItem: (id, data) => http.put(`/api/processing/items/${id}`, data),
  itemStatus: (id, status) => http.patch(`/api/processing/items/${id}/status`, null, { params: { status } }),
  deleteItem: id => http.delete(`/api/processing/items/${id}`),
  craftsmen: () => http.get('/api/processing/craftsmen'),
  orders: params => http.get('/api/processing/orders', { params }),
  detail: id => http.get(`/api/processing/orders/${id}`),
  createOrder: data => http.post('/api/processing/orders', data),
  updateOrder: (id, data) => http.put(`/api/processing/orders/${id}`, data),
  assign: (id, data) => http.post(`/api/processing/orders/${id}/assign`, data),
  updateStatus: (id, data) => http.patch(`/api/processing/orders/${id}/status`, data),
  pay: (id, data) => http.post(`/api/processing/orders/${id}/payments`, data),
  storeGold: (id, data) => http.post(`/api/processing/orders/${id}/store-gold`, data),
  weighing: (id, data) => http.post(`/api/processing/orders/${id}/weighing`, data),
  commissions: params => http.get('/api/processing/commissions', { params }),
  commissionSummary: () => http.get('/api/processing/commissions/summary'),
  generateCommissions: () => http.post('/api/processing/commissions/generate'),
  payCommission: id => http.patch(`/api/processing/commissions/${id}/pay`),
  statistics: params => http.get('/api/processing/statistics', { params }),
  lossSummary: params => http.get('/api/processing/loss-summary', { params }),
  lossOrders: params => http.get('/api/processing/loss-orders', { params }),
  lossConfigGet: () => http.get('/api/processing/loss-config'),
  lossConfigPut: data => http.put('/api/processing/loss-config', data)
}
