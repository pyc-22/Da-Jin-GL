import axios from 'axios'
import { useAuthStore } from '../stores/auth.js'

export const http = axios.create({ baseURL: import.meta.env.VITE_API_BASE || '', timeout: 10000 })
http.interceptors.request.use((config) => {
  const token = useAuthStore().token
  config._sessionToken = token
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})
http.interceptors.response.use((response) => {
  const body = response.data
  if (body && body.code !== undefined && body.code !== 200) {
    const error = new Error(body.message || '请求失败')
    error.response = response
    return Promise.reject(error)
  }
  return body?.data ?? body
}, (error) => {
  const original = error.config
  if (error.response?.status === 401 && original && !original._retried) {
    const auth = useAuthStore()
    // A late response from the previous account must not sign out the new account.
    if (!original._sessionToken || original._sessionToken !== auth.token) return Promise.reject(error)
    if (auth.refreshToken) return refreshAndRetry(auth, original)
    forceRelogin(auth)
  }
  return Promise.reject(error)
})

async function refreshAndRetry(auth, original) {
  original._retried = true
  const sessionToken = auth.token
  try {
    const res = await axios.post(`${http.defaults.baseURL}/api/auth/refresh`, { refreshToken: auth.refreshToken })
    const data = res.data?.data ?? res.data
    if (auth.token !== sessionToken) throw new Error('账号已切换')
    if (!data?.token) throw new Error('no token')
    auth.token = data.token
    if (data.refreshToken) auth.refreshToken = data.refreshToken
    auth.persist()
    original.headers = { ...original.headers, Authorization: `Bearer ${data.token}` }
    return http(original)
  } catch {
    if (auth.token === sessionToken) forceRelogin(auth)
    return Promise.reject(new Error('登录已过期，请重新登录'))
  }
}

function forceRelogin(auth) {
  auth.logout()
  if (typeof window !== 'undefined' && !window.location.pathname.startsWith('/login')) window.location.replace('/login')
}

