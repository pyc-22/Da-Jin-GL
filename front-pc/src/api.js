const isLocalPage = ['localhost', '127.0.0.1'].includes(location.hostname) || location.protocol === 'file:'
let API_BASE = (localStorage.getItem('dajin_api_base') || (isLocalPage ? 'http://127.0.0.1:18080' : '')).replace(/\/$/, '')
let unauthorizedNotified = false
console.info('[dajin-api] baseURL:', API_BASE)

export function getToken() { return localStorage.getItem('dajin_token') || '' }
export function setToken(token) {
  if (token) {
    localStorage.setItem('dajin_token', token)
    unauthorizedNotified = false
  } else localStorage.removeItem('dajin_token')
}
export function apiBase() { return API_BASE }
export function setApiBase(value) {
  const next = String(value || '').trim().replace(/\/$/, '')
  if (!next) return API_BASE
  API_BASE = next
  localStorage.setItem('dajin_api_base', API_BASE)
  console.info('[dajin-api] baseURL updated:', API_BASE)
  return API_BASE
}
export async function fetchBackend(path, options = {}) {
  if (window.dajin?.network?.request) {
    const result = await window.dajin.network.request({
      path, method: options.method || 'GET', headers: options.headers || {},
      body: options.body, timeoutMs: options.timeoutMs
    })
    return new Response(result.status === 204 ? null : result.body, { status: result.status })
  }
  return fetch(`${API_BASE}${path}`, options)
}

export async function uploadBackendPhoto(file, orderNo) {
  if (window.dajin?.network?.uploadPhoto) {
    const result = await window.dajin.network.uploadPhoto({
      bytes: new Uint8Array(await file.arrayBuffer()), filename: file.name,
      type: file.type, orderNo, authorization: `Bearer ${getToken()}`
    })
    return new Response(result.status === 204 ? null : result.body, { status: result.status })
  }
  const form = new FormData()
  form.append('file', file)
  form.append('bizType', 'processing')
  if (orderNo) form.append('orderNo', orderNo)
  return fetchBackend('/api/upload', { method: 'POST', headers: { Authorization: `Bearer ${getToken()}` }, body: form })
}

export function openBackendSocket(handlers) {
  if (window.dajin?.network?.openSocket) return window.dajin.network.openSocket(getToken(), handlers)
  const socket = new WebSocket(wsUrl())
  Object.assign(socket, handlers)
  return socket
}

export async function request(path, options = {}) {
  const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) }
  const token = getToken()
  if (token) headers.Authorization = `Bearer ${token}`
  const url = `${API_BASE}${path}`
  const method = String(options.method || 'GET').toUpperCase()
  const started = performance.now()
  try {
    const response = await fetchBackend(path, { ...options, headers })
    const elapsed = Math.round(performance.now() - started)
    console.info(`[dajin-api] ${method} ${url} -> ${response.status} (${elapsed}ms)`)
    const body = await response.json().catch(() => ({}))
    if (response.status === 401 && token && !unauthorizedNotified) {
      unauthorizedNotified = true
      setToken('')
      localStorage.removeItem('dajin_user')
      window.dispatchEvent(new Event('dajin:unauthorized'))
    }
    if (!response.ok || body.code && body.code !== 200) throw Object.assign(new Error(body.message || `请求失败 (${response.status})`), { status: response.status, body })
    return body.data
  } catch (error) {
    if (error?.status == null) console.warn(`[dajin-api] ${method} ${url} -> network error`, error?.message || error)
    throw error
  }
}

export async function login(username, password) {
  const data = await request('/api/auth/login', { method: 'POST', body: JSON.stringify({ username, password, clientType: 'FRONT_PC' }) })
  setToken(data.token)
  localStorage.setItem('dajin_user', JSON.stringify(data.user || {}))
  return data
}

export function wsUrl() {
  const url = new URL(API_BASE || location.origin)
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
  url.pathname = '/ws'
  url.search = `token=${encodeURIComponent(getToken())}`
  return url.toString()
}
