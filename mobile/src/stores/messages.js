import { defineStore } from 'pinia'
import { getStorage, setStorage } from '../utils/storage.js'
import { readMessagePreferences, saveMessagePreferences, messageRead } from '../utils/messagePreferences.js'

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
    readSeen(owner) {
      try { return JSON.parse(getStorage(`dajin-message:${owner}:seen`, 'null')) } catch { return null }
    },
    writeSeen(owner, value) { setStorage(`dajin-message:${owner}:seen`, JSON.stringify(value)) }
  }
})
