// @vitest-environment jsdom
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, reactive } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({ auth: null, app: null, events: {}, notifications: vi.fn(), sound: vi.fn(), show: vi.fn(), setOwner: vi.fn(), push: vi.fn(), replace: vi.fn(), remove: vi.fn() }))
vi.mock('../api/request.js', () => ({ api: { notifications: mocks.notifications } }))
vi.mock('../stores/auth.js', () => ({ useAuthStore: () => mocks.auth }))
vi.mock('../stores/app.js', () => ({ useAppStore: () => mocks.app }))
vi.mock('@capacitor/app', () => ({ App: { addListener: async (event, callback) => { mocks.events[event] = callback; return { remove: mocks.remove } } } }))
vi.mock('../utils/messageSound.js', () => ({ installSoundUnlock: () => () => {}, playMessageSound: mocks.sound }))
vi.mock('../utils/messagePermissions.js', () => ({
  isAndroidMessages: () => true,
  backgroundSettings: async () => ({ permission: 'granted' }), requestMessagePermission: vi.fn(),
  messageNative: { setOwner: mocks.setOwner, showNotification: mocks.show,
    addListener: async (event, callback) => { mocks.events[event] = callback; return { remove: mocks.remove } } }
}))
import { useMessageRuntime } from './useMessageRuntime.js'
import { useMessagesStore } from '../stores/messages.js'

const notice = id => ({ notification_id: id, action: 'APPROVAL_CREATED', approvalId: id + 100, read: false })
let wrapper
async function start() {
  wrapper = mount(defineComponent({ setup() { useMessageRuntime({ push: mocks.push, replace: mocks.replace }); return () => null } }))
  await flushPromises()
  return useMessagesStore()
}
beforeEach(() => {
  vi.useFakeTimers(); vi.clearAllMocks(); localStorage.clear(); setActivePinia(createPinia()); mocks.events = {}
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
  mocks.auth = reactive({ token: 'one', user: { user_id: 7, store_id: 2 }, can: () => true, refreshSession: vi.fn().mockResolvedValue(), logout: vi.fn() })
  mocks.app = reactive({ eventVersion: 0, lastEventType: '', unread: 0, resumeWs: vi.fn(), setWsOnline: vi.fn(), checkConnectivity: vi.fn() })
  mocks.notifications.mockResolvedValue([notice(1)]); mocks.sound.mockResolvedValue({ played: true })
  mocks.show.mockResolvedValue({ delivered: true }); mocks.setOwner.mockResolvedValue()
})
afterEach(() => { wrapper?.unmount(); wrapper = null; vi.useRealTimers(); vi.restoreAllMocks() })

describe('Capacitor message integration', () => {
  it('plays once for a foreground business notification; price broadcasts never ring by themselves', async () => {
    await start(); expect(mocks.sound).not.toHaveBeenCalled()
    mocks.app.lastEventType = 'GOLD_PRICE_UPDATED'; mocks.app.eventVersion++
    await vi.advanceTimersByTimeAsync(600); expect(mocks.notifications).toHaveBeenCalledTimes(1)
    mocks.notifications.mockResolvedValue([notice(2), notice(1)])
    mocks.app.lastEventType = 'APPROVAL_CREATED'; mocks.app.eventVersion++
    await vi.advanceTimersByTimeAsync(600)
    expect(mocks.sound).toHaveBeenCalledTimes(1); expect(mocks.show).not.toHaveBeenCalled()
    expect(mocks.app.unread).toBe(2)
  })
  it('shows a silent background notification when only sound is disabled and opens the message page on tap', async () => {
    const messages = await start(); messages.setPreference('sound', false)
    mocks.events.appStateChange({ isActive: false })
    mocks.notifications.mockResolvedValue([notice(3), notice(2), notice(1)])
    await vi.advanceTimersByTimeAsync(15000)
    expect(mocks.show).toHaveBeenCalledExactlyOnceWith({ owner: '2:7', count: 2, sound: false })
    expect(mocks.sound).not.toHaveBeenCalled()
    mocks.events.notificationAction({ owner: '2:7', notification: notice(2) })
    expect(mocks.push).toHaveBeenCalledExactlyOnceWith({ path: '/manager/approval-center', query: { approvalId: '102' } })
  })
  it('reconnects after resume and coalesces messages that arrived while JS was suspended', async () => {
    const messages = await start(); mocks.events.appStateChange({ isActive: false })
    mocks.notifications.mockResolvedValue([notice(4), notice(3), notice(2), notice(1)])
    vi.setSystemTime(Date.now() + 180000)
    expect(mocks.sound).not.toHaveBeenCalled(); expect(mocks.show).not.toHaveBeenCalled()
    mocks.events.appStateChange({ isActive: true }); await flushPromises()
    expect(mocks.app.resumeWs).toHaveBeenCalledTimes(1)
    expect(messages.lastExecutionGap).toBeGreaterThanOrEqual(180000)
    expect(mocks.sound).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(15000); expect(mocks.sound).toHaveBeenCalledTimes(1)
  })
  it('reconnects when switching between two online networks; discards another account’s notification tap', async () => {
    await start(); mocks.events.networkStatusChange({ connected: true })
    expect(mocks.app.resumeWs).toHaveBeenCalledTimes(1)
    mocks.events.notificationAction({ owner: '2:99' }); expect(mocks.push).not.toHaveBeenCalled()
    mocks.auth.token = ''; await flushPromises()
    expect(mocks.setOwner).toHaveBeenLastCalledWith({ owner: '' })
    const count = mocks.notifications.mock.calls.length
    await vi.advanceTimersByTimeAsync(30000)
    expect(mocks.notifications).toHaveBeenCalledTimes(count)
  })
  it('preserves the foreground login-expiry check even before the socket connects', async () => {
    await start(); mocks.events.appStateChange({ isActive: false })
    mocks.auth.refreshSession.mockRejectedValueOnce({ response: { status: 401 } })
    mocks.events.appStateChange({ isActive: true }); await flushPromises()
    expect(mocks.auth.logout).toHaveBeenCalledTimes(1)
    expect(mocks.replace).toHaveBeenCalledExactlyOnceWith('/login')
  })
})
