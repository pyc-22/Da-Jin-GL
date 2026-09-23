import { describe, expect, it } from 'vitest'
import { formatApprovalReason } from './format.js'

describe('mobile display formatting', () => {
  it('renders approval reasons in Chinese without changing Chinese descriptions', () => {
    expect(formatApprovalReason({ type: 'REFUND', reason: 'test again' })).toBe('退货退款申请')
    expect(formatApprovalReason({ type: 'RECYCLE', reason: '大额回收超过配置限额' })).toBe('大额回收超过配置限额')
    expect(formatApprovalReason({ type: 'STOCK_OUT', reason: '{}' })).toBe('手动出库审批')
  })
})
