// @vitest-environment jsdom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ReportDashboard from './ReportDashboard.vue'

const mocks = vi.hoisted(() => ({
  reportGold: vi.fn(),
  reportOverview: vi.fn(),
  reportSales: vi.fn(),
  reportEmployee: vi.fn(),
  commission: vi.fn().mockResolvedValue([]),
  reportMonthly: vi.fn(),
  processingStatistics: vi.fn(),
  reportRecycle: vi.fn(),
  auth: null,
  can: vi.fn(() => true),
  route: { params: { kind: 'gold' } },
  push: vi.fn()
}))

vi.mock('../api/request.js', () => ({
  api: {
    reportGold: mocks.reportGold,
    reportOverview: mocks.reportOverview,
    reportSales: mocks.reportSales,
    reportEmployee: mocks.reportEmployee,
    commission: mocks.commission,
    reportMonthly: mocks.reportMonthly,
    processingStatistics: mocks.processingStatistics,
    reportRecycle: mocks.reportRecycle,
    systemTarget: vi.fn().mockResolvedValue({ monthlySalesTarget: 0 })
  }
}))
vi.mock('../stores/auth.js', async () => {
  const { reactive } = await import('vue')
  mocks.auth = reactive({ role: 'MANAGER', can: mocks.can, user: { storeName: '测试店', permissions: [] } })
  return { useAuthStore: () => mocks.auth }
})
vi.mock('../stores/app.js', () => ({ useAppStore: () => ({ offline: false, eventVersion: 0, lastEventType: '' }) }))
vi.mock('vue-router', () => ({ useRoute: () => mocks.route, useRouter: () => ({ push: mocks.push }) }))
enableAutoUnmount(afterEach)
function grant(...permissions) {
  mocks.auth.user.permissions = permissions
  mocks.can.mockImplementation(code => mocks.auth.user.permissions.includes(code))
}

