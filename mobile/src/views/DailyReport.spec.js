// @vitest-environment jsdom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import DailyReport from './DailyReport.vue'

const mocks = vi.hoisted(() => ({ auth: null, daily: vi.fn(), overview: vi.fn(), recycle: vi.fn(), stats: vi.fn(), orders: vi.fn() }))
vi.mock('../stores/auth.js', async () => {
  const { reactive } = await import('vue')
  mocks.auth = reactive({ user: { permissions: ['report:daily'] }, can: code => mocks.auth.user.permissions.includes(code) })
  return { useAuthStore: () => mocks.auth }
})
vi.mock('vue-router', () => ({ useRouter: () => ({ back: vi.fn(), push: vi.fn() }) }))
vi.mock('../api/request.js', () => ({ api: { reportDaily: mocks.daily, reportOverview: mocks.overview, reportRecycle: mocks.recycle, processingStatistics: mocks.stats, processingOrders: mocks.orders } }))
enableAutoUnmount(afterEach)

describe('independent daily access', () => {
  beforeEach(() => {
    mocks.auth.user.permissions = ['report:daily']
    vi.clearAllMocks()
    mocks.daily.mockResolvedValue({ amount: 128, order_count: 2, turnover: 128 })
  })
  it('loads only the permitted daily endpoint without store-performance or operational grants', async () => {
    const wrapper = mount(DailyReport)
    await flushPromises()
    expect(wrapper.text()).toContain('¥128.00')
    expect(wrapper.text()).not.toContain('员工排行')
    expect(mocks.daily).toHaveBeenCalled()
    expect(mocks.overview).not.toHaveBeenCalled()
    expect(mocks.recycle).not.toHaveBeenCalled()
    expect(mocks.stats).not.toHaveBeenCalled()
    expect(mocks.orders).not.toHaveBeenCalled()
  })
  it('discards an in-flight daily result when access is revoked', async () => {
    let resolve
    mocks.daily.mockReturnValue(new Promise(done => { resolve = done }))
    const wrapper = mount(DailyReport)
    await flushPromises()
    mocks.auth.user.permissions = []
    await flushPromises()
    resolve({ amount: 98765 })
    await flushPromises()
    expect(wrapper.text()).toContain('当前账号未开启经营日报')
    expect(wrapper.text()).not.toContain('98,765')
  })
})
