// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import InboundDetail from './InboundDetail.vue'

vi.mock('../api/request.js', () => ({ api: {
  inboundDetail: vi.fn().mockResolvedValue({
    inbound_no: 'RK001', inbound_type: 'profit', create_time: '2026-09-17 10:00:00',
    total_quantity: 1, total_weight: 1, total_amount: 600, items: []
  })
} }))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: '1' } }),
  useRouter: () => ({ back: vi.fn(), replace: vi.fn() })
}))

describe('InboundDetail mobile actions', () => {
  it('shows the voucher without a mobile print action', async () => {
    const wrapper = mount(InboundDetail)
    await flushPromises()
    expect(wrapper.text()).toContain('入库凭证')
    expect(wrapper.findAll('button').some(button => button.text().includes('打印'))).toBe(false)
  })
})
