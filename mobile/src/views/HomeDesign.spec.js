// @vitest-environment jsdom
import { mount, flushPromises } from '@vue/test-utils'
import { reactive } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import RoleHome from './RoleHome.vue'
import { createPinia, setActivePinia } from 'pinia'
const state=vi.hoisted(()=>({auth:null,app:null,route:null,push:vi.fn(),createOrder:vi.fn(),dashboard:vi.fn(),performance:vi.fn()}))
vi.mock('../stores/auth.js',()=>({useAuthStore:()=>state.auth}))
vi.mock('../stores/app.js',()=>({useAppStore:()=>state.app}))
vi.mock('vue-router',()=>({useRoute:()=>state.route,useRouter:()=>({push:state.push,replace:vi.fn()})}))
vi.mock('../api/request.js',()=>({http:{defaults:{baseURL:''}},api:new Proxy({}, {get:(_,name)=>name==='createOrder'?state.createOrder:name==='dashboard'?state.dashboard:name==='performance'?state.performance:async()=>name==='goods'?{records:[{goods_id:1,name:'按克足金',barcode:'G001',gold_type:'足金',price_type:1,weight:2,available_stock:20}]}:name==='processingSalespeople'?[{user_id:7,real_name:'导购测试'}]:name==='systemTarget'?{monthlySalesTarget:2000}:[]})}))
let wrapper
beforeEach(()=>{
 setActivePinia(createPinia())
 state.route={params:{role:'manager',section:'dashboard'}}
 state.auth=reactive({role:'MANAGER',user:{user_id:7,real_name:'测试店长',permissions:['*']},get permissions(){return this.user.permissions},can:code=>state.auth.role==='ADMIN'||state.auth.permissions.includes('*')||state.auth.permissions.includes(code)})
 const gold={price_type:'足金',salePrice:800,recyclePrice:750,basePrice:780,markup:20,recycleDeduction:30,marketStatus:'CLOSED'}
 state.app=reactive({gold:[gold],primaryGold:gold,silverSale:{salePrice:10,recyclePrice:8},silverRecycle:null,approvals:[],pendingInboundCount:0,eventVersion:0,unread:0,loadGold:vi.fn(),refreshLocalPendingInbounds:vi.fn()})
 state.createOrder.mockReset().mockResolvedValue({orderNo:'TEST-ORDER'})
 state.dashboard.mockReset().mockResolvedValue({amount:2500,order_count:2,trend:[],ranking:[]})
 state.performance.mockReset().mockResolvedValue({amount:500,commission:10})
})
afterEach(()=>wrapper?.unmount())
function start(){wrapper=mount(RoleHome,{global:{directives:{permission:{mounted(){}}}}});return flushPromises()}
describe('phase two home behavior',()=>{
 it('hides empty manager trend and complete pricing information',async()=>{
  await start()
  expect(wrapper.text()).toContain('足金卖价')
  expect(wrapper.text()).toContain('800.00')
  expect(wrapper.text()).not.toContain('基准')
  expect(wrapper.text()).not.toContain('金价设置')
  expect(wrapper.text()).not.toContain('7日营业额趋势')
  expect(wrapper.findAll('.tabbar button').map(b=>b.text())).toEqual(['首页','单据','加工开单','消息','我的'])
 })
 it('shows processing orders in the today order detail using the dashboard count and scope',async()=>{
  state.dashboard.mockResolvedValue({amount:0,order_count:1,salesOrderCount:0,processingOrderCount:1,todayOrderDetails:[{
   order_type:'PROCESSING',order_no:'JG20261011103925795A7F6',customer_name:'李女士',item_name:'银手镯加工',
   status:'PENDING',create_time:'2026-10-11 10:39',labor_fee:0,due_amount:0
  }],trend:[],ranking:[]})
  await start()
  const orderKpi=wrapper.findAll('.kpi').find(node=>node.text().includes('今日订单（销售 + 加工）'))
  expect(orderKpi).toBeTruthy()
  state.route.path='/manager/kpidetail'
  await orderKpi.trigger('click');await flushPromises()
  expect(wrapper.text()).toContain('今日 1 单 · 销售 0 单 + 加工 1 单')
  expect(wrapper.text()).toContain('加工 · 银手镯加工')
  expect(wrapper.text()).toContain('JG20261011103925795A7F6 · 李女士 · 10-11 10:39')
  expect(wrapper.text()).toContain('待完工称重')
  expect(wrapper.text()).toContain('待确认加工')
  expect(wrapper.text()).not.toContain('今日暂无明细')
 })
 it('labels sales and completed processing rows with their settlement amounts and statuses',async()=>{
  state.dashboard.mockResolvedValue({amount:1050,order_count:2,salesOrderCount:1,processingOrderCount:1,todayOrderDetails:[
   {order_type:'SALES',order_no:'XS001',customer_name:'王女士',status:'1',create_time:'2026-10-11 11:00',amount:150},
   {order_type:'PROCESSING',order_no:'JG002',customer_name:'赵先生',item_name:'戒指改圈',status:'COMPLETED',create_time:'2026-10-11 10:00',labor_fee:900,due_amount:900}
  ],trend:[],ranking:[]})
  await start()
  const orderKpi=wrapper.findAll('.kpi').find(node=>node.text().includes('今日订单（销售 + 加工）'))
  state.route.path='/manager/kpidetail'
  await orderKpi.trigger('click');await flushPromises()
  expect(wrapper.text()).toContain('今日 2 单 · 销售 1 单 + 加工 1 单')
  expect(wrapper.text()).toContain('实收 ¥150.00')
  expect(wrapper.text()).toContain('已完成')
  expect(wrapper.text()).toContain('工费 ¥900.00 · 应收 ¥900.00')
  expect(wrapper.text()).toContain('待取货')
 })
 it('uses actual sales performance and only final gold prices',async()=>{
  state.auth.role='SALES';state.route.params={role:'sales',section:'home'}
  await start()
  expect(wrapper.text()).toContain('本月业绩与提成')
  expect(wrapper.text()).toContain('已完成 25%')
  expect(wrapper.text()).not.toContain('基准')
  expect(wrapper.text()).not.toContain('金价设置')
 })
 it('hides manager sales KPIs, trend and ranking when store performance is disabled',async()=>{
  state.auth.user.permissions=['dashboard:view','stock:view','report:view','report:daily','report:commission','report:recycle']
  state.dashboard.mockResolvedValue({amount:2500,order_count:2,trend:[{day:'2026-10-02',amount:2500}],ranking:[{name:'PRIVATE_EMPLOYEE',amount:2500}]})
  await start()
  expect(wrapper.text()).not.toContain('今日营业额')
  expect(wrapper.text()).not.toContain('今日订单数')
  expect(wrapper.text()).not.toContain('员工排行榜')
  expect(wrapper.text()).not.toContain('7日营业额趋势')
  expect(wrapper.text()).toContain('库存预警')
  expect(wrapper.text()).toContain('回收克重')
  expect(state.performance).not.toHaveBeenCalled()
 })
 it('clears manager performance visibility while an old dashboard response is still pending',async()=>{
  state.auth.user.permissions=['dashboard:view','report:view','report:daily','report:store-performance']
  let resolveDashboard
  state.dashboard.mockReturnValueOnce(new Promise(resolve=>{resolveDashboard=resolve}))
  await start()
  state.auth.user.permissions=['dashboard:view','report:view','report:daily']
  await flushPromises()
  resolveDashboard({amount:98765,trend:[{day:'2026-10-02',amount:98765}],ranking:[{name:'PRIVATE_EMPLOYEE',amount:98765}]})
  await flushPromises()
  expect(wrapper.text()).not.toContain('PRIVATE_EMPLOYEE')
  expect(wrapper.text()).not.toContain('今日营业额')
  expect(wrapper.text()).not.toContain('7日营业额趋势')
 })
 it('hides sales performance and discards a pending response when report access is revoked',async()=>{
  state.auth.role='SALES';state.route.params={role:'sales',section:'home'}
  state.auth.user.permissions=['dashboard:view','report:view']
  let resolvePerformance
  state.performance.mockReturnValueOnce(new Promise(resolve=>{resolvePerformance=resolve}))
  await start()
  state.auth.user.permissions=['dashboard:view']
  await flushPromises()
  resolvePerformance({amount:98765,commission:1234,trend:[{day:'2026-10-02',amount:98765}]})
  await flushPromises()
  expect(wrapper.text()).not.toContain('本月业绩与提成')
  expect(wrapper.text()).not.toContain('近期趋势')
  expect(wrapper.text()).not.toContain('98,765')
 })
 it('marks a gram order locked only after the backend confirms creation',async()=>{
  state.route.params.section='order'
  await start()
  const barcode=wrapper.get('input[placeholder="扫码或输入条码"]')
  await barcode.setValue('G001');await barcode.trigger('keyup.enter');await flushPromises()
  expect(wrapper.text()).toContain('2.000 克 × 800.00')
  expect(wrapper.find('.price-lock-badge').exists()).toBe(false)
  expect(wrapper.text()).toContain('未选择导购，本单不计销售提成')
  await wrapper.get('.checkout-bar .primary').trigger('click');await flushPromises()
  expect(state.createOrder).toHaveBeenCalledWith(expect.objectContaining({handover:true,payMethod:'PENDING',salesId:null}))
  expect(wrapper.get('.price-lock-badge').text()).toBe('金价已锁')
 })
})
