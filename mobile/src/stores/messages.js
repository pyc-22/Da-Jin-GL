import { defineStore } from 'pinia'
import { getStorage, setStorage } from '../utils/storage.js'
import { readMessagePreferences, saveMessagePreferences, messageRead, messageId } from '../utils/messagePreferences.js'
import { api } from '../api/request.js'

export const useMessagesStore = defineStore('messages', {
  state: () => ({ owner: '', rows: [], loading: false, error: '', deliveryError: '', active: true, executionState: 'foreground', lastExecutionGap: 0, settings: readMessagePreferences('') }),
  getters: { unread: state => state.rows.filter(row => !messageRead(row)).length },
  actions: {
    setOwner(owner) {
      this.owner = owner; this.rows = []; this.error = ''; this.deliveryError = ''
      this.loading = Boolean(owner); this.settings = readMessagePreferences(owner)
    },
    setPreference(key, value) {
      this.settings = { ...this.settings, [key]: Boolean(value) }
      saveMessagePreferences(this.owner, this.settings)
    },
    async refresh() {
      if (!this.owner) this.owner = 'anonymous'
      this.loading = true
      try { this.rows = await api.notifications() || []; this.error = ''; return this.rows }
      catch (error) { this.error = error?.message || '消息加载失败，请稍后刷新'; throw error }
      finally { this.loading = false }
    },
    async markRead(notice) {
      if (!notice || messageRead(notice)) return
      const id = notice.notification_id ?? notice.id ?? notice.log_id
      await api.markNotificationRead(id)
      this.rows = this.rows.map(row => String(row.notification_id ?? row.id ?? row.log_id) === String(id) ? { ...row, read: true } : row)
    },
    /** 划掉一条消息（服务端软删除）。 */
    async remove(notice) {
      const id = messageId(notice)
      if (!id) return
      await api.deleteNotification(id)
      this.rows = this.rows.filter(row => messageId(row) !== id)
    },
    /** 清空全部消息（服务端软删除）。 */
    async clearAll() {
      await api.clearNotifications()
      this.rows = []
    },
    readSeen(owner) {
      try { return JSON.parse(getStorage(`dajin-message:${owner}:seen`, 'null')) } catch { return null }
    },
    writeSeen(owner, value) { setStorage(`dajin-message:${owner}:seen`, JSON.stringify(value)) }
  }
})
