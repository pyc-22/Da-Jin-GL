// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import RecycleCreate from './RecycleCreate.vue'

const mocks = vi.hoisted(() => ({
  oldMaterialTypes: vi.fn(),
  recycleCreate: vi.fn(),
  back: vi.fn()
}))

vi.mock('../api/request.js', () => ({
  api: {
    oldMaterialTypes: mocks.oldMaterialTypes,
    recycleCreate: mocks.recycleCreate
  }
}))

vi.mock('../stores/app.js', () => ({
  useAppStore: () => ({
    gold: [{ price_type: '回收金价', price: 300 }],
    loadGold: vi.fn().mockResolvedValue(undefined)
  })
}))

vi.mock('vue-router', () => ({ useRouter: () => ({ back: mocks.back }) }))

describe('RecycleCreate', () => {
  beforeEach(() => {
    mocks.oldMaterialTypes.mockReset().mockResolvedValue([
      { type_id: 1, name: '足金999', status: 1 },
      { type_id: 2, name: '18K金', status: 0 },
      { type_id: 3, name: '铂金950', status: 1 }
    ])
    mocks.recycleCreate.mockReset().mockResolvedValue({
      recycleOrderId: 9,
      billNo: 'HS20260917001',
      amount: 2997,
      approvalRequired: false
    })
  })

  it('selects active material types, quotes first, then shows an unmistakable success result', async () => {
    const wrapper = mount(RecycleCreate)
    await flushPromises()

    const materialSelect = wrapper.get('[data-testid="material-type"]')
    expect(materialSelect.element.tagName).toBe('SELECT')
    expect(materialSelect.text()).toContain('足金999')
    expect(materialSelect.text()).toContain('铂金950')
    expect(materialSelect.text()).not.toContain('18K金')

    await wrapper.get('[data-testid="recycle-weight"]').setValue('10')
    expect(wrapper.get('[data-testid="quote-card"]').text()).toContain('¥2,997.00')

    await wrapper.get('[data-testid="open-confirm"]').trigger('click')
    expect(mocks.recycleCreate).not.toHaveBeenCalled()
    expect(wrapper.get('[data-testid="confirm-dialog"]').text()).toContain('确认回收并入账')

    await wrapper.get('[data-testid="confirm-submit"]').trigger('click')
    await flushPromises()
    expect(mocks.recycleCreate).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-testid="success-result"]').text()).toContain('回收成功')
    expect(wrapper.get('[data-testid="success-result"]').text()).toContain('¥2,997.00')
    expect(wrapper.find('[data-testid="recycle-form"]').exists()).toBe(false)
  })

  it('labels an approval-required recycle as pending instead of successful', async () => {
    mocks.recycleCreate.mockResolvedValueOnce({
      recycleOrderId: 10,
      billNo: 'HS20260917002',
      amount: 5994,
      approvalRequired: true
    })
    const wrapper = mount(RecycleCreate)
    await flushPromises()

    await wrapper.get('[data-testid="recycle-weight"]').setValue('20')
    await wrapper.get('[data-testid="open-confirm"]').trigger('click')
    await wrapper.get('[data-testid="confirm-submit"]').trigger('click')
    await flushPromises()

    const result = wrapper.get('[data-testid="success-result"]').text()
    expect(result).toContain('已提交店长审批')
    expect(result).toContain('等待审批')
    expect(result).not.toContain('回收成功')
  })
})
