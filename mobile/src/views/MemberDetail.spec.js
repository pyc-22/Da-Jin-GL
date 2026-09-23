// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import MemberDetail from './MemberDetail.vue'

vi.mock('../api/request.js', () => ({ api: {
  member: vi.fn().mockResolvedValue({ member: { member_id: 1, name: '王女士', phone: '13800000000' } }),
  consume: vi.fn().mockResolvedValue([{ consume_id: 5, order_id: 9, order_no: 'XS20260917001', consume_time: '2026-09-17 12:00:00', amount: 888, items: '足金戒指 × 1', pay_method: 'WECHAT' }]),
  processingOrders: vi.fn().mockResolvedValue([])
} }))
vi.mock('../stores/auth.js', () => ({ useAuthStore: () => ({ role: 'SALES' }) }))
vi.mock('vue-router', () => ({ useRoute: () => ({ params: { id: '1' } }), useRouter: () => ({ back: vi.fn(), push: vi.fn() }) }))

describe('MemberDetail consumption records', () => {
  it('shows order number, goods, payment method, time and amount as separate details', async () => {
    const wrapper = mount(MemberDetail)
    await flushPromises()
    const text = wrapper.text()
    expect(text).toContain('订单号：XS20260917001')
    expect(text).toContain('商品：足金戒指 × 1')
    expect(text).toContain('支付：微信')
    expect(text).toContain('2026-09-17 12:00')
    expect(text).toContain('¥888.00')
  })
})
