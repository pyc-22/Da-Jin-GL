import { request } from './api'

const nativeDb = () => window.dajin?.db
export const uuid = () => globalThis.crypto?.randomUUID?.() || `req-${Date.now()}-${Math.random().toString(16).slice(2)}`

export async function localProducts(keyword = '') {
  if (nativeDb()) { try { return await nativeDb().products(keyword) } catch (error) { console.warn('SQLite unavailable, using browser cache', error.message) } }
  return JSON.parse(localStorage.getItem('dajin_products') || '[]').filter(p => `${p.name}${p.barcode}`.includes(keyword))
}
export async function localGold() {
  if (nativeDb()) { try { return await nativeDb().gold() } catch (error) { console.warn('SQLite unavailable, using browser cache', error.message) } }
  return JSON.parse(localStorage.getItem('dajin_gold') || '[{"price_type":"足金","price":612},{"price_type":"回收金价","price":578}]')
}
export async function localMembers(keyword = '') {
  if (nativeDb()) { try { return await nativeDb().members(keyword) } catch (error) { console.warn('SQLite unavailable, using browser cache', error.message) } }
  return JSON.parse(localStorage.getItem('dajin_members') || '[]').filter(m => `${m.name}${m.phone}`.includes(keyword))
}

export async function enqueue(path, payload, method = 'POST', version = null) {
  const clientRequestId = payload?.clientRequestId || uuid()
  const item = { clientRequestId, method, path, payload: { ...payload, clientRequestId }, version }
  if (nativeDb()) {
    try { await nativeDb().enqueue(item) }
    catch { const queue = JSON.parse(localStorage.getItem('dajin_queue') || '[]'); queue.push(item); localStorage.setItem('dajin_queue', JSON.stringify(queue)); localStorage.setItem('dajin_sqlite_warning', 'native SQLite unavailable') }
  }
  else {
    const queue = JSON.parse(localStorage.getItem('dajin_queue') || '[]')
    queue.push(item); localStorage.setItem('dajin_queue', JSON.stringify(queue))
  }
  return item.clientRequestId
}

export async function enqueueWithId(path, payload, clientRequestId, method = 'POST', version = null) {
  const item = { clientRequestId, method, path, payload, version }
  if (nativeDb()) {
    try { await nativeDb().enqueue(item) }
    catch { const queue = JSON.parse(localStorage.getItem('dajin_queue') || '[]'); queue.push(item); localStorage.setItem('dajin_queue', JSON.stringify(queue)); localStorage.setItem('dajin_sqlite_warning', 'native SQLite unavailable') }
  }
  else { const queue = JSON.parse(localStorage.getItem('dajin_queue') || '[]'); queue.push(item); localStorage.setItem('dajin_queue', JSON.stringify(queue)) }
  return clientRequestId
}

export async function requestOrQueue(path, payload, options = {}) {
  const method = options.method || 'POST'
  const clientRequestId = payload.clientRequestId || uuid()
  const body = { ...payload, clientRequestId }
  if (options.backendAvailable === false) { await enqueueWithId(path, body, clientRequestId, method, options.version); return { queued: true, clientRequestId } }
  try { return await request(path, { method, body: JSON.stringify(body) }) }
  catch (error) {
    if (error.status === 409) {
      const conflict = { id: Date.now() + Math.floor(Math.random() * 1000), queueId: null, clientRequestId, path, localPayload: body, serverPayload: JSON.stringify(error.body || {}), reason: error.message }
      if (nativeDb()) await nativeDb().conflict(conflict)
      else {
        const conflicts = (() => { try { return JSON.parse(localStorage.getItem('dajin_conflicts') || '[]') } catch { return [] } })()
        localStorage.setItem('dajin_conflicts', JSON.stringify([...conflicts, conflict]))
      }
      throw Object.assign(error, { conflict: true, clientRequestId })
    }
    // ApiResponse business failures are definitive. Queue only connectivity failures;
    // otherwise a rejected payment or invalid order would be retried offline forever.
    const businessCode = Number(error.body?.code || 0)
    if (error.status != null || (businessCode && businessCode !== 200)) throw error
    // A short retry prevents transient restarts or Wi-Fi handoffs from being treated as offline mode.
    try {
      await new Promise(resolve => setTimeout(resolve, 250))
      return await request(path, { method, body: JSON.stringify(body) })
    } catch (retryError) {
      const retryBusinessCode = Number(retryError.body?.code || 0)
      if (retryError.status != null || (retryBusinessCode && retryBusinessCode !== 200)) throw retryError
    }
    await enqueueWithId(path, body, clientRequestId, method, options.version)
    return { queued: true, clientRequestId }
  }
}

function browserQueue() {
  try { return JSON.parse(localStorage.getItem('dajin_queue') || '[]') } catch { return [] }
}

function saveBrowserQueue(queue) { localStorage.setItem('dajin_queue', JSON.stringify(queue)) }

let queueWork = Promise.resolve()
function withQueueLock(work) {
  const next = queueWork.then(work, work)
  queueWork = next.catch(() => {})
  return next
}