describe('ReportDashboard', () => {
  beforeEach(() => {
    mocks.can.mockImplementation(() => true)
    mocks.auth.role = 'MANAGER'
    mocks.auth.user.permissions = []
    mocks.commission.mockReset().mockResolvedValue([])
    mocks.reportMonthly.mockReset().mockResolvedValue([])
    mocks.processingStatistics.mockReset().mockResolvedValue({})
    mocks.reportRecycle.mockReset().mockResolvedValue({})
    mocks.route.params.kind = 'gold'
    mocks.reportGold.mockReset().mockResolvedValue({
      average: 505,
      max: 510,
      min: 500,
      prices: [{ day: '2026-09-17', price: 505 }],
      salesWeight: [{ day: '2026-09-17', weight: 3.2 }],
      distribution: [{ price_bucket: 500, weight: 3.2 }]
    })
    mocks.reportOverview.mockReset().mockResolvedValue({ summary: {}, trend: [], categories: [], employees: [], records: [] })
    mocks.reportSales.mockReset().mockResolvedValue({ summary: {}, records: [] })
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

  it('shows actual settlement amounts and payment method in sales details', async () => {
    mocks.route.params.kind = 'sales'
    mocks.reportOverview.mockResolvedValue({
      summary: { actual_paid: 180, sales_amount: 180, item_count: 1, gold_weight: 1.2, avg_order: 180 },
      categories: [],
      employees: [],
      records: []
    })
    mocks.reportSales.mockResolvedValue({
      summary: { actual_paid: 180, sales_amount: 180, item_count: 1, gold_weight: 1.2, avg_order: 180 },
      records: [{
        order_id: 12,
        order_no: 'XS20260928001',
        goods_name: '团购加工饰品',
        original_amount: 200,
        settlement_discount: 20,
        discounted_amount: 180,
        actual_paid: 180,
        remaining_amount: 0,
        settlement_discount_reason: '抖音团购',
        pay_method: 'DOUTUAN',
        employee_name: '销售员工甲',
        amount: 180,
        quantity: 1,
        weight: 1.2,
        date: '2026-09-28 10:00'
      }]
    })
    const wrapper = mount(ReportDashboard)
    await flushPromises()

    expect(wrapper.text()).toContain('销售实收')
    expect(wrapper.text()).toContain('¥180.00')
    await wrapper.get('.report-row-button').trigger('click')
    const drawer = wrapper.get('.detail-drawer')
    expect(drawer.text()).toContain('¥200.00')
    expect(drawer.text()).toContain('¥20.00')
    expect(drawer.text()).toContain('¥180.00')
    expect(drawer.text()).toContain('¥0.00')
    expect(drawer.text()).toContain('抖音团购')
  })

  it('defaults to an independently allowed report without requesting performance', async () => {
    grant('report:view', 'report:commission', 'report:daily', 'report:processing', 'report:recycle')
    const wrapper = mount(ReportDashboard)
    await flushPromises()
    expect(wrapper.get('.report-tabs').text()).toContain('提成统计')
    expect(wrapper.get('.report-tabs').text()).not.toContain('概览')
    expect(wrapper.get('.report-tabs').text()).not.toContain('月报')
    expect(mocks.commission).toHaveBeenCalledWith({ month: expect.stringMatching(/^\d{4}-\d{2}$/) })
    expect(mocks.reportOverview).not.toHaveBeenCalled()
    expect(mocks.reportGold).not.toHaveBeenCalled()
  })

  it('keeps month and last-month performance filters when monthly permission is off', async () => {
    mocks.route.params.kind = 'overview'
    grant('report:view', 'report:store-performance')
    const wrapper = mount(ReportDashboard)
    await flushPromises()
    expect(wrapper.get('.report-tabs').text()).not.toContain('月报')
    await wrapper.get('.filter-tabs').findAll('button').find(button => button.text() === '上月').trigger('click')
    await flushPromises()
    expect(mocks.reportOverview).toHaveBeenLastCalledWith({ timeType: 'lastMonth' })
  })

  it('opens the employee picker as a bottom sheet instead of a native select overlay', async () => {
    mocks.route.params.kind = 'overview'
    grant('report:view', 'report:store-performance')
    const wrapper = mount(ReportDashboard)
    await flushPromises()
    expect(wrapper.find('select').exists()).toBe(false)
    const field = wrapper.get('.picker-field')
    await field.trigger('click')
    const sheet = wrapper.get('.picker-sheet')
    expect(sheet.text()).toContain('全部员工')
    await sheet.findAll('button').find(button => button.text().includes('全部员工')).trigger('click')
    expect(wrapper.find('.picker-sheet').exists()).toBe(false)
  })

  it('sends the selected month to the dedicated monthly report', async () => {
    mocks.route.params.kind = 'monthly'
    grant('report:view', 'report:monthly')
    const wrapper = mount(ReportDashboard)
    await flushPromises()
    expect(wrapper.find('.filter-tabs').exists()).toBe(false)
    expect(wrapper.find('select').exists()).toBe(false)
    await wrapper.get('input[type="month"]').setValue('2026-08')
    await flushPromises()
    expect(mocks.reportMonthly).toHaveBeenLastCalledWith({ month: '2026-08' })
  })

  it('translates processing periods into the real statistics date parameters', async () => {
    mocks.route.params.kind = 'processing'
    grant('report:view', 'report:processing')
    const wrapper = mount(ReportDashboard)
    await flushPromises()
    await wrapper.get('.filter-tabs').findAll('button').find(button => button.text() === '上月').trigger('click')
    await flushPromises()
    const today = new Date()
    const first = new Date(today.getFullYear(), today.getMonth() - 1, 1)
    const last = new Date(today.getFullYear(), today.getMonth(), 0)
    const date = value => `${value.getFullYear()}-${String(value.getMonth()+1).padStart(2,'0')}-${String(value.getDate()).padStart(2,'0')}`
    expect(mocks.processingStatistics).toHaveBeenLastCalledWith({ from: date(first), to: date(last) })
  })

  it('discards an in-flight employee response after performance is revoked', async () => {
    mocks.route.params.kind = 'employee'
    grant('report:view', 'report:store-performance', 'report:commission')
    let resolve
    mocks.reportEmployee.mockReturnValue(new Promise(done => { resolve = done }))
    const wrapper = mount(ReportDashboard)
    await flushPromises()
    mocks.auth.user.permissions = ['report:view', 'report:commission']
    await flushPromises()
    resolve({ employees: [{ user_id: 7, name: 'STALE_EMPLOYEE', amount: 98765 }] })
    await flushPromises()
    expect(wrapper.text()).not.toContain('STALE_EMPLOYEE')
    expect(wrapper.find('.detail-drawer').exists()).toBe(false)
    expect(wrapper.get('.report-tabs').text()).toBe('提成统计')
  })

  it('clears the detail drawer even when the current report stays permitted', async () => {
    mocks.route.params.kind = 'employee'
    grant('report:view', 'report:store-performance', 'report:commission')
    mocks.reportEmployee.mockResolvedValue({ employees: [{ user_id: 7, name: '员工甲', amount: 10, categories: [] }] })
    const wrapper = mount(ReportDashboard)
    await flushPromises()
    await wrapper.get('.report-row-button').trigger('click')
    expect(wrapper.find('.detail-drawer').exists()).toBe(true)
    mocks.auth.user.permissions = ['report:view', 'report:store-performance']
    await flushPromises()
    expect(wrapper.find('.detail-drawer').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('提成统计')
  })

  it('hides employee and store-report controls for sales despite broader grants', async () => {
    mocks.auth.role = 'SALES'
    const wrapper = mount(ReportDashboard)
    await flushPromises()
    expect(wrapper.get('.report-tabs').text()).toBe('个人业绩个人销售明细')
    expect(wrapper.find('select').exists()).toBe(false)
  })
})
