// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import Layout from './Layout.vue'
import { notificationApi } from '../api/modules'

const state = vi.hoisted(() => ({
  app: { store: null, gold: [], notifications: 0, eventVersion: 0, lastEventType: '', setStore: vi.fn(), setGold: vi.fn(), setPaymentChannels: vi.fn(), connectWs: vi.fn(), disconnectWs: vi.fn() },
  auth: { role: 'ADMIN', token: 'test-token', user: { real_name: '管理员' }, logout: vi.fn(), can: vi.fn(() => true), refresh: vi.fn(async () => {}) }
}))

vi.mock('../stores/auth', () => ({ useAuthStore: () => state.auth }))
vi.mock('../stores/app', () => ({ useAppStore: () => state.app }))
vi.mock('vue-router', () => ({ useRoute: () => ({ path: '/dashboard' }), useRouter: () => ({ push: vi.fn() }) }))
vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn(), error: vi.fn() } }))
vi.mock('../api/modules', () => ({
  goldApi: { current: vi.fn(async () => []) },
  systemApi: { store: vi.fn(async () => ({ store_name: '测试门店' })), activeChannels: vi.fn(async () => []) },
  notificationApi: { list: vi.fn(), read: vi.fn(async () => ({ read: true })) }
}))

let wrapper
afterEach(() => { wrapper?.unmount(); wrapper = null; vi.clearAllMocks(); state.auth.role = 'ADMIN'; state.auth.can.mockImplementation(() => true) })

describe('manager notifications', () => {
  it('shows a negotiated sale and marks it read', async () => {
    notificationApi.list.mockResolvedValue([{ notification_id: 17, action: 'BARGAIN', content: '议价成交：订单XS-17，原应收 ¥140.00，实收 ¥120.00，议价优惠 ¥20.00', create_time: '2026-09-28 14:00:00' }])
    wrapper = mount(Layout, { global: { stubs: {
      RouterLink: { template: '<a><slot /></a>' }, RouterView: true,
      ElPopover: { template: '<div><slot name="reference" /><slot /></div>' },
      ElBadge: { props: ['value'], template: '<div><output>{{ value }}</output><slot /></div>' },
      ElButton: { template: '<button><slot /></button>' }
    } } })
    await flushPromises()
    expect(wrapper.get('output').text()).toBe('1')
    expect(wrapper.get('.notification-row').text()).toContain('实收 ¥120.00')
    await wrapper.get('.notification-row').trigger('click')
    await flushPromises()
    expect(notificationApi.read).toHaveBeenCalledWith(17)
    expect(wrapper.get('output').text()).toBe('0')
    expect(wrapper.get('.notification-row').text()).toContain('已读')
  })

  it('only renders menu entries granted to the manager', async () => {
    state.auth.role = 'MANAGER'
    state.auth.can.mockImplementation(code => ['member:view', 'processing:items'].includes(code))
    wrapper = mount(Layout, { global: { stubs: {
      RouterLink: { template: '<a><slot /></a>' }, RouterView: true,
      ElPopover: { template: '<div><slot name="reference" /><slot /></div>' },
      ElBadge: { template: '<div><slot /></div>' },
      ElButton: { template: '<button><slot /></button>' }
    } } })
    await flushPromises()
    const links = wrapper.findAll('nav a').map(item => item.text())
    expect(links).toContain('会员')
    expect(links).toContain('加工管理')
    expect(links).toContain('加工项目')
    expect(links).not.toContain('系统')
    expect(links).not.toContain('加工订单')
    expect(wrapper.find('.top-actions .gold-mini').exists()).toBe(false)
    expect(wrapper.find('.notification-heading').exists()).toBe(false)
  })
})
