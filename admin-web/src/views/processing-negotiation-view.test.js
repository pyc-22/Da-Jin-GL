import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(resolve(process.cwd(), 'src/views/Processing.vue'), 'utf8')

describe('processing discount display', () => {
  it('shows ordinary negotiated discounts and keeps group discounts distinct', () => {
    expect(source).toContain('<el-table-column label="优惠" align="right">')
    expect(source).toContain("selectedOrder.promotion_channel ? '团购优惠' : '议价优惠'")
    expect(source).toContain('v-if="selectedOrder.promotion_reason" label="优惠原因"')
  })
})
