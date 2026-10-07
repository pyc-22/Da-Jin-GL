import { describe, expect, it } from 'vitest'
import * as admin from './goldPricing.js'
import * as mobile from '../../../mobile/src/utils/goldPricing.js'

describe.each([['admin', admin], ['mobile', mobile]])('%s gold pricing', (_, pricing) => {
  const valid = { pricingMode: 'AUTO', basePrice: 900.2, purityCoefficient: 1, markup: 20, recycleDeduction: 10, roundingRule: 'NONE', marketStatus: 'OPEN' }
  it('distinguishes a missing reference quote from money zero', () => {
    for (const value of [undefined, null, '', 0, 'bad']) expect(pricing.quoteMoney(value)).toBe('—')
    expect(pricing.quoteMoney(906.8)).toBe('¥906.80')
    expect(pricing.pricingPreview({ ...valid, basePrice: null })).toBeNull()
  })
  it('blocks equal prices including those made equal by rounding', () => {
    expect(pricing.autoPricingIssue({ ...valid, markup: 0, recycleDeduction: 0 })).toContain('至少填写一项')
    expect(pricing.autoPricingIssue({ ...valid, markup: 0.01, recycleDeduction: 0, roundingRule: 'YUAN' })).toContain('取整后')
  })
  it('allows a valid spread with a closed quote but rejects missing or failed quotes', () => {
    expect(pricing.autoPricingIssue({ ...valid, marketStatus: 'CLOSED' })).toBe('')
    expect(pricing.autoPricingIssue({ ...valid, basePrice: null })).toContain('暂无有效基准行情')
    expect(pricing.autoPricingIssue({ ...valid, marketStatus: 'ERROR' })).toContain('行情异常')
    expect(pricing.autoPricingIssue({ ...valid, pricingMode: 'MANUAL', basePrice: null })).toBe('')
  })
  it('applies the full-gold line when converting old gold into money', () => {
    expect(pricing.finenessFactor(0.999)).toBe(1)
    expect(pricing.finenessFactor(0.995)).toBe(1)
    expect(pricing.finenessFactor(0.916)).toBeCloseTo(0.916)
    // 门店实际那单：旧金 10g、足金999、回收价 860 → 折抵 8600（不是 8591.40）
    expect(pricing.oldGoldDeduction(10, 0.999, 860)).toBeCloseTo(8600)
    expect(pricing.oldGoldDeduction(10, 0.916, 860)).toBeCloseTo(7877.6)
  })
})
