import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const app = readFileSync(new URL('./App.vue', import.meta.url), 'utf8')

describe('收银端：取货照片选填 + 完成加工结算备注', () => {
  it('取货不再强制要求照片（可加可不加）', () => {
    // 前端不再用照片数量拦截
    expect(app).not.toContain('请先添加至少1张取货照片')
    expect(app).toContain(':disabled="procPickup.busy"')
    // 文案要告诉店员是选填
    expect(app).toContain('取货照片为选填')
  })

  it('完成加工弹窗有结算备注输入，并随状态提交一起发送', () => {
    expect(app).toContain('v-model="procFinish.settlementRemark"')
    expect(app).toContain('settlementRemark: String(procFinish.settlementRemark || \'\').trim()')
    expect(app).toContain('⑦ 结算备注')
  })

  it('打开完成加工时带出已有备注，便于修改', () => {
    expect(app).toContain('procFinish.settlementRemark = o.settlement_remark || \'\'')
  })
})
