import { onMounted, onUnmounted, watch } from 'vue'
import { App } from '@capacitor/app'
import { api } from '../api/request.js'
import { useAuthStore } from '../stores/auth.js'
import { useAppStore } from '../stores/app.js'
import { useMessagesStore } from '../stores/messages.js'
import { createMessageMonitor } from '../utils/messageMonitor.js'
import { readMessagePreferences } from '../utils/messagePreferences.js'
import { isAndroidMessages, messageNative, backgroundSettings, requestMessagePermission } from '../utils/messagePermissions.js'
import { installSoundUnlock, playMessageSound } from '../utils/messageSound.js'
import { getStorage, setStorage } from '../utils/storage.js'
import { navigateNotification } from '../utils/notificationNavigation.js'

export function useMessageRuntime(router) {
  const auth = useAuthStore(), app = useAppStore(), messages = useMessagesStore()
  let stopped = false, mounted = false, pollTimer, eventTimer, unlock = () => {}, lastTick = Date.now()
  let session = 0, pendingTap = null
  const listeners = [], unwatch = []
  const account = () => {
    const user = auth.user, id = user?.user_id ?? user?.userId, store = user?.store_id ?? user?.storeId
    return auth.token && auth.can('notification:view') && id != null && store != null
      ? `${encodeURIComponent(store)}:${encodeURIComponent(id)}` : ''
  }
  const monitor = createMessageMonitor({
    fetchRows: () => api.notifications(), readSeen: owner => messages.readSeen(owner),
    writeSeen: (owner, value) => messages.writeSeen(owner, value), preferences: readMessagePreferences,
    onRows(rows) { messages.rows = rows; messages.loading = false; messages.error = ''; app.unread = messages.unread },
    onError(error) { messages.loading = false; messages.error = error?.message || '消息同步失败，恢复连接后重试' },
    async deliver({ owner, rows, sound }) {
      if (account() !== owner || stopped) return
      messages.deliveryError = ''
      try {
        if (isAndroidMessages() && !messages.active) {
          const result = await messageNative.showNotification({ owner, count: rows.length, sound })
          if (result.delivered === false) messages.deliveryError = '系统通知未开启，请在消息设置中开启'
        } else if (sound) {
          const result = await playMessageSound(owner, () => account() === owner && !stopped && readMessagePreferences(owner).sound !== false)
          if (result?.reason === 'user-gesture-required') messages.deliveryError = '请点击消息设置中的“试听提示音”启用浏览器声音'
        }
      } catch { messages.deliveryError = '提醒播放失败，请在消息设置中检查通知权限并试听' }
    }
  })
  function openTappedMessage() {
    if (!pendingTap || !auth.token) return
    const owner = account(), tap = pendingTap; pendingTap = null
    if (owner && tap.owner === owner && auth.can('notification:view')) {
      const row = tap.notification || tap.message || tap.row || tap
      void navigateNotification(router, row)
    }
  }
  async function syncSession() {
    const current = ++session, owner = account()
    monitor.stop(); messages.setOwner(owner)
    if (isAndroidMessages()) {
      try {
        await messageNative.setOwner({ owner })
        if (current !== session || stopped) return
        if (owner) {
          const settings = await backgroundSettings()
          if (current !== session || stopped) return
          // Android 13+ 首次登录只询问一次；拒绝后由设置页主动重试。
          if (settings.permission === 'prompt' && !getStorage('dajin-message-permission-asked', '')) {
            setStorage('dajin-message-permission-asked', '1')
            await requestMessagePermission()
          }
        }
      } catch { messages.deliveryError = '请在消息设置中检查系统通知权限' }
    }
    if (current !== session || stopped) return
    if (owner) { openTappedMessage(); void monitor.start(owner) }
  }
  function setActive(active) {
    const changed = messages.active !== active
    messages.active = active
    if (!active) { messages.executionState = 'background'; return }
    if (changed) {
      // Capacitor appStateChange 相当于 UniApp 的 onShow。
      // 即使 readyState 仍是 OPEN，也重建休眠前留下的连接。
      const gap = Date.now() - lastTick
      if (gap > 45000) messages.lastExecutionGap = gap
      messages.executionState = 'foreground'; lastTick = Date.now()
      if (auth.token) {
        const token = auth.token
        app.resumeWs(); void app.checkConnectivity(); void monitor.sync()
        // 保留原有回到前台时刷新登录态/权限的行为，不依赖 WS 是否连通。
        void auth.refreshSession().then(() => {
          if (auth.token === token && !stopped) app.eventVersion++
        }).catch(error => {
          if (auth.token === token && error?.response?.status === 401) { auth.logout(); void router.replace('/login') }
        })
      }
    }
  }
  const visibility = () => { if (!isAndroidMessages()) setActive(document.visibilityState !== 'hidden') }
  const network = connected => {
    app.setWsOnline(connected)
    if (connected && auth.token) { void app.checkConnectivity(); void monitor.sync() }
  }
  const online = () => network(true), offline = () => network(false)
  async function listen(promise) {
    try { const listener = await promise; if (stopped) await listener.remove(); else listeners.push(listener) } catch { /* Web 环境只使用 DOM 生命周期。 */ }
  }
  onMounted(() => {
    mounted = true; unlock = installSoundUnlock()
    messages.active = document.visibilityState !== 'hidden'
    document.addEventListener('visibilitychange', visibility)
    window.addEventListener('online', online); window.addEventListener('offline', offline)
    if (isAndroidMessages()) {
      void listen(App.addListener('appStateChange', ({ isActive }) => setActive(isActive)))
      void listen(messageNative.addListener('networkStatusChange', ({ connected }) => {
        network(connected)
        // Wi-Fi/移动数据切换时二者都可能是 online，仍需替换原来的 socket。
        if (connected && auth.token) app.resumeWs()
      }))
      void listen(messageNative.addListener('notificationAction', tap => { pendingTap = tap; openTappedMessage() }))
    }
    // 现有广播不是持久通知本身。广播触发合并查询，定时同步补齐未广播的通知。
    // 冻结期间 JS/计时器均停跑，下面的轮询和 WebSocket 都不具备唤醒能力。
    pollTimer = setInterval(() => {
      const gap = Date.now() - lastTick; lastTick = Date.now()
      if (gap > 45000) {
        messages.lastExecutionGap = gap; messages.executionState = 'resuming'
        if (auth.token) app.resumeWs()
      }
      void monitor.sync()
      messages.executionState = messages.active ? 'foreground' : 'background'
    }, 15000)
    unwatch.push(watch(() => [auth.token, account()], syncSession, { immediate: true }))
    unwatch.push(watch(() => messages.unread, value => { app.unread = value }))
    unwatch.push(watch(() => app.eventVersion, () => {
      if (['GOLD_PRICE_UPDATED', 'GOLD_TYPES_UPDATED'].includes(app.lastEventType)) return
      if (!account() || eventTimer) return
      eventTimer = setTimeout(() => { eventTimer = null; void monitor.sync() }, 500)
    }))
  })
  onUnmounted(() => {
    stopped = true; session++; monitor.stop(); unlock()
    clearInterval(pollTimer); clearTimeout(eventTimer)
    unwatch.forEach(stop => stop()); listeners.forEach(listener => { void listener.remove() })
    if (mounted) {
      document.removeEventListener('visibilitychange', visibility)
      window.removeEventListener('online', online); window.removeEventListener('offline', offline)
    }
  })
}
