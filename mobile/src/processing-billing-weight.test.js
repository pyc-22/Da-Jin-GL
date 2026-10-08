import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const view = readFileSync(resolve(process.cwd(), 'src/views/ProcessingOrders.vue'), 'utf8')

// 移动端开单同样不能出现计费总克重输入框（完工才填）。
describe('mobile processing billing weight', () => {
  it('never asks for 计费总克重 on the order-creation form', () => {
    expect(view).not.toContain('v-model.number="form.billingWeight"')
    expect(view).not.toContain('v-model="form.billingWeight"')
    // 开单提交走统一构造器（里面显式留空 billingWeight，工费先记 0）
    expect(view).toContain('buildProcessingDraftPayload(form, selectedItem.value, activeRef.value)')
  })

  it('explains the 按克 fee rule on the order form', () => {
    expect(view).toContain('工费在完工登记成品实重时按')
  })
})
