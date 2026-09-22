// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import Notifications from './Notifications.vue'

const mocks = vi.hoisted(() => ({ push: vi.fn(), back: vi.fn(), notifications: vi.fn() }))

vi.mock('vue-router', () => ({ useRouter: () => ({ push: mocks.push, back: mocks.back }) }))
vi.mock('../stores/auth.js', () => ({ useAuthStore: () => ({ role: 'MANAGER', permissions: ['*'] }) }))
vi.mock('../api/request.js', () => ({ api: { notifications: mocks.notifications, markNotificationRead: vi.fn() } }))

describe('Notifications navigation', () => {
  beforeEach(() => {
    mocks.push.mockReset()
    mocks.notifications.mockReset().mockResolvedValue([{ notification_id: 1, action: 'READ', content: '库存提醒', create_time: '2026-09-17 03:23:12' }])
  })

  it('keeps the role tab bar visible and localizes read notifications', async () => {
    const wrapper = mount(Notifications)
    await flushPromises()
    expect(wrapper.findAll('.tabbar button')).toHaveLength(5)
    expect(wrapper.get('.tabbar button.active').text()).toContain('消息')
    expect(wrapper.text()).toContain('通知')
    expect(wrapper.text()).not.toContain('READ')
  })

  it('returns to the manager home from the bottom tab', async () => {
    const wrapper = mount(Notifications)
    await flushPromises()
    await wrapper.findAll('.tabbar button')[0].trigger('click')
    expect(mocks.push).toHaveBeenCalledWith('/manager/dashboard')
  })
})
