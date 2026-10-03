// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import Dashboard from './Dashboard.vue'
import { dashboardApi } from '../api/modules'
import * as echarts from 'echarts/core'

const chart = vi.hoisted(() => ({ setOption: vi.fn(), dispose: vi.fn(), resize: vi.fn() }))
vi.mock('echarts/core', () => ({ use: vi.fn(), init: vi.fn(() => chart) }))
vi.mock('../stores/app', () => ({ useAppStore: () => ({ eventVersion: 0 }) }))
vi.mock('../api/modules', () => ({ dashboardApi: { get: vi.fn() } }))
const stubs = {
  ElAlert: { props: ['title'], template: '<div role="alert">{{ title }}</div>' },
  ElSkeleton: true, ElButton: { template: '<button><slot /></button>' },
  ElTable: true, ElTableColumn: true, ElTag: true
}
let wrapper
afterEach(() => { wrapper?.unmount(); vi.clearAllMocks() })
describe('dashboard loading and retry', () => {
  it('renders the chart after the initial loading state', async () => {
    dashboardApi.get.mockResolvedValue({ revenue: 180, trend: [{ day: '10-03', amount: 180 }], ranking: [] })
    wrapper = mount(Dashboard, { global: { stubs } })
    await flushPromises()
    expect(echarts.init).toHaveBeenCalledWith(wrapper.get('.chart').element)
    expect(chart.setOption.mock.calls[0][0].series[0].data).toEqual([180])
  })
  it('shows a failure instead of fictitious zero totals, and retries', async () => {
    dashboardApi.get.mockRejectedValueOnce(new Error('服务暂时中断')).mockResolvedValueOnce({ revenue: 180, trend: [], ranking: [] })
    wrapper = mount(Dashboard, { global: { stubs } })
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('服务暂时中断')
    expect(wrapper.find('.kpi-grid').exists()).toBe(false)
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.get('.kpi-grid').text()).toContain('180.00')
  })
})
