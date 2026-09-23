// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import PendingTasks from './PendingTasks.vue'

const mocks = vi.hoisted(() => ({ processingOrders: vi.fn(), processingPay: vi.fn() }))
vi.mock('../api/request.js', () => ({
  api: { processingOrders: mocks.processingOrders, processingPay: mocks.processingPay },
  http: { defaults: { baseURL: 'http://localhost:8080' } }
}))
vi.mock('../api/upload.js', () => ({ uploadImage: vi.fn() }))
vi.mock('../stores/auth.js', () => ({ useAuthStore: () => ({ role: 'MANAGER', user: { storeName: '测试店' } }) }))
vi.mock('../stores/app.js', () => ({ useAppStore: () => ({ eventVersion: 0, lastEventType: '' }) }))
vi.mock('vue-router', () => ({ useRouter: () => ({ back: vi.fn() }) }))

describe('mobile processing pending tasks', () => {
  it('shows outstanding money read-only and has no collection action', async () => {
    mocks.processingOrders.mockResolvedValue([{
      processing_order_id: 12, order_no: 'JG0012', status: 'PENDING', customer_name: '张三',
      due_amount: 100, paid_amount: 0, handover: 1
    }])
    mocks.processingPay.mockReset()
    const wrapper = mount(PendingTasks)
    await flushPromises()
    expect(wrapper.get('.todo-card').text()).toContain('未收 ¥100.00')
    expect(wrapper.get('.todo-actions').text()).not.toContain('收款')
    expect(wrapper.get('.todo-actions').text()).not.toContain('确认加工')
    expect(mocks.processingPay).not.toHaveBeenCalled()
  })
})