export const api = {
  login: (payload) => http.post('/api/user/login', { ...payload, clientType: payload.clientType || 'MOBILE' }),
  currentUser: () => http.get('/api/user/me'),
  health: () => http.get('/actuator/health'),
  gold: () => http.get('/api/gold-price/current'),
  dashboard: () => http.get('/api/admin/dashboard'),
  performance: (params = {}) => http.get('/api/report/performance', { params }),
  approvals: () => http.get('/api/approval/pending'),
  approve: (id, remark, confirmation = {}) => http.post(`/api/approval/${id}/approve`, { ...confirmation, remark }),
  reject: (id, reason) => http.post(`/api/approval/${id}/reject`, { remark: reason }),
  members: (params = {}) => http.get('/api/member/list', { params }),
  member: (id) => http.get(`/api/member/${id}`),
  claimMember: (id) => http.post(`/api/member/${id}/claim`),
  consume: (id) => http.get(`/api/member/${id}/consume`),
  visits: (params = {}) => http.get('/api/visit/tasks', { params }),
  visitStats: () => http.get('/api/visit/stats'),
  startVisitCall: (taskId) => http.post(`/api/visit/tasks/${taskId}/call`),
  completeVisit: (id, record) => http.post('/api/visit/complete', { id, record }),
  recordVisit: (taskId, record, nextFollowUp, callResult, callStartedAt) => http.post('/api/visit/record', { taskId, record, nextFollowUp, callResult, callStartedAt }),
  markNotificationRead: (id) => http.post(`/api/notification/${id}/read`),
  notifications: () => http.get('/api/notification'),
  // The overview endpoint is the canonical source for weekly ranges. There is
  // no separate /weekly resource, so keep this compatibility helper aligned
  // with the report dashboard instead of returning a misleading 404.
  reports: (period = 'daily') => period === 'weekly'
    ? http.get('/api/report/overview', { params: { timeType: 'week' } })
    : http.get(`/api/report/${period}`),
  commission: () => http.get('/api/report/commission'),
  stock: () => http.get('/api/admin/stock/overview'),
  warnings: () => http.get('/api/admin/stock/warnings'),
  goods: (params = {}) => http.get('/api/goods/list', { params }),
  updateGoodsImages: (id, images) => http.patch(`/api/goods/${id}/images`, { images }),
  createOrder: (payload) => http.post('/api/order/create', payload),
  payMethods: () => http.get('/api/pay/methods'),
  pay: (payload) => http.post('/api/pay/pay', payload),
  memberPool: (params = {}) => http.get('/api/member/pool', { params }),
  goldHistory: (days = 30) => http.get('/api/gold-price/history', { params: { days } }),
  categoryProfit: () => http.get('/api/admin/finance/gross-profit'),
  changePassword: (payload) => http.post('/api/auth/change-password', payload),
  scanGoods: (barcode) => http.get(`/api/goods/scan/${encodeURIComponent(barcode)}`),
  approvalDetail: (id) => http.get(`/api/approval/${id}`),
  approvalHistory: () => http.get('/api/admin/approval/history'),
  orderDetail: (id) => http.get(`/api/order/${id}`),
  shiftInfo: () => http.get('/api/shift/info'),
  shiftConfirm: (payload) => http.post('/api/shift/confirm', payload),
  shiftRecords: () => http.get('/api/admin/finance/shifts'),
  paySummary: () => http.get('/api/admin/finance/summary'),
  recycleStats: (params = {}) => http.get('/api/report/recycle', { params }),
  reportOverview: (params = {}) => http.get('/api/report/overview', { params }),
  reportSales: (params = {}) => http.get('/api/report/sales', { params }),
  reportEmployee: (params = {}) => http.get('/api/report/employee', { params }),
  reportRecycle: (params = {}) => http.get('/api/report/recycle-detail', { params }),
  reportMember: (params = {}) => http.get('/api/report/member', { params }),
  reportGold: (params = {}) => http.get('/api/report/gold', { params }),
  stockChecks: () => http.get('/api/admin/stock/checks'),
  oldMaterial: () => http.get('/api/stock/old-material'),
  oldMaterialTypes: () => http.get('/api/stock/old-material/types'),
  oldMaterialIn: (payload) => http.post('/api/stock/old-material/in', payload),
  oldMaterialOut: (payload) => http.post('/api/stock/old-material/out', payload)
  ,goodsByBarcode: (barcode, storeId, pieceNo) => http.get('/api/stock/goods', { params: { ...(barcode ? { barcode } : {}), ...(storeId ? { storeId } : {}), ...(pieceNo ? { pieceNo } : {}) } })
  ,goodsByPieceNo: (pieceNo) => http.get('/api/stock/piece', { params: { pieceNo } })
  ,createInbound: (payload) => http.post('/api/stock/stock-in', payload)
  ,inboundDetail: (id) => http.get(`/api/stock/stock-in/${id}`)
  ,inboundHistory: () => http.get('/api/stock/stock-in/history')
  ,pendingInbounds: () => http.get('/api/stock/stock-in/pending')
  ,goodsCategories: () => http.get('/api/goods/categories')
  ,inboundStores: () => http.get('/api/stock/inbound/stores')
  ,inboundSuppliers: (storeId) => http.get('/api/stock/inbound/suppliers', { params: storeId ? { storeId } : {} })
  ,suppliers: () => http.get('/api/stock/suppliers')
  ,supplierCreate: (payload) => http.post('/api/stock/suppliers', payload)
  ,supplierUpdate: (id, payload) => http.put(`/api/stock/suppliers/${id}`, payload)
  ,stockCheckScopeGoods: (scopeType, scopeId) => http.get('/api/stock/check/scope-goods', { params: { scopeType, ...(scopeId ? { scopeId } : {}) } })
  ,createStockCheck: (payload) => http.post('/api/stock/check', payload)
  ,stockCheckHistory: () => http.get('/api/stock/check/history')
  ,stockCheckDetail: (id) => http.get(`/api/stock/check/${id}`)
  ,processingOrders: (params = {}) => http.get('/api/processing/orders', { params })
  ,processingOrder: (id) => http.get(`/api/processing/orders/${id}`)
  ,processingStatistics: (params = {}) => http.get('/api/processing/statistics', { params })
  ,processingStatus: (id, status) => http.patch(`/api/processing/orders/${id}/status`, { status })
  ,processingNotify: (id) => http.post(`/api/processing/orders/${id}/notify`)
  ,processingCreate: (payload) => http.post('/api/processing/orders', payload)
  ,processingPay: (id, payload) => http.post(`/api/processing/orders/${id}/payments`, payload)
  ,processingPhotos: (id, payload) => http.post(`/api/processing/orders/${id}/photos`, payload)
  ,processingHandover: (id) => http.post(`/api/processing/orders/${id}/handover`)
  ,processingItems: (params = {}) => http.get('/api/processing/items', { params })
  ,processingCraftsmen: () => http.get('/api/processing/craftsmen')
  ,goldSpot: () => http.get('/api/gold-price/spot')
  ,systemTarget: () => http.get('/api/system/target')
  ,setTarget: (value) => http.put('/api/system/target', { monthlySalesTarget: value })
  ,tradeInCreate: (payload) => http.post('/api/trade-in/create', payload)
  ,recycleCreate: (payload) => http.post('/api/recycle/create', payload)
  ,memberCreate: (payload) => http.post('/api/member', payload)
  ,visitCreateTask: (payload) => http.post('/api/visit/tasks', payload)
}
