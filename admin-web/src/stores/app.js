import { defineStore } from 'pinia'
export const useAppStore = defineStore('app', {
  state: () => ({ store: null, gold: [], paymentChannels: [], notifications: 0, eventVersion: 0, lastEventType: '', ws: null }),
  actions: {
    setStore(store) { this.store = store },
    setGold(gold) { this.gold = gold },
    setPaymentChannels(channels) { this.paymentChannels = Array.isArray(channels) ? channels : [] },
    connectWs(token) {
      this.disconnectWs()
      if (!token || typeof WebSocket === 'undefined') return
      const scheme = location.protocol === 'https:' ? 'wss' : 'ws'
      this.ws = new WebSocket(`${scheme}://${location.host}/ws?token=${encodeURIComponent(token)}`)
      this.ws.onmessage = event => {
        try {
          const message = JSON.parse(event.data)
          this.lastEventType = message.type || ''
          if (message.type === 'APPROVAL_CREATED') this.notifications += 1
          if (message.type === 'GOLD_PRICE_UPDATED') {
            const data = message.data || message
            const rows = Array.isArray(this.gold) ? [...this.gold] : []
            const index = rows.findIndex(row => String(row.price_type || row.priceType) === String(data.priceType))
            if (index >= 0) rows[index] = { ...rows[index], price: data.price }
            else rows.push({ price_type: data.priceType, price: data.price })
            this.gold = rows
          }
          this.eventVersion += 1
        } catch { }
      }
      this.ws.onclose = () => { this.ws = null }
    },
    disconnectWs() {
      if (this.ws) this.ws.close()
      this.ws = null
    }
  }
})
