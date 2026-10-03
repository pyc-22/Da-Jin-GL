/**
 * 浏览器/Capacitor 共用的连接管理器。没有新增服务端消息格式。
 * 现有协议没有约定 JSON ping/pong：使用现有 health 接口检查网络，
 * 并定期重建长时间静默的 WebSocket，避免把 HTTP 成功误当作 WS 心跳应答。
 *
 * Android 冻结 WebView 后，以下定时器、网络回调和重连逻辑全部暂停。
 * 它们不是后台保活机制；恢复执行后才检测时间间隔并重新建立连接。
 */
export function createMessageSocket({
  url, probe, onState = () => {}, onOpen = () => {}, onMessage = () => {},
  onExecutionGap = () => {}, createSocket = address => new WebSocket(address),
  now = Date.now, random = Math.random,
  heartbeatMs = 25000, quietMs = 90000, connectTimeoutMs = 12000
}) {
  let running = false, online = true, socket = null, retries = 0
  let retryTimer = null, connectTimer = null, heartbeatTimer = null
  let lastTick = 0, lastMessage = 0, generation = 0, probing = false
  const state = (status, reason = '') => onState({ status, reason, socket, retries, retryTimer })

  function closeSocket() {
    generation++
    probing = false
    clearTimeout(connectTimer); connectTimer = null
    if (socket) {
      const old = socket; socket = null
      old.onopen = old.onclose = old.onerror = old.onmessage = null
      try { old.close() } catch { /* 已断开的底层连接无需再次关闭。 */ }
    }
  }
  function retry(reason) {
    closeSocket()
    if (!running || !online) { state(online ? 'stopped' : 'offline', reason); return }
    if (!retryTimer) {
      const delay = Math.min(30000, 1000 * 2 ** Math.min(retries++, 5))
      retryTimer = setTimeout(() => { retryTimer = null; open() }, delay + Math.floor(random() * 500))
    }
    state('reconnecting', reason)
  }
  function open() {
    if (!running || !online || socket) return
    try {
      const current = createSocket(url())
      socket = current; lastMessage = now()
      state('connecting')
      connectTimer = setTimeout(() => { if (socket === current) retry('connect-timeout') }, connectTimeoutMs)
      current.onopen = () => {
        if (!running || socket !== current) return
        clearTimeout(connectTimer); connectTimer = null; retries = 0; lastMessage = now()
        state('connected')
        Promise.resolve(onOpen()).catch(() => {})
      }
      current.onmessage = async event => {
        if (!running || socket !== current) return
        lastMessage = now()
        try { await onMessage(event) } catch { /* 一条消息处理失败不终止后续消息接收。 */ }
      }
      current.onclose = () => { if (socket === current) retry('closed') }
      current.onerror = () => { if (socket === current) retry('socket-error') }
    } catch { retry('connect-error') }
  }
  function reconnect(reason = 'resume') {
    if (!running) return
    clearTimeout(retryTimer); retryTimer = null
    closeSocket(); lastTick = now()
    if (online) open()
    else state('offline', reason)
  }
  async function heartbeat() {
    if (!running) return
    const gap = now() - lastTick; lastTick = now()
    // 只能事后观察执行间隔；长间隔也可能来自系统繁忙，不宣称精确识别冻结。
    if (gap > heartbeatMs * 2.5) { onExecutionGap(gap); reconnect('execution-resumed'); return }
    if (!online || probing) return
    const epoch = generation
    probing = true
    try {
      const healthy = await probe()
      if (!running || epoch !== generation) return
      if (!healthy) { retry('health-check-failed'); return }
      if (socket?.readyState === 1 && now() - lastMessage >= quietMs) reconnect('quiet-connection-refresh')
      else if (!socket && !retryTimer) open()
    } catch { if (running && epoch === generation) retry('health-check-failed') }
    finally { if (epoch === generation) probing = false }
  }
  return {
    start() {
      if (running) return
      running = true; lastTick = now()
      heartbeatTimer = setInterval(heartbeat, heartbeatMs)
      open()
    },
    reconnect,
    setOnline(value) {
      const changed = online !== Boolean(value); online = Boolean(value)
      if (!online) { clearTimeout(retryTimer); retryTimer = null; closeSocket(); state('offline', 'network-offline') }
      else if (changed) reconnect('network-restored')
    },
    stop() {
      running = false
      clearTimeout(retryTimer); clearInterval(heartbeatTimer)
      retryTimer = heartbeatTimer = null
      closeSocket(); state('stopped')
    }
  }
}
