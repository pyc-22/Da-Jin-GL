import { messageCategory, messageId, messageRead } from './messagePreferences.js'

/** 以服务器消息中心的通知 ID 去重；行情广播本身不产生提示音。 */
export function createMessageMonitor({ fetchRows, readSeen, writeSeen, preferences, deliver, onRows, onError = () => {} }) {
  let owner = '', epoch = 0, busy = false, again = false
  let seen = new Set(), initialized = false
  const persist = () => {
    try { writeSeen(owner, { initialized, ids: [...seen] }) }
    catch { /* 存储配额不足时本次运行仍在内存去重，继续提供消息提醒。 */ }
  }
  async function sync() {
    if (!owner) return
    if (busy) { again = true; return }
    busy = true
    const session = epoch, account = owner
    try {
      const result = await fetchRows()
      if (session !== epoch) return
      if (!Array.isArray(result)) throw new Error('消息数据格式异常')
      const rows = result.filter(row => row && messageId(row))
      const fresh = [], batchIds = new Set()
      for (const row of rows) {
        const id = messageId(row)
        if (initialized && !seen.has(id) && !batchIds.has(id) && !messageRead(row)) fresh.push(row)
        batchIds.add(id)
      }
      // 首次安装/首次切入账号仅建立基线，历史未读消息不集中响铃。
      // 完整保留当前返回列表的 ID，另留 2000 条历史 ID。
      // 直接截断整个集合会导致接口返回超过 2000 条时旧未读消息反复响。
      const history = [...seen].filter(id => !batchIds.has(id)).slice(-2000)
      seen = new Set([...history, ...batchIds])
      initialized = true
      persist()
      onRows(rows)
      const settings = preferences(account)
      const eligible = fresh.filter(row => settings[messageCategory(row)] !== false)
      // 每次补同步无论新增几条都只通知一次。先记 ID，避免重连/并发重复响。
      if (eligible.length && session === epoch) await deliver({ owner: account, rows: eligible, sound: settings.sound !== false })
    } catch (error) { if (session === epoch) onError(error) }
    finally {
      if (session === epoch) {
        busy = false
        if (again) { again = false; void sync() }
      }
    }
  }
  return {
    start(account) {
      epoch++; owner = account; busy = false; again = false
      const saved = readSeen(account)
      initialized = saved?.initialized === true
      seen = new Set(Array.isArray(saved?.ids) ? saved.ids.map(String) : [])
      return sync()
    },
    sync,
    stop() { epoch++; owner = ''; busy = again = false; seen.clear() }
  }
}
