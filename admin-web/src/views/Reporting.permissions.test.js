// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { computed, defineComponent, h, inject, provide, reactive } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import Finance from './Finance.vue'
import Commission from './Commission.vue'
import Processing from './Processing.vue'
import Approval from './Approval.vue'

const state = vi.hoisted(() => ({
  auth: null, app: null, route: null,
  finance: Object.fromEntries(['daily','monthly','records','shifts','summary','grossProfit','recycle'].map(k=>[k,vi.fn()])),
  commission: Object.fromEntries(['rules','records','settings'].map(k=>[k,vi.fn()])),
  processing: Object.fromEntries(['statistics','categories','items','craftsmen','salespeople'].map(k=>[k,vi.fn()])),
  approval: Object.fromEntries(['pending','history','detail','approve','reject'].map(k=>[k,vi.fn()])),
  message: { success: vi.fn(), error: vi.fn(), warning: vi.fn() },
  prompt: vi.fn()
}))
vi.mock('../api/modules',()=>({financeApi:state.finance,commissionApi:state.commission,processingApi:state.processing,approvalApi:state.approval,memberApi:{list:vi.fn()}}))
vi.mock('../stores/auth',()=>({useAuthStore:()=>state.auth}))
vi.mock('../stores/app',()=>({useAppStore:()=>state.app}))
vi.mock('vue-router',()=>({useRoute:()=>state.route,useRouter:()=>({push:vi.fn()})}))
vi.mock('element-plus',()=>({ElMessage:state.message,ElMessageBox:{prompt:state.prompt}}))

const rowsKey = Symbol('rows')
const passthrough={template:'<div><slot /></div>'}
const stubs={
  ElButton:{props:['disabled','loading'],template:'<button :disabled="disabled || loading"><slot /></button>'},
  ElAlert:{props:['title'],template:'<p role="alert">{{ title }}</p>'},
  ElDialog:{props:['modelValue'],template:'<section v-if="modelValue"><slot /><slot name="footer" /></section>'},
  ElTable:defineComponent({props:['data'],setup(props,{slots}){provide(rowsKey,computed(()=>props.data||[]));return()=>h('div',slots.default?.())}}),
  ElTableColumn:defineComponent({setup(props,{slots}){const rows=inject(rowsKey);return()=>h('div',rows.value.map(row=>slots.default?.({row})))}}),
  ...Object.fromEntries(['ElTabs','ElTabPane','ElDescriptions','ElDescriptionsItem','ElTag','ElSelect','ElOption','ElForm','ElFormItem','ElRadioGroup','ElRadio','ElDivider','ElCollapse','ElCollapseItem'].map(k=>[k,passthrough])),
  ...Object.fromEntries(['ElInput','ElInputNumber','ElDatePicker','ElSwitch','ElImage','ElCheckbox','ElRadioButton'].map(k=>[k,true]))
}
let wrapper
function grant(...permissions){state.auth.user.permissions=permissions}
function button(text){return wrapper.findAll('button').find(b=>b.text()===text)}
async function start(component){wrapper=mount(component,{global:{stubs,directives:{loading:{}}}});await flushPromises()}
beforeEach(()=>{
  state.auth=reactive({role:'MANAGER',user:{permissions:[]},can:code=>state.auth.user.permissions.includes(code)})
  state.app=reactive({eventVersion:0,lastEventType:'',paymentChannels:[]})
  state.route=reactive({meta:{processingTab:'dashboard'},fullPath:'/processing-dashboard'})
  for(const api of [state.finance,state.commission,state.processing,state.approval])for(const fn of Object.values(api))fn.mockReset().mockResolvedValue([])
  for(const fn of Object.values(state.message))fn.mockReset()
  state.commission.settings.mockResolvedValue({defaultCommissionRate:0.02,processingSalesCommissionRate:0.01})
  state.processing.statistics.mockResolvedValue({paid_amount:128,item_ranking:[]})
  state.finance.daily.mockResolvedValue({amount:128,order_count:2})
  state.prompt.mockReset().mockResolvedValue({value:'fixture reason'})
})
afterEach(()=>{wrapper?.unmount();wrapper=null})

