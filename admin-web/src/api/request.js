import axios from 'axios'
import { ElMessage } from 'element-plus'
const http = axios.create({ baseURL: import.meta.env.VITE_API_BASE || '', timeout: 12000 })
http.interceptors.request.use(config => { const token = localStorage.getItem('dajin_admin_token'); if (token) config.headers.Authorization = `Bearer ${token}`; return config })
function rejectWithMessage(error, fallback) {
  const message = error?.response?.data?.message || error?.message || fallback
  const reason = error instanceof Error ? error : new Error(message)
  reason.message = message
  reason.messageShown = true
  ElMessage.error(message)
  return Promise.reject(reason)
}
http.interceptors.response.use(response => {
  const body = response.data
  if (body?.code !== undefined && body.code !== 200) return rejectWithMessage(new Error(body.message || '请求失败'), '请求失败')
  return body?.data ?? body
}, error => {
  if (error.response?.status === 401) { localStorage.removeItem('dajin_admin_token'); location.hash = '#/login' }
  return rejectWithMessage(error, '网络异常')
})
export default http
