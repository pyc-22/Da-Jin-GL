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
