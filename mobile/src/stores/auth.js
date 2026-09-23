import { defineStore } from 'pinia'
import { api } from '../api/request.js'
import { getStorage, setStorage, removeStorage } from '../utils/storage.js'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: getStorage('dajin-token', ''),
    refreshToken: getStorage('dajin-refresh-token', ''),
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
      this.refreshToken = data.refreshToken || ''
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
      if (this.refreshToken) setStorage('dajin-refresh-token', this.refreshToken)
      else removeStorage('dajin-refresh-token')
    },
    can(permission) {
      if (!permission) return true
      if (this.role === 'ADMIN') return true
      return this.permissions.includes('*') || this.permissions.includes(permission)
    },
    logout() {
      this.token = ''; this.user = null; this.refreshToken = ''
      removeStorage('dajin-token'); removeStorage('dajin-user'); removeStorage('dajin-refresh-token')
    }
  }
})