export function cancelQueuedOrder(orderClientRequestId) {
  return withQueueLock(async () => {
    if (!orderClientRequestId) throw new Error('缺少本地订单编号，无法取消')
    const clientRequestId = uuid()
    const cancellation = { clientRequestId, method: 'POST', path: '/api/order/cancel-by-client', payload: { clientRequestId, orderClientRequestId, reason: '收银端取消离线订单' } }
    // Keep a durable cancellation even if an earlier create timed out after reaching the server.
    if (nativeDb()) await nativeDb().cancelOrder(orderClientRequestId, cancellation)
    const remaining = browserQueue().filter(item => item.clientRequestId !== orderClientRequestId && item.payload?.orderClientRequestId !== orderClientRequestId)
    if (!nativeDb()) remaining.push(cancellation)
    saveBrowserQueue(remaining)
    const conflicts = JSON.parse(localStorage.getItem('dajin_conflicts') || '[]')
    localStorage.setItem('dajin_conflicts', JSON.stringify(conflicts.filter(item => item.clientRequestId !== orderClientRequestId)))
    return true
  })
}

export function syncQueue(onConflict, backendAvailable = true) {
  return withQueueLock(() => syncPendingQueue(onConflict, backendAvailable))
}

async function syncPendingQueue(onConflict, backendAvailable) {
  if (!backendAvailable) return { synced: 0, conflicts: 0 }
  const queue = nativeDb() ? (await nativeDb().queue()).map(item => ({ ...item, native: true })) : []
  queue.push(...browserQueue().filter(item => ['PENDING', 'RETRY', undefined].includes(item.status)))
  let synced = 0; let conflicts = 0
  const resolvedOrders = new Map()
  for (const item of queue) {
    const useNative = item.native === true
    const clientRequestId = item.client_request_id || item.clientRequestId || item.payload?.clientRequestId || uuid()
    try {
      const requestPayload = { ...item.payload, clientRequestId, expectedVersion: item.version }
      if (requestPayload.orderClientRequestId && resolvedOrders.has(requestPayload.orderClientRequestId)) requestPayload.orderId = resolvedOrders.get(requestPayload.orderClientRequestId)
      const result = await request(item.path, { method: item.method, body: JSON.stringify(requestPayload) })
      const resolvedOrderId = result?.orderId ?? result?.order_id ?? result?.id
      if (item.path === '/api/order/create' && resolvedOrderId && requestPayload.clientRequestId) resolvedOrders.set(requestPayload.clientRequestId, resolvedOrderId)
      if (useNative) await nativeDb().queueDone(item.id, result)
      else saveBrowserQueue(browserQueue().filter(candidate => candidate.clientRequestId !== clientRequestId))
      synced++
    } catch (error) {
      const businessCode = Number(error.body?.code || 0)
      // Inventory/version failures require an explicit operator choice; automatic retries could
      // otherwise keep a payment queued forever after another terminal sold the item.
      const requiresManualResolution = error.status === 409 || error.conflict || businessCode === 409103
      if (requiresManualResolution) {
        conflicts++
        const conflict = { id: Date.now() + Math.floor(Math.random() * 1000), queueId: item.id ?? null, clientRequestId: item.client_request_id || item.clientRequestId, path: item.path, localPayload: item.payload, serverPayload: JSON.stringify(error.body || {}), reason: error.message }
        if (useNative) await nativeDb().conflict(conflict)
        else {
          const current = (() => { try { return JSON.parse(localStorage.getItem('dajin_conflicts') || '[]') } catch { return [] } })()
          localStorage.setItem('dajin_conflicts', JSON.stringify([...current, conflict]))
          saveBrowserQueue(browserQueue().map(candidate => candidate.clientRequestId === clientRequestId ? { ...candidate, status: 'CONFLICT' } : candidate))
        }
        onConflict?.(item, error)
      } else if (useNative) {
        await nativeDb().queueFailed(item.id, error.message || '同步失败')
      } else {
        const status = error.status != null || businessCode ? 'FAILED' : 'RETRY'
        saveBrowserQueue(browserQueue().map(candidate => candidate.clientRequestId === clientRequestId ? { ...candidate, status, error: error.message || '同步失败' } : candidate))
      }
    }
  }
  return { synced, conflicts }
}

export async function localConflicts() {
  const native = nativeDb() ? await nativeDb().conflicts() : []
  try { return [...native, ...JSON.parse(localStorage.getItem('dajin_conflicts') || '[]').filter(item => !item.resolved).map(item => ({ ...item, storage: 'browser' }))] } catch { return native }
}

export async function resolveLocalConflict(conflict, resolution) {
  if (nativeDb() && conflict.storage !== 'browser') return nativeDb().resolveConflict(conflict.id, resolution)
  const conflicts = JSON.parse(localStorage.getItem('dajin_conflicts') || '[]')
  localStorage.setItem('dajin_conflicts', JSON.stringify(conflicts.map(item => item.id === conflict.id || item.clientRequestId === conflict.clientRequestId ? { ...item, resolved: 1, resolution } : item)))
  const queue = browserQueue()
  if (resolution === 'LOCAL') saveBrowserQueue(queue.map(item => item.clientRequestId === conflict.clientRequestId ? { ...item, status: 'RETRY' } : item))
  if (resolution === 'CLOUD') saveBrowserQueue(queue.filter(item => item.clientRequestId !== conflict.clientRequestId))
  return true
}
