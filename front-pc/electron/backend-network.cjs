const MAX_UPLOAD_BYTES = 10 * 1024 * 1024
const MAX_RESPONSE_BYTES = 10 * 1024 * 1024
const METHODS = new Set(['GET', 'POST', 'PUT', 'PATCH', 'DELETE'])

function backendUrl(base, requestPath) {
  const root = new URL(String(base || ''))
  if (!['http:', 'https:'].includes(root.protocol) || root.username || root.password || root.search || root.hash || root.pathname !== '/') {
    throw new Error('后端 API 地址须为 http(s) 站点根地址')
  }
  if (typeof requestPath !== 'string' || !requestPath.startsWith('/') || requestPath.startsWith('//') || /[\\#]/.test(requestPath)) {
    throw new Error('请求路径无效')
  }
  const url = new URL(requestPath, root)
  if (url.origin !== root.origin || !(url.pathname.startsWith('/api/') || url.pathname === '/actuator/health')) {
    throw new Error('请求路径不在后端接口范围内')
  }
  return url
}

function requestHeaders(value = {}) {
  const headers = {}
  for (const [key, entry] of Object.entries(value)) {
    const name = key.toLowerCase()
    if (!['authorization', 'content-type', 'accept'].includes(name) || typeof entry !== 'string' || /[\r\n]/.test(entry)) {
      throw new Error('请求头无效')
    }
    headers[name] = entry
  }
  return headers
}

async function backendRequest(base, input, fetchImpl = fetch) {
  const url = backendUrl(base, input?.path)
  const method = String(input?.method || 'GET').toUpperCase()
  if (!METHODS.has(method)) throw new Error('请求方法无效')
  if (input?.body != null && typeof input.body !== 'string') throw new Error('请求内容无效')
  if (input?.body && Buffer.byteLength(input.body) > MAX_UPLOAD_BYTES) throw new Error('请求内容过大')
  const timeout = Math.min(Math.max(Number(input?.timeoutMs) || 15000, 500), 30000)
  const response = await fetchImpl(url, {
    method, headers: requestHeaders(input?.headers), body: input?.body,
    redirect: 'manual', signal: AbortSignal.timeout(timeout)
  })
  return { status: response.status, body: await limitedResponseText(response) }
}

async function limitedResponseText(response) {
  if (Number(response.headers.get('content-length') || 0) > MAX_RESPONSE_BYTES) throw new Error('响应内容过大')
  if (!response.body) return ''
  const reader = response.body.getReader()
  const chunks = []
  let size = 0
  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      size += value.byteLength
      if (size > MAX_RESPONSE_BYTES) throw new Error('响应内容过大')
      chunks.push(Buffer.from(value))
    }
  } finally { reader.releaseLock() }
  return Buffer.concat(chunks, size).toString('utf8')
}

async function uploadPhoto(base, input, fetchImpl = fetch) {
  const url = backendUrl(base, '/api/upload')
  const bytes = input?.bytes
  if (!(bytes instanceof Uint8Array) || !bytes.length || bytes.length > MAX_UPLOAD_BYTES) throw new Error('照片大小无效')
  const filename = String(input.filename || 'photo.jpg').slice(0, 200)
  const type = String(input.type || 'application/octet-stream')
  if (!type.startsWith('image/')) throw new Error('仅支持上传图片')
  const form = new FormData()
  form.append('file', new Blob([bytes], { type }), filename)
  form.append('bizType', 'processing')
  if (input.orderNo) form.append('orderNo', String(input.orderNo).slice(0, 100))
  const response = await fetchImpl(url, {
    method: 'POST', headers: requestHeaders({ authorization: input.authorization || '' }),
    body: form, redirect: 'manual', signal: AbortSignal.timeout(30000)
  })
  return { status: response.status, body: await limitedResponseText(response) }
}

function socketUrl(base, token) {
  if (typeof token !== 'string' || !token || token.length > 8192) throw new Error('登录凭据无效')
  const url = backendUrl(base, '/api/auth/login')
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
  url.pathname = '/ws'
  url.search = `token=${encodeURIComponent(token)}`
  return url.toString()
}

module.exports = { backendUrl, backendRequest, uploadPhoto, socketUrl }
