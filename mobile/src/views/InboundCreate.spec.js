// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import InboundCreate from './InboundCreate.vue'

const mocks = vi.hoisted(() => ({
  inboundStores: vi.fn(),
  inboundSuppliers: vi.fn(),
  push: vi.fn()
}))

vi.mock('../api/request.js', () => ({ api: {
  inboundStores: mocks.inboundStores,
  inboundSuppliers: mocks.inboundSuppliers,
  supplierCreate: vi.fn()
} }))
vi.mock('../stores/auth.js', () => ({ useAuthStore: () => ({ role: 'MANAGER', user: { store_id: 1, store_name: '一店' } }) }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: mocks.push, back: vi.fn() }) }))

describe('InboundCreate', () => {
  beforeEach(() => {
    localStorage.clear()
    mocks.push.mockReset()
    mocks.inboundStores.mockReset().mockResolvedValue([
      { store_id: 1, store_name: '一店' },
      { store_id: 2, store_name: '二店' }
    ])
    mocks.inboundSuppliers.mockReset().mockResolvedValue([
      { supplier_id: 8, supplier_name: '测试供应商', supplier_code: 'SUP8' }
    ])
  })

  it('switches all four inbound types and links their source selectors', async () => {
    const wrapper = mount(InboundCreate, { global: { directives: { permission: () => {} } } })
    await flushPromises()

    expect(wrapper.findAll('.inbound-types button').map(button => button.text())).toEqual([
      '采购入库', '调拨入库', '退货入库', '盘盈入库'
    ])
    expect(wrapper.text()).toContain('供应商')
    expect(wrapper.get('select').text()).toContain('测试供应商')
    expect(wrapper.findAll('select')[1].element.value).toBe('1')

    await wrapper.findAll('.inbound-types button')[1].trigger('click')
    expect(wrapper.text()).toContain('调出门店')
    expect(wrapper.find('select').text()).toContain('二店')
    expect(wrapper.find('select').text()).not.toContain('一店')

    await wrapper.findAll('.inbound-types button')[2].trigger('click')
    expect(wrapper.text()).not.toContain('供应商')
    expect(wrapper.text()).not.toContain('调出门店')
  })
})
