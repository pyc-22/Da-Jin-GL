import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMessageSocket } from './messageSocket.js'

let connections
function setup() {
  const sockets = [], probe = vi.fn().mockResolvedValue(true), onState = vi.fn(), onOpen = vi.fn(), onMessage = vi.fn(), onExecutionGap = vi.fn()
  const connection = createMessageSocket({ url: () => 'ws://localhost/ws?token=test', probe, onState, onOpen, onMessage, onExecutionGap, random: () => 0,
    createSocket() { const socket = { readyState: 0, close: vi.fn() }; sockets.push(socket); return socket } })
  connections.push(connection)
  return { connection, sockets, probe, onState, onOpen, onMessage, onExecutionGap }
}
function open(socket) { socket.readyState = 1; socket.onopen() }
beforeEach(() => { vi.useFakeTimers(); connections = [] })
afterEach(() => { connections.forEach(connection => connection.stop()); vi.useRealTimers() })
describe('mobile connection lifecycle', () => {
  it('deduplicates start and drops callbacks from the replaced socket', () => {
    const x = setup(); x.connection.start(); x.connection.start(); expect(x.sockets).toHaveLength(1)
    const stale = x.sockets[0].onopen
    x.connection.reconnect(); stale(); expect(x.onOpen).not.toHaveBeenCalled()
    expect(x.sockets[0].close).toHaveBeenCalledTimes(1)
    open(x.sockets[1]); expect(x.onOpen).toHaveBeenCalledTimes(1)
  })
  it('backs off after disconnection, stops while offline, reconnects on network recovery', async () => {
    const x = setup(); x.connection.start(); open(x.sockets[0]); x.sockets[0].onclose()
    expect(x.onState).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'reconnecting' }))
    await vi.advanceTimersByTimeAsync(1000); expect(x.sockets).toHaveLength(2)
    x.connection.setOnline(false); await vi.advanceTimersByTimeAsync(60000); expect(x.sockets).toHaveLength(2)
    x.connection.setOnline(true); expect(x.sockets).toHaveLength(3)
  })
  it('expires stalled connections and replaces silent OPEN sockets without inventing a ping protocol', async () => {
    const x = setup(); x.connection.start()
    await vi.advanceTimersByTimeAsync(12000)
    expect(x.onState).toHaveBeenLastCalledWith(expect.objectContaining({ reason: 'connect-timeout' }))
    await vi.advanceTimersByTimeAsync(1000); open(x.sockets[1])
    await vi.advanceTimersByTimeAsync(112000)
    expect(x.probe).toHaveBeenCalled(); expect(x.sockets[1].close).toHaveBeenCalled()
  })
  it('detects the execution gap only after JS resumes and reconnects', async () => {
    const x = setup(); x.connection.start(); open(x.sockets[0])
    vi.setSystemTime(Date.now() + 180000)
    expect(x.onExecutionGap).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(25000)
    expect(x.onExecutionGap).toHaveBeenCalledTimes(1)
    expect(x.sockets).toHaveLength(2)
  })
  it('ignores a late health response after logout', async () => {
    const x = setup(); let finish
    x.probe.mockImplementation(() => new Promise(resolve => { finish = resolve }))
    x.connection.start(); open(x.sockets[0]); await vi.advanceTimersByTimeAsync(25000)
    x.connection.stop(); finish(false); await vi.advanceTimersByTimeAsync(60000)
    expect(x.sockets).toHaveLength(1)
    expect(x.onState).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'stopped' }))
  })
})
