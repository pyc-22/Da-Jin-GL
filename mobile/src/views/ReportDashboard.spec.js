// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ReportDashboard from './ReportDashboard.vue'

const mocks = vi.hoisted(() => ({
  reportGold: vi.fn(),
  reportEmployee: vi.fn(),
  route: { params: { kind: 'gold' } },
  push: vi.fn()
}))

vi.mock('../api/request.js', () => ({
  api: {
    reportGold: mocks.reportGold,
    reportEmployee: mocks.reportEmployee,
    systemTarget: vi.fn().mockResolvedValue({ monthlySalesTarget: 0 })
  }
}))
vi.mock('../stores/auth.js', () => ({ useAuthStore: () => ({ role: 'MANAGER', user: { storeName: '测试店' } }) }))
vi.mock('../stores/app.js', () => ({ useAppStore: () => ({ offline: false, eventVersion: 0, lastEventType: '' }) }))
vi.mock('vue-router', () => ({ useRoute: () => mocks.route, useRouter: () => ({ push: mocks.push }) }))

describe('ReportDashboard', () => {
  beforeEach(() => {
    mocks.route.params.kind = 'gold'
    mocks.reportGold.mockReset().mockResolvedValue({
      average: 505,
      max: 510,
      min: 500,
      prices: [{ day: '2026-09-17', price: 505 }],
      salesWeight: [{ day: '2026-09-17', weight: 3.2 }],
      distribution: [{ price_bucket: 500, weight: 3.2 }]
    })
    mocks.reportEmployee.mockReset()
  })

  it('supports all requested periods and sends the selected period to the API', async () => {
    const wrapper = mount(ReportDashboard)
    await flushPromises()
    for (const [label, key] of [['昨天', 'yesterday'], ['上月', 'lastMonth']]) {
      await wrapper.get('.filter-tabs').findAll('button').find(button => button.text() === label).trigger('click')
      await flushPromises()
      expect(mocks.reportGold).toHaveBeenLastCalledWith(expect.objectContaining({ timeType: key }))
    }
    expect(wrapper.get('.filter-tabs').text()).toContain('今天')
    expect(wrapper.get('.filter-tabs').text()).toContain('本周')
    expect(wrapper.get('.filter-tabs').text()).toContain('本月')
    expect(wrapper.get('.filter-tabs').text()).toContain('自定义')
  })

  it('initializes and labels the custom date range', async () => {
    const wrapper = mount(ReportDashboard)
    await flushPromises()
    await wrapper.get('.filter-tabs').findAll('button').find(button => button.text() === '自定义').trigger('click')
    await flushPromises()
    const inputs = wrapper.findAll('.custom-range input[type="date"]')
    const now = new Date()
    const pad = value => String(value).padStart(2, '0')
    const expectedStart = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-01`
    const expectedEnd = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
    expect(wrapper.get('.custom-range').text()).toContain('开始日期')
    expect(wrapper.get('.custom-range').text()).toContain('结束日期')
    expect(inputs.map(input => input.element.value)).toEqual([expectedStart, expectedEnd])
    expect(mocks.reportGold).toHaveBeenLastCalledWith(expect.objectContaining({
      timeType: 'custom', startDate: expectedStart, endDate: expectedEnd
    }))
  })

  it('shows the gold-price sales distribution returned by the backend', async () => {
    const wrapper = mount(ReportDashboard)
    await flushPromises()
    expect(wrapper.text()).toContain('金价区间销售分布')
    expect(wrapper.text()).toContain('¥500 - ¥510')
    expect(wrapper.text()).toContain('3.20g')
  })

  it('opens employee details in a bottom drawer instead of squeezing the row', async () => {
    mocks.route.params.kind = 'employee'
    mocks.reportEmployee.mockResolvedValue({ employees: [{ user_id: 7, name: '销售员工甲', amount: 34731, order_count: 11, weight: 12.3, commission: 347.31, categories: [{ category: '素金', amount: 34731, item_count: 11, weight: 12.3 }] }] })
    const wrapper = mount(ReportDashboard)
    await flushPromises()
    await wrapper.get('.report-row-button').trigger('click')
    expect(wrapper.get('.detail-drawer').text()).toContain('销售员工甲')
    expect(wrapper.get('.detail-drawer').text()).toContain('素金')
    await wrapper.get('.detail-drawer .modal-close').trigger('click')
    expect(wrapper.find('.detail-drawer').exists()).toBe(false)
  })
})
