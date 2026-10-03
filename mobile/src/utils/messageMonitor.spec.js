import { describe, expect, it, vi } from 'vitest'
import { createMessageMonitor } from './messageMonitor.js'

const notice = (id, action = 'APPROVAL_CREATED') => ({ notification_id: id, action, read: false })
function setup() {
  const saved = new Map(), delivered = vi.fn().mockResolvedValue(), rows = vi.fn(), fetchRows = vi.fn(), error = vi.fn()
  const settings = { sound: true, approval: true, stock: true, visit: true, system: true }
  const monitor = createMessageMonitor({ fetchRows, deliver: delivered, preferences: () => settings,
    readSeen: owner => saved.get(owner), writeSeen: (owner, value) => saved.set(owner, value), onRows: rows, onError: error })
  return { monitor, saved, delivered, rows, fetchRows, settings, error }
}
describe('new-message delivery', () => {
  it('baselines old unread messages; coalesces new IDs and never repeats them on reconnect', async () => {
    const x = setup()
    x.fetchRows.mockResolvedValue([notice(1)])
    await x.monitor.start('shop:manager'); expect(x.delivered).not.toHaveBeenCalled()
    x.fetchRows.mockResolvedValue([notice(3), notice(2), notice(2), notice(1)])
    await x.monitor.sync()
    expect(x.delivered).toHaveBeenCalledExactlyOnceWith({ owner: 'shop:manager', rows: [notice(3), notice(2)], sound: true })
    await x.monitor.sync(); await x.monitor.start('shop:manager')
    expect(x.delivered).toHaveBeenCalledTimes(1)
  })
  it('keeps background notifications when sound is switched off', async () => {
    const x = setup(); x.fetchRows.mockResolvedValue([]); await x.monitor.start('a')
    x.settings.sound = false; x.fetchRows.mockResolvedValue([notice(2)])
    await x.monitor.sync()
    expect(x.delivered).toHaveBeenCalledWith(expect.objectContaining({ sound: false, rows: [notice(2)] }))
  })
  it('persists per account and suppresses late responses from a previous login', async () => {
    const x = setup(); let finishOld
    x.fetchRows.mockImplementationOnce(() => new Promise(resolve => { finishOld = resolve }))
    const old = x.monitor.start('old')
    x.fetchRows.mockResolvedValue([notice(10)]); await x.monitor.start('new')
    finishOld([notice(99)]); await old
    expect(x.saved.has('old')).toBe(false)
    expect(x.rows).toHaveBeenCalledExactlyOnceWith([notice(10)])
    expect(x.delivered).not.toHaveBeenCalled()
  })
  it('does not baseline a failed request, or replay messages already read or with disabled categories', async () => {
    const x = setup(); x.fetchRows.mockRejectedValueOnce(new Error('offline'))
    await x.monitor.start('a'); expect(x.saved.has('a')).toBe(false)
    x.fetchRows.mockResolvedValue([notice(1)]); await x.monitor.sync()
    x.settings.approval = false
    x.fetchRows.mockResolvedValue([notice(2), { ...notice(3), read: true }, { ...notice(4), action: 'READ' }, notice(1)])
    await x.monitor.sync(); expect(x.delivered).not.toHaveBeenCalled()
    x.settings.approval = true; await x.monitor.sync(); expect(x.delivered).not.toHaveBeenCalled()
  })
  it('serializes poll and WebSocket triggers, and cancels delivery on logout', async () => {
    const x = setup(); x.fetchRows.mockResolvedValue([]); await x.monitor.start('a')
    let finish
    x.fetchRows.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const pending = x.monitor.sync(); await x.monitor.sync()
    expect(x.fetchRows).toHaveBeenCalledTimes(2)
    x.monitor.stop(); finish([notice(1)]); await pending
    expect(x.delivered).not.toHaveBeenCalled()
    expect(x.fetchRows).toHaveBeenCalledTimes(2)
  })
  it('restores missed notices after a process restart as one batch', async () => {
    const x = setup(); x.fetchRows.mockResolvedValue([notice(1)]); await x.monitor.start('a'); x.monitor.stop()
    x.fetchRows.mockResolvedValue([notice(4), notice(3), notice(2), notice(1)])
    await x.monitor.start('a')
    expect(x.delivered).toHaveBeenCalledTimes(1)
    expect(x.delivered.mock.calls[0][0].rows).toHaveLength(3)
  })
  it('does not replay a large historical response when trimming the cache', async () => {
    const x = setup(); const rows = Array.from({ length: 2100 }, (_, index) => notice(index + 1))
    x.fetchRows.mockResolvedValue(rows); await x.monitor.start('a'); await x.monitor.sync()
    expect(x.delivered).not.toHaveBeenCalled()
    x.fetchRows.mockResolvedValue([notice(2101), ...rows]); await x.monitor.sync()
    expect(x.delivered).toHaveBeenCalledExactlyOnceWith({ owner: 'a', rows: [notice(2101)], sound: true })
  })
})
