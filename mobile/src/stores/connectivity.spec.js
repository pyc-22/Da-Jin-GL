// @vitest-environment jsdom
import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
const mocks = vi.hoisted(() => ({ health: vi.fn(), refreshSession: vi.fn() }))
vi.mock('../api/request.js', () => ({ api: { health: mocks.health } }))
vi.mock('../api/upload.js', () => ({ uploadImage: vi.fn() }))
vi.mock('./auth.js', () => ({ useAuthStore: () => ({ role: 'SALES', user: { user_id: 7 }, refreshSession: mocks.refreshSession }) }))
import { useAppStore } from './app.js'
let sockets
beforeEach(() => {
  localStorage.clear(); setActivePinia(createPinia()); vi.clearAllMocks(); sockets = []
  vi.stubGlobal('WebSocket', class { constructor() { this.readyState = 0; this.close = vi.fn(); sockets.push(this) } })
})
afterEach(() => vi.unstubAllGlobals())
it('rejects HTML and DOWN health responses; accepts only UP', async () => {
  const app = useAppStore()
  for (const response of ['<html>shell</html>', { status: 'DOWN' }, null]) {
    mocks.health.mockResolvedValue(response)
    expect(await app.checkConnectivity()).toBe(false)
    expect(app.offline).toBe(true)
  }
  mocks.health.mockResolvedValue({ status: 'UP' })
  expect(await app.checkConnectivity()).toBe(true)
})
it('deduplicates connections and closes the old socket on token change and logout', () => {
  const app = useAppStore()
  app.connectWs('one'); app.connectWs('one')
  expect(sockets).toHaveLength(1)
  const staleOpen = sockets[0].onopen
  app.connectWs('two')
  expect(sockets[0].close).toHaveBeenCalledOnce()
  staleOpen()
  expect(app.wsConnected).toBe(false)
  app.closeWs()
  expect(sockets[1].close).toHaveBeenCalledOnce()
})
it('refreshes only current user permission changes and ignores malformed messages', async () => {
  const app = useAppStore(); app.connectWs('one')
  await sockets[0].onmessage({ data: 'invalid json' })
  await sockets[0].onmessage({ data: JSON.stringify({ type: 'USER_PERMISSIONS_UPDATED', data: { userId: 8 } }) })
  expect(mocks.refreshSession).not.toHaveBeenCalled()
  await sockets[0].onmessage({ data: JSON.stringify({ type: 'USER_PERMISSIONS_UPDATED', data: { userId: 7 } }) })
  expect(mocks.refreshSession).toHaveBeenCalledOnce()
  app.closeWs()
})
