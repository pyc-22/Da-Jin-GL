import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const view = readFileSync(resolve(process.cwd(), 'src/views/Processing.vue'), 'utf8')

// 同收银端口径：计费总克重只在完工称重登记时填，新增加工单弹窗不得要求该字段。
describe('admin processing billing weight', () => {
  it('never asks for 计费总克重 when creating an order', () => {
    expect(view).not.toContain('v-model="orderForm.billingWeight"')
    expect(view).not.toContain('label="计费总克重(g)"')
  })

  it('keeps the billing weight input on the weighing dialog', () => {
    expect(view).toContain('v-model="weighForm.billingWeight"')
    expect(view).toContain('计费总克重到成品称重这一步才知道')
  })
})
