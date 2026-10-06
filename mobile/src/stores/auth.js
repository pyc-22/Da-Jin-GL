import { defineStore } from 'pinia'
import { api } from '../api/request.js'
import { getStorage, setStorage, removeStorage } from '../utils/storage.js'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: getStorage('dajin-token', ''),
    sessionExpiresAt: 0,
    sessionWarning: false,
    sessionTimer: null,
    user: (() => { try { return JSON.parse(getStorage('dajin-user', 'null')) } catch { return null } })()
  }),
  getters: {
    role: (s) => s.user?.role_code || s.user?.roleCode || 'SALES',
    online: () => typeof navigator === 'undefined' ? true : navigator.onLine,
    permissions: (s) => {
      const raw = s.user?.permissions
      if (Array.isArray(raw)) return raw
      try { return JSON.parse(raw || '[]') } catch { return [] }
    }
  },
  actions: {
    async login(username, password) {
      const data = await api.login({ username, password, clientType: 'MOBILE' })
      this.token = data.token
      this.user = { ...(data.user || data.userInfo || {}), permissions: data.permissions || data.user?.permissions || data.userInfo?.permissions || [] }
      this.updateSessionExpiry()
      this.persist()
      return data
    },
    async refreshSession() {
      if (!this.token) return null
      const token = this.token
      const data = await api.currentUser()
      if (this.token !== token) return null
      this.user = { ...(data.user || data.userInfo || {}), permissions: data.permissions || data.user?.permissions || data.userInfo?.permissions || [] }
      this.persist()
      return data
    },
    persist() {
      setStorage('dajin-token', this.token); setStorage('dajin-user', JSON.stringify(this.user))
      removeStorage('dajin-refresh-token')
    },
    updateSessionExpiry() {
      try {
        const encoded = String(this.token || '').split('.')[1]
        const payload = JSON.parse(atob(encoded.replace(/-/g, '+').replace(/_/g, '/') + '=='))
        this.sessionExpiresAt = Number(payload.exp || 0) * 1000
      } catch { this.sessionExpiresAt = 0 }
      this.sessionWarning = false
    },
    startSessionMonitor() {
      this.stopSessionMonitor()
      const check = () => {
        const remaining = this.sessionExpiresAt ? this.sessionExpiresAt - Date.now() : 0
        this.sessionWarning = remaining > 0 && remaining <= 10 * 60 * 1000
      }
      check(); this.sessionTimer = setInterval(check, 30000)
    },
    stopSessionMonitor() {
      if (this.sessionTimer) clearInterval(this.sessionTimer)
      this.sessionTimer = null
    },
    can(permission) {
      if (!permission) return true
      if (this.role === 'ADMIN') return true
      const required = String(permission).split('+').filter(Boolean)
      return this.permissions.includes('*') || required.every(code => this.permissions.includes(code))
    },
    logout() {
      this.stopSessionMonitor(); this.token = ''; this.user = null; this.sessionExpiresAt = 0; this.sessionWarning = false
      removeStorage('dajin-token'); removeStorage('dajin-user'); removeStorage('dajin-refresh-token')
    }
  }
})
