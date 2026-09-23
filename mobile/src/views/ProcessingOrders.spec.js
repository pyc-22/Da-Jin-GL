// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ProcessingOrders from './ProcessingOrders.vue'

const mocks = vi.hoisted(() => ({
  processingOrders: vi.fn(),
  processingOrder: vi.fn(),
  processingItems: vi.fn(),
  processingCraftsmen: vi.fn(),
  oldMaterialTypes: vi.fn(),
  processingPhotos: vi.fn(),
  processingCreate: vi.fn(),
  processingPay: vi.fn(),
  processingHandover: vi.fn(),
  httpGet: vi.fn()
}))

vi.mock('../api/request.js', () => ({
  api: {
    processingOrders: mocks.processingOrders,
    processingOrder: mocks.processingOrder,
    processingItems: mocks.processingItems,
    processingCraftsmen: mocks.processingCraftsmen,
    oldMaterialTypes: mocks.oldMaterialTypes
    ,processingPhotos: mocks.processingPhotos,
    processingCreate: mocks.processingCreate,
    processingPay: mocks.processingPay,
    processingHandover: mocks.processingHandover
  },
  http: { get: mocks.httpGet, defaults: { baseURL: 'http://localhost:8080' } }
}))
vi.mock('../api/upload.js', () => ({ uploadImage: vi.fn() }))
vi.mock('../stores/auth.js', () => ({ useAuthStore: () => ({ role: 'MANAGER', user: { storeName: '测试店' } }) }))
vi.mock('../stores/app.js', () => ({ useAppStore: () => ({ eventVersion: 0, lastEventType: '', gold: [], loadGold: vi.fn() }) }))
vi.mock('vue-router', () => ({ useRouter: () => ({ back: vi.fn() }) }))

describe('ProcessingOrders mobile actions', () => {
  beforeEach(() => {
    const order = { processing_order_id: 12, order_no: 'JG0012', status: 'PENDING', customer_name: '张三', customer_phone: '13800000000', due_amount: 100, paid_amount: 0 }
    mocks.processingOrders.mockReset().mockResolvedValue([order])
    mocks.processingOrder.mockReset().mockResolvedValue({ ...order, incoming_photos: [], weigh_photos: [], payments: [] })
    mocks.processingItems.mockReset().mockResolvedValue([{ item_id: 5, name: '戒指加工', category_name: '首饰加工', labor_fee: 100, processing_days: 2 }])
    mocks.processingCraftsmen.mockReset().mockResolvedValue([{ user_id: 21, real_name: '王师傅', role_code: 'CRAFTSMAN' }])
    mocks.oldMaterialTypes.mockReset().mockResolvedValue([])
    mocks.processingPhotos.mockReset().mockResolvedValue({ ...order, incoming_photos: [], weigh_photos: [], pickup_photos: [], payments: [] })
    mocks.processingCreate.mockReset().mockResolvedValue(order)
    mocks.processingPay.mockReset().mockResolvedValue(order)
    mocks.processingHandover.mockReset().mockResolvedValue({ ...order, handover: 1 })
    mocks.httpGet.mockReset()
    vi.spyOn(window, 'alert').mockImplementation(() => {})
  })

  it('does not expose a printing action on mobile', async () => {
    const wrapper = mount(ProcessingOrders)
    await flushPromises()
    await wrapper.get('.processing-card').trigger('click')
    await flushPromises()
    expect(wrapper.get('.sheet-actions').text()).toContain('一键拨号')
    expect(wrapper.get('.sheet-actions').text()).not.toContain('打印')
    expect(wrapper.text()).not.toContain('送收银端打印')
    expect(mocks.httpGet).not.toHaveBeenCalled()
  })

  it('loads management craftsmen for mobile processing orders', async () => {
    const wrapper = mount(ProcessingOrders)
    await flushPromises()
    const createButton = wrapper.findAll('button').find(button => button.text().includes('开加工单'))
    await createButton.trigger('click')
    await flushPromises()

    const craftsmanField = wrapper.findAll('label').find(label => label.text().includes('加工师傅'))
    expect(mocks.processingCraftsmen).toHaveBeenCalledOnce()
    expect(craftsmanField.get('select').text()).toContain('王师傅')
  })

  it('creates an order without collecting money and transfers it to the cashier', async () => {
    const wrapper = mount(ProcessingOrders)
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text().includes('开加工单')).trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('收取定金')
    expect(wrapper.text()).not.toContain('定金支付方式')
    const field = label => wrapper.findAll('.sheet-body label').find(node => node.text().includes(label)).get('input')
    await field('客户姓名').setValue('张三')
    await field('客户电话').setValue('13800000000')
    await wrapper.find('.sheet-body select').setValue('5')
    await wrapper.find('.sheet-body button.primary.full').trigger('click')
    await flushPromises()
    expect(mocks.processingCreate).toHaveBeenCalledOnce()
    expect(mocks.processingPay).not.toHaveBeenCalled()
    expect(mocks.processingHandover).toHaveBeenCalledWith(12)
  })

  it('does not create a duplicate order when transfer fails', async () => {
    mocks.processingHandover.mockRejectedValue(new Error('转交失败'))
    const wrapper = mount(ProcessingOrders)
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text().includes('开加工单')).trigger('click')
    await flushPromises()
    const field = label => wrapper.findAll('.sheet-body label').find(node => node.text().includes(label)).get('input')
    await field('客户姓名').setValue('张三')
    await field('客户电话').setValue('13800000000')
    await wrapper.find('.sheet-body select').setValue('5')
    await wrapper.find('.sheet-body button.primary.full').trigger('click')
    await flushPromises()
    expect(mocks.processingCreate).toHaveBeenCalledOnce()
    expect(window.alert).toHaveBeenCalledWith(expect.stringContaining('已创建，但转交失败'))
    expect(wrapper.find('.sheet-body button.primary.full').exists()).toBe(false)
  })

  it('shows a pickup photo section for completed orders', async () => {
    const order = { processing_order_id: 12, order_no: 'JG0012', status: 'COMPLETED', customer_name: '张三', customer_phone: '13800000000', due_amount: 100, paid_amount: 100, pickup_photos: [] }
    mocks.processingOrders.mockResolvedValue([order])
    mocks.processingOrder.mockResolvedValue({ ...order, incoming_photos: [], weigh_photos: [], pickup_photos: [], payments: [] })
    const wrapper = mount(ProcessingOrders)
    await flushPromises()
    await wrapper.get('.processing-card').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('取货照片')
    expect(wrapper.find('.pickup-photo-input').exists()).toBe(true)
  })
})
