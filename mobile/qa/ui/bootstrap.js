// UI-only fixture entry. The production entry never imports this file.
import { http } from '../../src/api/request.js'
const query=new URLSearchParams(location.search)
const role=query.get('qaRole')||sessionStorage.getItem('ui-qa-role')||'MANAGER'
sessionStorage.setItem('ui-qa-role',role)
const permissions=['dashboard:view','report:view','member:view','member:create','member:follow','order:create','notification:view','processing:view','recycle:view','goods:search','stock:view','stock:inbound:create','stock:check:create','stock:check:view',...(role==='SALES'?[]:['stock:check:approve','goods:manage','shift:confirm','report:view:all'])]
const user={user_id:9001,real_name:role==='SALES'?'导购验收':role==='ADMIN'?'管理员验收':'店长验收',username:'ui-fixture',store_id:9001,store_name:'本地 UI 验收店',role_code:role,permissions}
if(query.has('qaLoggedOut')){localStorage.removeItem('dajin-user');localStorage.removeItem('dajin-token')}else{localStorage.setItem('dajin-user',JSON.stringify(user));localStorage.setItem('dajin-token','local-ui-fixture')}
const gold=[{type_id:1,type_code:'GOLD',price_type:'足金',type_name:'足金 999',salePrice:862,recyclePrice:813,basePrice:835,markup:27,recycleDeduction:22,purityCoefficient:1,baseInstrument:'Au_TD',pricingMode:'AUTO',roundingRule:'NONE',marketStatus:'OPEN',source:'测试行情',quoteTime:'2026-10-02 15:00:00'},{type_id:2,type_code:'SILVER',price_type:'银',type_name:'白银',salePrice:12.5,recyclePrice:10.2,basePrice:11.2,markup:1.3,recycleDeduction:1,purityCoefficient:1,baseInstrument:'Ag_TD',pricingMode:'AUTO',roundingRule:'TENTH',marketStatus:'OPEN',source:'测试行情',quoteTime:'2026-10-02 15:00:00'},{type_id:3,type_code:'18K',price_type:'18K',type_name:'18K 金',salePrice:643,recyclePrice:580,baseInstrument:'Au_TD',basePrice:835,purityCoefficient:.75,markup:0,recycleDeduction:0,pricingMode:'MANUAL',roundingRule:'NONE',marketStatus:'OPEN',source:'测试行情',quoteTime:'2026-10-02 15:00:00'}]
const goods={goods_id:1,barcode:'QA001',name:'足金素圈手镯',goods_name:'足金素圈手镯',gold_type:'足金',price_type:1,weight:8.25,stock:65,available_stock:65,category:'手镯',sale_price:862}
const processing=[{processing_order_id:1,order_no:'QA-JG-001',item_name_snapshot:'手镯改圈',customer_name:'测试客户',customer_phone:'13800000000',status:'PROCESSING',due_amount:260,paid_amount:100,quantity:1,labor_fee:260,create_time:'2026-10-02 12:00:00',pickup_date:'2026-10-04',craftsman_name:'加工师傅'}]
const sales={records:[{order_id:1,order_no:'QA-XS-001',goods_name:'足金素圈手镯',actual_paid:7111.5,remaining_amount:0,date:'2026-10-02 10:00:00',employee_name:'导购验收',weight:8.25}]}
const fixtures={
 '/api/user/me':{user,permissions},'/actuator/health':{status:'UP'},'/api/gold-price/current':gold,'/api/gold-price/types/all':gold,'/api/gold-price/logs':[],
 '/api/gold-price/spot':{price:835,marketStatus:'OPEN',nextRefreshTime:new Date(Date.now()+420000).toISOString()},
 '/api/admin/dashboard':{amount:19280,order_count:8,weight:22.65,recycle_weight:6.2,avg_order:2410,ranking:[{real_name:'导购验收',amount:12680,commission:253.6}],trend:Array.from({length:7},(_,i)=>({day:'2026-09-'+(24+i),amount:[9400,11500,9600,12300,10800,14500,19280][i]}))},
 '/api/admin/stock/warnings':[], '/api/approval/pending':[{approval_id:1,type:'DISCOUNT',amount:80,reason:'订单折扣申请',status:1,create_time:'2026-10-02 12:30:00'}],
 '/api/report/performance':{amount:52680,order_count:27,avg_order:1951.11,commission:1053.6,employees:[],trend:[]}, '/api/system/target':{monthlySalesTarget:80000},
 '/api/member/list':{records:[{member_id:1,name:'测试会员',phone:'13800000000',total_consume:2200}]},'/api/member/1':{member_id:1,name:'测试会员',phone:'13800000000'},'/api/member/1/consume':[],
 '/api/stock/old-material/types':[{type_id:1,name:'足金旧料',status:1},{type_id:2,name:'18K旧料',status:1}],'/api/stock/old-material':[],
 '/api/processing/orders':processing,'/api/processing/orders/1':{...processing[0],payments:[],incoming_photos:[],weigh_photos:[],pickup_photos:[]},'/api/processing/items':[{item_id:1,name:'手镯改圈',labor_fee:260,pricing_unit:'按件',processing_days:2,category_name:'手镯'}],'/api/processing/craftsmen':[],
 '/api/processing/salespeople':[{user_id:9001,real_name:'导购验收'}],'/api/visit/tasks':[{id:1,status:1}],'/api/goods/list':{records:[goods]},
 '/api/notification':[{notification_id:1,title:'加工单更新',content:'测试加工单已进入加工阶段，请查看进度。',create_time:'2026-10-02 06:00:00',read:false},{notification_id:2,title:'库存提醒',content:'请核对门店库存。',create_time:'2026-10-02 04:00:00',read:true}],
 '/api/report/sales':sales,'/api/report/recycle-detail':{records:[{recycle_order_id:1,bill_no:'QA-HS-001',material_type:'足金旧料',total_amount:5034,weight:6.2,purity:.999,create_time:'2026-10-02 09:00:00'}]},
 '/api/report/overview':{summary:{sales_amount:19280,order_count:8},trend:[],categories:[],employees:[],records:sales.records},
 '/api/stock/goods':goods,'/api/stock/check/scope-goods':[goods],'/api/shift/info':{},'/api/stock/inbound/stores':[{store_id:9001,store_name:'验收店'}],'/api/goods/categories':[],
}
http.defaults.adapter=async config=>{
 let data=fixtures[config.url]??[]
 if(config.url==='/api/order/create')data={orderNo:'QA-XS-SUBMITTED',orderId:99}
 if(config.url==='/api/user/login')data={token:'local-ui-fixture',user,permissions}
 return {data:{code:200,data},status:200,statusText:'OK',headers:{},config}
}
// Avoid a real socket in this isolated visual harness.
window.WebSocket=class {readyState=0; close(){this.readyState=3}}
await import('../../src/main.js')
