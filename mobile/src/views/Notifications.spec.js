// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import Notifications from './Notifications.vue'
import { createPinia, setActivePinia } from 'pinia'

const mocks = vi.hoisted(() => ({ push: vi.fn(), back: vi.fn(), notifications: vi.fn(), deleteNotification: vi.fn(), clearNotifications: vi.fn() }))

vi.mock('vue-router', () => ({ useRouter: () => ({ push: mocks.push, back: mocks.back }) }))
vi.mock('../stores/auth.js', () => ({ useAuthStore: () => ({ role: 'MANAGER', permissions: ['*'] }) }))
vi.mock('../api/request.js', () => ({ api: { notifications: mocks.notifications, markNotificationRead: vi.fn(), deleteNotification: mocks.deleteNotification, clearNotifications: mocks.clearNotifications } }))

describe('Notifications navigation', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    mocks.push.mockReset()
    mocks.notifications.mockReset().mockResolvedValue([{ notification_id: 1, action: 'READ', content: '库存提醒', create_time: '2026-09-17 03:23:12' }])
    mocks.deleteNotification.mockReset().mockResolvedValue({ deleted: true })
    mocks.clearNotifications.mockReset().mockResolvedValue({ cleared: 1 })
    vi.stubGlobal('confirm', vi.fn(() => true))
  })

  it('removes a message after swiping it left', async () => {
    const wrapper = mount(Notifications)
    await flushPromises()
    const row = wrapper.get('.notice-swipe')
    await row.trigger('touchstart', { changedTouches: [{ clientX: 220, clientY: 20 }] })
    await row.trigger('touchmove', { changedTouches: [{ clientX: 110, clientY: 22 }] })
    await row.trigger('touchend')
    expect(wrapper.get('.list-card').attributes('style')).toContain('translateX(-82px)')
    await row.get('.notice-delete').trigger('click')
    await flushPromises()
    expect(mocks.deleteNotification).toHaveBeenCalledWith('1')
    expect(wrapper.find('.notice-swipe').exists()).toBe(false)
  })

  it('resets the previously swiped row when another row is moved', async () => {
    mocks.notifications.mockResolvedValue([
      { notification_id: 1, action: 'READ', content: '第一条', create_time: '2026-09-17 03:23:12' },
      { notification_id: 2, action: 'READ', content: '第二条', create_time: '2026-09-17 03:24:12' }
    ])
    const wrapper = mount(Notifications)
    await flushPromises()
    const rows = wrapper.findAll('.notice-swipe')
    await rows[0].trigger('touchstart', { changedTouches: [{ clientX: 220, clientY: 20 }] })
    await rows[0].trigger('touchmove', { changedTouches: [{ clientX: 110, clientY: 22 }] })
    await rows[0].trigger('touchend')
    expect(rows[0].get('.list-card').attributes('style')).toContain('translateX(-82px)')

    await rows[1].trigger('touchstart', { changedTouches: [{ clientX: 220, clientY: 20 }] })
    expect(rows[0].get('.list-card').attributes('style')).toContain('translateX(0px)')
    await rows[1].trigger('touchmove', { changedTouches: [{ clientX: 110, clientY: 22 }] })
    await rows[1].trigger('touchend')
    expect(rows[1].get('.list-card').attributes('style')).toContain('translateX(-82px)')
  })

  it('clears every message from the header action', async () => {
    const wrapper = mount(Notifications)
    await flushPromises()
    await wrapper.findAll('button').find(node => node.text() === '清空').trigger('click')
    await flushPromises()
    expect(mocks.clearNotifications).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.notice-swipe').exists()).toBe(false)
  })

  it('keeps the role tab bar visible and localizes read notifications', async () => {
    const wrapper = mount(Notifications)
    await flushPromises()
    expect(wrapper.findAll('.tabbar button')).toHaveLength(5)
    expect(wrapper.get('.tabbar button:nth-child(3)').text()).toContain('开单')
    expect(wrapper.get('.tabbar button.active').text()).toContain('消息')
    expect(wrapper.text()).toContain('库存提醒')
    expect(wrapper.text()).not.toContain('READ')
  })

  it('returns to the manager home from the bottom tab', async () => {
    const wrapper = mount(Notifications)
    await flushPromises()
    await wrapper.findAll('.tabbar button')[0].trigger('click')
    expect(mocks.push).toHaveBeenCalledWith('/manager/dashboard')
  })

  it('opens the order page from the raised center tab', async () => {
    const wrapper = mount(Notifications)
    await flushPromises()
    await wrapper.find('.tabbar-order').trigger('click')
    expect(mocks.push).toHaveBeenCalledWith('/processing?create=1')
  })
})
