// @vitest-environment jsdom
import { mount, flushPromises } from '@vue/test-utils'
import { reactive } from 'vue'
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import GoldSettings from './GoldSettings.vue'

const state = vi.hoisted(() => ({ app: null, auth: null, api: {} }))
vi.mock('vue-router', () => ({ useRouter: () => ({ back: vi.fn() }) }))
vi.mock('../stores/auth.js', () => ({ useAuthStore: () => state.auth }))
vi.mock('../stores/app.js', () => ({ useAppStore: () => state.app }))
vi.mock('../api/request.js', () => ({ api: state.api }))
let wrapper
const row = { type_id: 'GOLD', type_name: '足金', baseInstrument: 'Au_TD', pricingMode: 'AUTO', status: 1, markup: 0, recycleDeduction: 0, purityCoefficient: 0.999, roundingRule: 'NONE', salePrice: 960, recyclePrice: 870, marketStatus: 'CLOSED' }
const button = text => wrapper.findAll('button').find(b => b.text().includes(text))

beforeEach(() => {
  state.auth = { role: 'ADMIN', can: () => true }
  state.app = reactive({ eventVersion: 0, lastEventType: '', loadGold: vi.fn().mockResolvedValue() })
  Object.assign(state.api, { goldTypesAll: vi.fn().mockResolvedValue([{ ...row }]), goldLogs: vi.fn().mockResolvedValue([]), goldSpot: vi.fn().mockResolvedValue({}), updateGoldType: vi.fn().mockResolvedValue({}) })
})
afterEach(() => { wrapper?.unmount(); vi.clearAllMocks() })
describe('administrator pricing validation', () => {
  it('shows a missing quote instead of a zero base and prevents an invalid AUTO save', async () => {
    wrapper = mount(GoldSettings); await flushPromises()
    expect(wrapper.find('.market-settings-values').text()).toContain('—')
    expect(wrapper.find('.formula-preview').text()).toContain('暂无有效基准行情')
    await button('保存并同步全端').trigger('click'); await flushPromises()
    expect(state.api.updateGoldType).not.toHaveBeenCalled()
    expect(wrapper.find('[role="alert"]').text()).toContain('至少填写一项')
  })
  it('keeps a backend rejection visible in the administrator mobile page', async () => {
    state.api.goldTypesAll.mockResolvedValue([{ ...row, basePrice: 906.8, markup: 20, recycleDeduction: 10 }])
    state.api.updateGoldType.mockRejectedValue(new Error('取整后卖价须高于回收价，请调整取整规则'))
    wrapper = mount(GoldSettings); await flushPromises()
    await button('保存并同步全端').trigger('click'); await flushPromises()
    expect(wrapper.find('[role="alert"]').text()).toContain('取整后卖价须高于回收价')
  })
  it('prompts on remote config changes without replacing an unsaved edit', async () => {
    state.api.goldTypesAll.mockResolvedValue([{ ...row, basePrice: 906.8, markup: 20, recycleDeduction: 10 }])
    wrapper = mount(GoldSettings); await flushPromises()
    await wrapper.find('input').setValue('35')
    state.app.lastEventType = 'GOLD_TYPES_UPDATED'; state.app.eventVersion++
    await flushPromises()
    expect(wrapper.text()).toContain('配置已在其他终端更新')
    expect(wrapper.find('input').element.value).toBe('35')
  })
  it.each(['MANAGER', 'SALES'])('keeps %s out of full pricing settings', async role => {
    state.auth.role = role
    wrapper = mount(GoldSettings); await flushPromises()
    expect(state.api.goldTypesAll).not.toHaveBeenCalled()
    expect(wrapper.find('.gold-type-card').exists()).toBe(false)
  })
})
