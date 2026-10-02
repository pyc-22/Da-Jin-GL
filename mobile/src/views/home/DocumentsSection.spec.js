// @vitest-environment jsdom
import { mount, flushPromises } from '@vue/test-utils'
import { reactive } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import DocumentsSection from './DocumentsSection.vue'

const state = vi.hoisted(() => ({ auth: null, app: null, sales: vi.fn(), processing: vi.fn(), recycle: vi.fn(), push: vi.fn() }))
vi.mock('../../stores/auth.js', () => ({ useAuthStore: () => state.auth }))
vi.mock('../../stores/app.js', () => ({ useAppStore: () => state.app }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: state.push }) }))
vi.mock('../../api/request.js', () => ({ api: { reportSales: state.sales, processingOrders: state.processing, reportRecycle: state.recycle } }))

const sale = { order_id: 1, order_no: 'XS001', goods_name: '足金戒指', actual_paid: 800, remaining_amount: 0, date: '2026-10-02 10:00:00' }
const processing = { processing_order_id: 2, order_no: 'JG002', item_name_snapshot: '改圈', due_amount: 200, status: 'PROCESSING', create_time: '2026-10-02 12:00:00' }
const recycle = { recycle_order_id: 3, bill_no: 'HS003', material_type: '足金旧料', total_amount: 700, create_time: '2026-10-02 09:00:00' }
let wrapper
beforeEach(() => {
  state.auth = reactive({ permissions: ['report:view', 'processing:view'], can: permission => state.auth.permissions.includes(permission) })
  state.app = reactive({ eventVersion: 0 })
  state.sales.mockReset().mockResolvedValue({ records: [sale] })
  state.processing.mockReset().mockResolvedValue([processing])
  state.recycle.mockReset().mockResolvedValue({ records: [recycle] })
  state.push.mockReset()
})
afterEach(() => wrapper?.unmount())

describe('document aggregation', () => {
  it('starts all queries together, sorts documents, filters and opens processing detail', async () => {
    let resolveSales
    state.sales.mockReturnValueOnce(new Promise(resolve => { resolveSales = resolve }))
    wrapper = mount(DocumentsSection)
    expect(state.sales).toHaveBeenCalledWith({ timeType: 'month' })
    expect(state.processing).toHaveBeenCalledTimes(1)
    expect(state.recycle).toHaveBeenCalledTimes(1)
    await flushPromises()
    expect(wrapper.text()).toContain('正在汇总单据')
    resolveSales({ records: [sale] })
    await flushPromises()
    expect(wrapper.findAll('.document-card').map(card => card.text().slice(0, 5))).toEqual(['JG002', 'XS001', 'HS003'])
    await wrapper.findAll('.chips button').find(button => button.text().startsWith('加工')).trigger('click')
    expect(wrapper.findAll('.document-card')).toHaveLength(1)
    await wrapper.get('.document-card').trigger('click')
    expect(state.push).toHaveBeenCalledWith('/processing?id=2')
  })

  it('keeps available documents when one source fails and supports retry', async () => {
    state.recycle.mockRejectedValueOnce(new Error('temporary error'))
    wrapper = mount(DocumentsSection)
    await flushPromises()
    expect(wrapper.findAll('.document-card')).toHaveLength(2)
    expect(wrapper.get('[role="alert"]').text()).toContain('回收单加载失败')
    await wrapper.get('.text-action').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.document-card')).toHaveLength(3)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('only requests records allowed by current permissions and clears rows on permission loss', async () => {
    state.auth.permissions = ['processing:view']
    wrapper = mount(DocumentsSection)
    await flushPromises()
    expect(state.sales).not.toHaveBeenCalled()
    expect(state.recycle).not.toHaveBeenCalled()
    expect(wrapper.findAll('.document-card')).toHaveLength(1)
    state.auth.permissions = []
    await flushPromises()
    expect(wrapper.findAll('.document-card')).toHaveLength(0)
    expect(wrapper.text()).toContain('暂无单据')
    expect(state.processing).toHaveBeenCalledTimes(1)
  })
})