describe('independent manager reporting and submission states',()=>{
  it('daily reporting remains usable without performance, monthly or processing access',async()=>{
    grant('report:daily')
    await start(Finance)
    expect(state.finance.summary).toHaveBeenCalledWith(expect.objectContaining({reportType:'daily'}))
    expect(state.finance.daily).toHaveBeenCalled()
    expect(state.finance.monthly).not.toHaveBeenCalled()
    expect(state.finance.grossProfit).not.toHaveBeenCalled()
    expect(wrapper.text()).not.toContain('毛利分析')
  })
  it('selects monthly directly when it is the only permitted finance view',async()=>{
    grant('report:monthly')
    await start(Finance)
    expect(state.finance.summary).toHaveBeenCalledWith(expect.objectContaining({reportType:'monthly'}))
    expect(state.finance.monthly).toHaveBeenCalled()
    expect(state.finance.daily).not.toHaveBeenCalled()
  })
  it('discards in-flight finance records when the permission changes',async()=>{
    grant('report:daily')
    let resolve
    state.finance.records.mockReturnValue(new Promise(done=>{resolve=done}))
    await start(Finance)
    grant('report:recycle')
    await flushPromises()
    resolve([{category:'SALE',amount:98765,related_bill_no:'STALE_SALES'}])
    await flushPromises()
    expect(wrapper.text()).not.toContain('STALE_SALES')
    expect(wrapper.text()).toContain('回收业务统计')
  })
  it('commission settings do not automatically grant employee records or payroll export',async()=>{
    grant('commission:manage')
    await start(Commission)
    expect(state.commission.settings).toHaveBeenCalled()
    expect(state.commission.records).not.toHaveBeenCalled()
    expect(wrapper.text()).not.toContain('导出工资表')
    expect(wrapper.text()).not.toContain('提成明细与工资表')
  })
  it('shows a retryable commission load error',async()=>{
    grant('report:commission')
    state.commission.records.mockRejectedValueOnce(new Error('fixture load error'))
    await start(Commission)
    expect(wrapper.get('[role="alert"]').text()).toContain('fixture load error')
    await button('重试').trigger('click')
    await flushPromises()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })
  it('processing statistics do not require access to processing orders or staff lists',async()=>{
    grant('report:processing')
    await start(Processing)
    expect(state.processing.statistics).toHaveBeenCalled()
    expect(state.processing.craftsmen).not.toHaveBeenCalled()
    expect(state.processing.salespeople).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('128.00')
    grant()
    await flushPromises()
    expect(wrapper.text()).not.toContain('加工单数')
  })
  it('stock-check actions require both handling and stock-check approval permissions',async()=>{
    state.approval.pending.mockResolvedValue([{approval_id:8,type:'STOCK_CHECK',status:1}])
    grant('approval:view','approval:handle')
    await start(Approval)
    expect(button('通过')).toBeUndefined()
    grant('approval:view','approval:handle','stock:check:approve')
    await flushPromises()
    expect(button('通过')).toBeDefined()
  })
  it('locks duplicate rejection clicks and keeps the row when rejection fails',async()=>{
    state.approval.pending.mockResolvedValue([{approval_id:8,type:'MEMBER_CLAIM',status:1}])
    grant('approval:view','approval:handle')
    let reject
    state.approval.reject.mockReturnValue(new Promise((_,fail)=>{reject=fail}))
    await start(Approval)
    await button('驳回').trigger('click')
    await flushPromises()
    expect(button('驳回').element.disabled).toBe(true)
    await button('驳回').trigger('click')
    expect(state.approval.reject).toHaveBeenCalledTimes(1)
    reject(new Error('fixture rejection error'))
    await flushPromises()
    expect(state.message.error).toHaveBeenCalledWith('fixture rejection error')
    expect(state.message.success).not.toHaveBeenCalled()
    expect(button('驳回').element.disabled).toBe(false)
  })
})
