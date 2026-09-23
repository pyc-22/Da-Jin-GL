// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import VisitsPage from './VisitsPage.vue'

vi.mock('../api/request.js', () => ({ api: {
  visits: vi.fn().mockResolvedValue([{ task_id: 1, status: 2, member_name: '李女士', phone: '13800000000', record: '佩戴正常，约定下月到店清洗', call_result: 'CONNECTED', call_started_at: '2026-09-17 09:30:00', update_time: '2026-09-17 09:35:00' }]),
  members: vi.fn().mockResolvedValue([]),
  startVisitCall: vi.fn(),
  recordVisit: vi.fn()
} }))
vi.mock('../stores/app.js', () => ({ useAppStore: () => ({ eventVersion: 0, lastEventType: '' }) }))
vi.mock('vue-router', () => ({ useRouter: () => ({ back: vi.fn() }) }))

describe('VisitsPage completed records', () => {
  it('shows the saved record, call result and completion time', async () => {
    const wrapper = mount(VisitsPage)
    await flushPromises()
    await wrapper.findAll('.filter-tabs button').find(button => button.text() === '已完成').trigger('click')
    expect(wrapper.text()).toContain('佩戴正常，约定下月到店清洗')
    expect(wrapper.text()).toContain('已接通')
    expect(wrapper.text()).toContain('2026-09-17 09:35')
  })

  it('shows a visible label for the optional next follow-up time', async () => {
    const wrapper = mount(VisitsPage)
    await flushPromises()
    await wrapper.findAll('.filter-tabs button').find(button => button.text() === '已完成').trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '查看/补充').trigger('click')
    expect(wrapper.get('.visit-next-follow-up').text()).toContain('下次跟进时间（选填）')
    expect(wrapper.get('input[type="datetime-local"]').attributes('aria-label')).toBe('下次跟进时间')
  })
})
