import { describe, expect, it, vi } from 'vitest'
import { navigateNotification, resolveNotificationTarget } from './notificationNavigation.js'

describe('notification navigation', () => {
  it.each([
    [{ action: 'APPROVAL_CREATED', approvalId: 8 }, { path: '/manager/approval-center', query: { approvalId: '8' } }],
    [{ action: 'PROCESSING_READY', processing_order_id: 12 }, { path: '/processing', query: { id: '12' } }],
    [{ action: 'STOCK_UPDATED', goodsId: 4 }, { path: '/inventory', query: { goodsId: '4' } }],
    [{ action: 'VISIT_TASK_UPDATED' }, { path: '/visits' }],
    [{ title: '会员生日提醒' }, { path: '/sales/birthday' }],
    [{ notification_id: 99, content: '系统维护提示' }, { path: '/notifications', hash: '#notification-99' }]
  ])('resolves %j', (row, expected) => expect(resolveNotificationTarget(row)).toMatchObject(expected))

  it('uses the same target object for router navigation', async () => {
    const router = { push: vi.fn().mockResolvedValue() }
    await navigateNotification(router, { action: 'PROCESSING_READY', data: { processingOrderId: 5 } })
    expect(router.push).toHaveBeenCalledWith({ path: '/processing', query: { id: '5' } })
  })
})
