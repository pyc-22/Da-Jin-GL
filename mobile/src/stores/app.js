import { defineStore } from 'pinia'
import { markRaw } from 'vue'
import { api } from '../api/request.js'
import { uploadImage } from '../api/upload.js'
import { getStorage, setStorage, scopedStorage } from '../utils/storage.js'
import { useAuthStore } from './auth.js'

export const useAppStore = defineStore('app', {
  state: () => ({ tradeInDraft: null, gold: [], dashboard: null, approvals: [], unread: 0, ws: null, wsConnected: false, heartbeatEpoch: 0, heartbeatBusy: false, heartbeatTimer: null, eventVersion: 0, lastEventType: '', offline: typeof navigator !== 'undefined' ? !navigator.onLine : false, pendingInboundCount: 0, wsToken: '', wsRetries: 0, wsRetryTimer: null }),
  getters: {
    primaryGold: (s) => Array.isArray(s.gold) ? (s.gold.find(x => String(x.price_type || x.priceType).includes('足金')) || s.gold[0]) : s.gold,
    silverSale: (s) => Array.isArray(s.gold) ? s.gold.find(x => String(x.price_type || x.priceType).trim() === '银' || String(x.type_code || x.code || '').toUpperCase() === 'SILVER') : null,
    silverRecycle: (s) => Array.isArray(s.gold) ? s.gold.find(x => String(x.price_type || x.priceType).trim() === '银回收价' || String(x.type_code || x.code || '').toUpperCase() === 'SILVER_RECYCLE') : null
  },
  actions: {
    async loadGold() {
      const cache = scopedStorage()
      try { const gold = await api.gold() || []; if (cache.current()) { this.gold = gold; cache.set('dajin-gold', JSON.stringify(gold)) } }
      catch { if (cache.current()) { try { this.gold = JSON.parse(cache.get('dajin-gold', '[]')) } catch { this.gold = [] } } }
    },
    async checkConnectivity() {
      if (this.heartbeatBusy) return !this.offline
      const epoch = this.heartbeatEpoch
      if (typeof navigator !== 'undefined' && !navigator.onLine) { this.offline = true; return false }
      this.heartbeatBusy = true
      try {
        const health = await api.health()
        if (epoch !== this.heartbeatEpoch) return false
        this.offline = health?.status !== 'UP'
        if (!this.offline) this.refreshLocalPendingInbounds()
        return !this.offline
      } catch { if (epoch === this.heartbeatEpoch) this.offline = true; return false }
      finally { if (epoch === this.heartbeatEpoch) this.heartbeatBusy = false }
    },
    startHeartbeat() {
      if (this.heartbeatTimer) clearInterval(this.heartbeatTimer)
      this.checkConnectivity()
      this.heartbeatTimer = setInterval(() => this.checkConnectivity(), 3000)
    },
    stopHeartbeat() {
      this.heartbeatEpoch++
      this.heartbeatBusy = false
      if (this.heartbeatTimer) clearInterval(this.heartbeatTimer)
      this.heartbeatTimer = null
    },
    refreshLocalPendingInbounds() {
      let queue = []
      try { queue = JSON.parse(getStorage('dajin-inbound-queue', '[]')) } catch { queue = [] }
      this.pendingInboundCount = Array.isArray(queue) ? queue.length : 0
      return this.pendingInboundCount
    },
    async loadPendingInbounds() {
      const cache = scopedStorage()
      const localCount = this.refreshLocalPendingInbounds()
      try { const rows = await api.pendingInbounds() || []; if (cache.current()) this.pendingInboundCount = localCount + rows.filter(x => !['COMPLETED', 'SYNCED'].includes(String(x.status || '').toUpperCase())).length } catch {}
    },
    async syncPendingInbound(clientRequestId) {
      if (!clientRequestId) throw new Error('缺少入库单标识')
      const cache = scopedStorage(), getStorage = cache.get, setStorage = cache.set
      const ensureOwner = () => { if (!cache.current()) throw new Error('账号已切换，请使用原账号同步该入库单') }
      ensureOwner()
      let queue = []; try { queue = JSON.parse(getStorage('dajin-inbound-queue', '[]')) } catch { queue = [] }
      if (!Array.isArray(queue)) queue = []
      let payload = queue.find(x => x.clientRequestId === clientRequestId)
      if (!payload) {
        try {
          const cached = JSON.parse(getStorage('dajin-inbound-history', '[]'))
          const rows = Array.isArray(cached) ? cached : []
          payload = rows.find(x => x.clientRequestId === clientRequestId
            && ['PENDING', 'FAILED'].includes(String(x.status || '').toUpperCase())
            && Array.isArray(x.items) && x.items.length)
        } catch { payload = null }
      }
      if (!payload) throw new Error('未找到该入库单的待同步数据')
      try {
        const prepared = { ...payload, items: [] }
        for (const item of (payload.items || [])) {
          ensureOwner()
          const images = []
          for (const image of (item.images || [])) {
            if (!image) continue
            if (/^(https?:|\/uploads\/|\/api\/file\/)/i.test(String(image))) { images.push(image); continue }
            const file = await pendingImageFile(image)
            ensureOwner()
            const result = typeof file === 'string' ? await uploadImage(file, null) : await uploadImage('', file)
            const url = result?.data?.url || result?.url || result?.data
            if (!url) throw new Error('照片上传失败')
            images.push(url)
          }
          prepared.items.push({ ...item, images })
          if (Array.isArray(item.pieceImages)) {
            const pieceImages = []
            for (const image of item.pieceImages) {
              if (!image) { pieceImages.push(''); continue }
              if (/^(https?:|\/uploads\/|\/api\/file\/)/i.test(String(image))) { pieceImages.push(image); continue }
              const file = await pendingImageFile(image)
              ensureOwner()
              const result = typeof file === 'string' ? await uploadImage(file, null) : await uploadImage('', file)
              const url = result?.data?.url || result?.url || result?.data
              if (!url) throw new Error('照片上传失败')
              pieceImages.push(url)
            }
            prepared.items[prepared.items.length - 1].pieceImages = pieceImages
          }
        }
        ensureOwner()
        const synced = await api.createInbound(prepared)
        const remain = queue.filter(x => x.clientRequestId !== clientRequestId)
        setStorage('dajin-inbound-queue', JSON.stringify(remain))
        if (cache.current()) this.refreshLocalPendingInbounds()
        try {
          const cached = JSON.parse(getStorage('dajin-inbound-history', '[]'))
          const rows = Array.isArray(cached) ? cached : []
          const index = rows.findIndex(row => row.clientRequestId === clientRequestId)
          if (index >= 0) rows[index] = { ...rows[index], ...synced, status: 'COMPLETED', error: '', clientRequestId }
          setStorage('dajin-inbound-history', JSON.stringify(rows))
        } catch {}
        if (getStorage('dajin-inbound-client-id', '') === clientRequestId) {
          setStorage('dajin-inbound-draft', '')
          setStorage('dajin-inbound-meta', '')
          setStorage('dajin-inbound-client-id', '')
        }
        return synced
      } catch (error) {
        this.markPendingInboundFailed(clientRequestId, error?.message || '同步失败', cache)
        throw error
      }
    },
    markPendingInboundFailed(clientRequestId, message, cache = scopedStorage()) {
      const getStorage = cache.get, setStorage = cache.set
      try {
        const cached = JSON.parse(getStorage('dajin-inbound-history', '[]'))
        const rows = Array.isArray(cached) ? cached : []
        const index = rows.findIndex(row => row.clientRequestId === clientRequestId)
        if (index >= 0) { rows[index] = { ...rows[index], status: 'FAILED', error: message }; setStorage('dajin-inbound-history', JSON.stringify(rows)) }
      } catch {}
    },
    connectWs(token) {
      if (typeof WebSocket === 'undefined' || !token) return
      if (this.wsToken === token && ((this.ws && this.ws.readyState < 2) || this.wsRetryTimer)) return
      this.closeWs()
      this.wsToken = token
      this.wsRetries = 0
      this.openWs()
    },
    buildWsUrl() {
      const configured = import.meta.env.VITE_WS_URL
      const apiBase = import.meta.env.VITE_API_BASE
      let base = configured
      if (!base && apiBase && /^https?:\/\//i.test(apiBase)) base = apiBase.replace(/^http/i, 'ws').replace(/\/$/, '')
      if (!base) {
        const host = typeof location !== 'undefined' ? location.hostname : 'localhost'
        const pageProtocol = typeof location !== 'undefined' ? location.protocol : 'http:'
        const scheme = pageProtocol === 'https:' ? 'wss' : 'ws'
        const port = typeof location !== 'undefined' && location.port === '5175' ? ':8080' : (typeof location !== 'undefined' && location.port ? `:${location.port}` : '')
        base = `${scheme}://${host}${port}`
      }
      return `${base.replace(/\/$/, '')}/ws?token=${encodeURIComponent(this.wsToken)}`
    },
    openWs() {
      if (typeof WebSocket === 'undefined' || !this.wsToken) return
      if (this.ws && this.ws.readyState < 2) return
      try {
        const ws = markRaw(new WebSocket(this.buildWsUrl()))
        this.ws = ws
        ws.onopen = () => {
          if (this.ws !== ws) return
          this.wsConnected = true; this.wsRetries = 0
          this.checkConnectivity()
          useAuthStore().refreshSession().catch(() => {})
          this.eventVersion++
        }
        ws.onclose = () => { if (this.ws !== ws) return; this.wsConnected = false; this.ws = null; this.scheduleWsReconnect() }
        ws.onerror = () => {}
        ws.onmessage = async (e) => {
          if (this.ws !== ws) return
          let m; try { m = JSON.parse(e.data) } catch { return }
          if (!m || typeof m.type !== 'string') return
          this.lastEventType = m.type
          const data = m.data || {}
          if (m.type === 'GOLD_PRICE_UPDATED') {
            const rows = Array.isArray(this.gold) ? [...this.gold] : []
            const i = rows.findIndex(x => String(x.price_type || x.priceType) === String(data.priceType))
            if (i >= 0) rows[i] = { ...rows[i], price: data.price }; else rows.push({ price_type: data.priceType, price: data.price })
            this.gold = rows; setStorage('dajin-gold', JSON.stringify(rows))
          }
          if (m.type === 'APPROVAL_CREATED') { this.unread++; this.approvals = [...this.approvals, data] }
          if (m.type === 'STOCK_IN_COMPLETED') this.refreshLocalPendingInbounds()
          const auth = useAuthStore()
          const ownPermission = m.type === 'USER_PERMISSIONS_UPDATED' && String(data.userId) === String(auth.user?.user_id ?? auth.user?.userId)
          const rolePermission = m.type === 'ROLE_PERMISSIONS_UPDATED' && (!data.roleCode || String(data.roleCode).toUpperCase() === auth.role)
          if (ownPermission || rolePermission) { try { await auth.refreshSession() } catch { /* Backend still enforces permissions. Recheck on resume/reconnect. */ } }
          if (this.ws === ws) this.eventVersion++
        }
      } catch { this.scheduleWsReconnect() }
    },
    scheduleWsReconnect() {
      if (!this.wsToken || this.wsRetryTimer) return
      const delay = Math.min(30000, 1000 * Math.pow(2, Math.min(this.wsRetries, 5)))
      this.wsRetries++
      this.wsRetryTimer = setTimeout(() => { this.wsRetryTimer = null; this.openWs() }, delay)
    },
    closeWs() {
      this.wsConnected = false
      this.wsToken = ''
      if (this.wsRetryTimer) { clearTimeout(this.wsRetryTimer); this.wsRetryTimer = null }
      if (this.ws) { try { const ws = this.ws; ws.onclose = null; ws.onopen = null; ws.onmessage = null; ws.onerror = null; ws.close() } catch {} this.ws = null }
    },
    setOffline(value) { this.offline = value }
  }
})

async function pendingImageFile(image) {
  if (typeof image !== 'string' || !image.startsWith('data:')) return image
  const response = await fetch(image)
  const blob = await response.blob()
  return new File([blob], `inbound-${Date.now()}.jpg`, { type: blob.type || 'image/jpeg' })
}
