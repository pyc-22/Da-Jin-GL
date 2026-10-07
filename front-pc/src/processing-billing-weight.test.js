import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const app = readFileSync(resolve(process.cwd(), 'src/App.vue'), 'utf8')

// 计费总克重要等加工完成、知道成品实际克重时才能填：开单阶段一律不能出现该输入框，
// 否则柜面会以为必须先填、按克项目就开不了单。
describe('cashier processing billing weight', () => {
  it('never asks for 计费总克重 on the order-creation form', () => {
    expect(app).not.toContain('v-model.number="processingForm.billingWeight"')
    expect(app).not.toContain('v-model="processingForm.billingWeight"')
    // 开单提交仍显式留空，工费先记 0
    expect(app).toContain('billingWeight: null,')
  })

  it('keeps the billing weight input on completion weighing only', () => {
    expect(app).toContain('v-model.number="procFinish.billingWeight"')
    expect(app).toContain('v-model.number="procWeighForm.billingWeight"')
  })

  it('explains on the order form that 按克 fees are computed at completion', () => {
    expect(app).toContain('按克项目：工费在完工登记成品实重时按')
  })
})
