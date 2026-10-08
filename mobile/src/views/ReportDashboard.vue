<template>
  <div class="shell report-shell">
    <DesignHeader title="经营报表" :subtitle="auth.user?.store_name || auth.user?.storeName || '默认门店'" :back="true" @back="router.back()"><span>{{ app.offline ? '离线' : '在线' }}</span></DesignHeader>
    <main class="content"><section class="page report-page">
      <div class="report-tabs"><Chip v-for="tab in tabs" :key="tab.key" :selected="activeTab===tab.key" @click="go(tab.key)">{{ tab.label }}</Chip></div>
      <div v-if="tabs.length" class="report-filters">
        <label v-if="monthBased" class="month-field">报表月份<input v-model="reportMonth" type="month" aria-label="报表月份" @change="load" /></label>
        <div v-else class="filter-tabs"><Chip v-for="item in periods" :key="item.key" :selected="timeType===item.key" @click="changePeriod(item.key)">{{ item.label }}</Chip></div>
        <div v-if="!monthBased && timeType==='custom'" class="custom-range"><label><span>开始日期</span><input v-model="startDate" type="date" aria-label="开始日期" @change="load"/></label><span class="range-separator">至</span><label><span>结束日期</span><input v-model="endDate" type="date" aria-label="结束日期" @change="load"/></label></div>
        <button v-if="isManager && ['overview','sales','employee'].includes(activeTab)" class="picker-field" type="button" @click="openPicker('employee')"><span>{{ employeeLabel }}</span><b>▾</b></button>
        <button v-if="activeTab==='sales'" class="picker-field" type="button" @click="openPicker('category')"><span>{{ category || '全部品类' }}</span><b>▾</b></button>
      </div>
      <p v-if="error" class="report-error">{{ error }} <button class="outline" @click="load">重试</button></p>
      <div v-if="picker.open" class="picker-mask" @click.self="picker.open=false">
        <div class="picker-sheet" role="dialog" :aria-label="picker.title">
          <header><b>{{ picker.title }}</b><button type="button" @click="picker.open=false">关闭</button></header>
          <button v-for="opt in picker.options" :key="String(opt.value)" type="button" :class="{ active: String(picker.current) === String(opt.value) }" @click="choosePicker(opt.value)">
            <span>{{ opt.label }}</span><i v-if="String(picker.current) === String(opt.value)">✓</i>
          </button>
        </div>
      </div>
      <div v-if="loading" class="report-loading">正在加载报表...</div>

      <EmptyState v-if="!tabs.length" title="当前账号未开启报表权限" />
      <template v-else-if="activeTab==='overview'">
        <div v-if="isManager && auth.can('system:manage')" class="target-row"><label>本月销售目标（元）<input v-model.number="targetInput" type="number" min="0" step="100" :placeholder="targetValue>0?String(targetValue):'未设置'"/></label><button class="outline" @click="saveTarget">保存目标</button><small v-if="targetValue>0">当前目标 {{ money(targetValue) }}</small></div>
        <div class="kpi-grid report-kpis"><Kpi :label="!isManager || auth.can('report:processing') ? '实收营业额(含加工)' : '销售实收营业额'" :value="money(summary.turnover ?? summary.actual_paid ?? summary.sales_amount)"/><Kpi label="毛利" :value="money(summary.gross_profit)"/><Kpi label="销售件数" :value="`${num(summary.item_count)} 件`"/><Kpi label="销售克重" :value="`${weight(summary.gold_weight)}g`"/></div>
        <p v-if="Number(summary.processing_amount || 0) > 0" class="report-scope-note">营业额含加工收款 {{ money(summary.processing_amount) }}；件数、克重只统计销售单。</p>
        <Panel title="营业额趋势"><div v-if="trend.length" class="report-bars"><div v-for="row in trend" :key="row.day" class="report-bar-col"><span>{{ moneyShort(row.amount) }}</span><i :style="{height: `${barHeight(row.amount, trend)}%`}"></i><small>{{ day(row.day) }}</small></div></div><EmptyState v-else title="暂无营业额趋势" /></Panel>
        <Panel title="品类销售"><div v-if="categoryRows.length" class="report-category-list"><div v-for="row in categoryRows" :key="row.category" class="report-list-row"><span>{{ row.category }}</span><strong>{{ money(row.amount) }}</strong><small>{{ num(row.item_count) }} 件 · {{ weight(row.weight) }}g</small></div></div><EmptyState v-else title="暂无品类数据" /></Panel>
        <Panel v-if="isManager" title="员工销售排行"><div v-if="employeeRows.length" class="report-category-list"><button v-for="(row,index) in employeeRows" :key="row.user_id || index" class="report-list-row report-row-button" @click="openEmployeeDetail(row)"><span><b class="rank-no">{{ index+1 }}</b> {{ row.name || row.employee_name || '员工' }}<small class="report-row-sub">{{ num(row.order_count) }} 单 <template v-if="auth.can('report:commission')">· 提成 {{ money(row.commission) }}</template></small></span><strong>{{ money(row.actual_paid ?? row.amount ?? row.sales_amount) }}</strong><span class="report-row-action">查看 ›</span></button></div><EmptyState v-else title="暂无员工销售数据" /></Panel>
        <Panel title="销售明细"><div v-if="records.length" class="report-category-list"><button v-for="row in records" :key="row.order_id + '-' + row.goods_name" class="report-list-row report-row-button" @click="openSalesDetail(row)"><span>{{ row.goods_name || '商品' }}<small class="report-row-sub">{{ row.order_no || row.order_id || '-' }} · {{ row.date || '-' }}</small></span><strong>{{ money(actualPaid(row)) }}</strong><span class="report-row-action">查看 ›</span></button></div><EmptyState v-else title="暂无销售明细" /></Panel>
      </template>

      <template v-else-if="activeTab==='sales'">
        <div class="kpi-grid report-kpis"><Kpi label="销售实收" :value="money(salesSummary.actual_paid ?? salesSummary.sales_amount)"/><Kpi label="销售克重" :value="`${weight(salesSummary.gold_weight)}g`"/><Kpi label="销售件数" :value="`${num(salesSummary.item_count)} 件`"/><Kpi label="客单价" :value="money(salesSummary.avg_order)"/></div>
        <Panel title="品类占比"><div class="report-category-list"><div v-for="row in categoryRows" :key="row.category" class="report-list-row"><span>{{ row.category }}</span><strong>{{ percent(row.amount, salesSummary.sales_amount) }}</strong><small>{{ money(row.amount) }} · {{ num(row.item_count) }} 件</small></div><EmptyState v-if="!categoryRows.length" title="暂无品类数据" /></div></Panel>
        <Panel title="销售明细"><button v-for="row in salesRecords" :key="row.order_id + '-' + row.goods_name" class="report-list-row report-row-button" @click="openSalesDetail(row)"><span>{{ row.goods_name || '商品' }}<small class="report-row-sub">{{ row.order_no || row.order_id || '-' }} · {{ row.date || '-' }}</small></span><strong>{{ money(actualPaid(row)) }}</strong><span class="report-row-action">查看 ›</span></button><EmptyState v-if="!salesRecords.length" title="暂无销售明细" /></Panel>
      </template>

      <template v-else-if="activeTab==='employee'">
        <div class="kpi-grid report-kpis"><Kpi label="员工人数" :value="`${employeeRows.length} 人`"/><Kpi label="人均实收" :value="money(employeeAverage)"/><Kpi label="实收冠军" :value="employeeRows[0]?.name || '-'"/><Kpi label="冠军实收" :value="money(employeeRows[0]?.actual_paid ?? employeeRows[0]?.sales_amount)"/></div>
        <Panel title="员工排行"><div class="report-bars employee-bars"><div v-for="(row,index) in employeeRows" :key="row.user_id || index" class="report-bar-col"><span>{{ moneyShort(row.actual_paid ?? row.sales_amount) }}</span><i :class="`rank-${index+1}`" :style="{height: `${barHeight(row.actual_paid ?? row.sales_amount, employeeRows, 'actual_paid')}%`}"></i><small>{{ row.name }}</small></div></div><EmptyState v-if="!employeeRows.length" title="暂无员工数据" /></Panel>
        <Panel title="员工明细"><button v-for="row in employeeRows" :key="row.user_id" class="report-list-row report-row-button" @click="openEmployeeDetail(row)"><span>{{ row.name || row.employee_name || '员工' }}<small class="report-row-sub">{{ num(row.order_count) }} 单 · {{ weight(row.weight) }}g</small></span><strong>{{ money(row.actual_paid ?? row.sales_amount ?? row.amount) }}</strong><span class="report-row-action">查看 ›</span></button><EmptyState v-if="!employeeRows.length" title="暂无员工数据" /></Panel>
      </template>

      <template v-else-if="activeTab==='commission'">
        <Panel title="提成统计"><div v-for="row in commissions" :key="row.user_id" class="report-list-row"><span>{{ row.employee_name || row.real_name || '员工' }}<small>{{ row.month }}</small></span><strong>{{ money(row.commission_amount) }}</strong></div><EmptyState v-if="!commissions.length" title="暂无提成数据" /></Panel>
      </template>
      <template v-else-if="activeTab==='monthly'">
        <Panel :title="reportMonth + ' 营业月报'"><div v-for="row in monthly" :key="row.day" class="report-list-row"><span>{{ day(row.day) }}<small>{{ num(row.order_count) }} 单</small></span><strong>{{ money(row.turnover ?? row.amount) }}</strong></div><EmptyState v-if="!monthly.length" title="暂无月报数据" /></Panel>
      </template>
      <template v-else-if="activeTab==='processing'">
        <div class="kpi-grid report-kpis"><Kpi label="加工单数" :value="num(processingStats.order_count)"/><Kpi label="实际收款" :value="money(processingStats.paid_amount)"/><Kpi label="工费应收" :value="money(processingStats.due_amount)"/><Kpi label="待收尾款" :value="money(processingStats.outstanding)"/></div>
        <Panel title="加工项目排行"><div v-for="row in processingStats.item_ranking || []" :key="row.item_name" class="report-list-row"><span>{{ row.item_name }}<small>{{ num(row.order_count) }} 单</small></span><strong>{{ money(row.paid_amount) }}</strong></div><EmptyState v-if="!processingStats.item_ranking?.length" title="暂无加工统计" /></Panel>
      </template>
      <template v-else-if="activeTab==='recycle'"><div class="kpi-grid report-kpis"><Kpi label="回收总额" :value="money(recycle.amount)"/><Kpi label="回收克重" :value="`${weight(recycle.weight)}g`"/><Kpi label="回收单数" :value="`${num(recycle.order_count)} 单`"/><Kpi label="折旧损耗" :value="money(recycle.deduct_loss)"/></div><div class="kpi-grid report-kpis"><Kpi label="以旧换新" :value="`${num(recycle.trade_in_count)} 单`"/><Kpi label="换新差价" :value="money(recycle.trade_in_amount)"/></div><Panel title="回收趋势"><div v-if="recycle.trend?.length" class="report-bars"><div v-for="row in recycle.trend" :key="row.day" class="report-bar-col"><span>{{ weight(row.weight) }}g</span><i :style="{height: `${barHeight(row.weight,recycle.trend,'weight')}%`}"></i><small>{{ day(row.day) }}</small></div></div><EmptyState v-else title="暂无回收趋势" /></Panel><Panel title="回收明细"><div v-for="row in recycle.records || []" :key="row.recycle_order_id" class="report-list-row"><span>{{ row.material_type || '旧料' }}<small>{{ row.bill_no }} · {{ row.create_time }}</small></span><strong>{{ money(row.total_amount) }}</strong><small>{{ weight(row.weight) }}g · {{ percent(row.purity,1) }}</small></div><EmptyState v-if="!recycle.records?.length" title="暂无回收数据" /></Panel><Panel title="以旧换新明细（报价记录）"><div v-for="row in recycle.trade_in_records || []" :key="row.trade_in_id" class="report-list-row"><span>换新 {{ row.bill_no }}<small>{{ row.create_time }} · {{ tradeInStatus(row.status) }}</small></span><strong>{{ money(row.diff_amount) }}</strong><small>{{ oldNewText(row) }}</small></div><EmptyState v-if="!recycle.trade_in_records?.length" title="暂无换新记录" /></Panel></template>
      <template v-else-if="activeTab==='member'"><div class="kpi-grid report-kpis"><Kpi label="新增会员" :value="`${num(member.new_members)} 人`"/><Kpi label="会员总数" :value="`${num(member.total_members)} 人`"/><Kpi label="会员消费额" :value="money(member.consume_amount)"/><Kpi label="消费占比" :value="percent(member.consume_amount, summary.sales_amount)"/></div><Panel title="会员明细"><div v-for="row in member.records || []" :key="row.member_id" class="report-list-row"><span>{{ row.name }}<small>{{ row.phone || '-' }} · {{ row.gender || '未填写' }}</small></span><strong>{{ money(row.total_consume) }}</strong><small>{{ row.create_time?.slice(0,10) }}</small></div><EmptyState v-if="!member.records?.length" title="暂无会员数据" /></Panel></template>
      <template v-else><div class="kpi-grid report-kpis"><Kpi label="周期均价" :value="money(goldAverage)"/><Kpi label="最高价" :value="money(goldMax)"/><Kpi label="最低价" :value="money(goldMin)"/><Kpi label="波动幅度" :value="percent(goldMax-goldMin,goldAverage)"/></div><Panel title="金价走势"><GoldTrend :points="gold.prices || []"/></Panel><Panel title="销售克重"><div v-for="row in gold.salesWeight || []" :key="row.day" class="report-list-row"><span>{{ day(row.day) }}</span><strong>{{ weight(row.weight) }}g</strong></div><EmptyState v-if="!gold.salesWeight?.length" title="暂无销售克重数据" /></Panel><Panel title="金价区间销售分布"><div v-for="row in gold.distribution || []" :key="row.price_bucket" class="report-list-row"><span>{{ priceBucket(row.price_bucket) }}</span><strong>{{ weight(row.weight) }}g</strong><small>该金价区间销售克重</small></div><EmptyState v-if="!gold.distribution?.length" title="暂无金价区间销售数据" /></Panel></template>
    </section></main>
    <div v-if="detailDrawer.open" class="detail-drawer-mask" @click.self="closeDetailDrawer">
      <section class="detail-drawer" role="dialog" aria-modal="true" :aria-label="detailDrawer.type === 'employee' ? '员工销售明细' : '销售明细详情'">
        <header class="detail-drawer-head"><strong>{{ detailDrawer.type === 'employee' ? '员工销售明细' : '销售明细详情' }}</strong><button class="modal-close" aria-label="关闭详情" @click="closeDetailDrawer">×</button></header>
        <div class="detail-drawer-body">
          <EmptyState v-if="drawerLoading" title="加载明细中..." />
          <template v-else-if="detailDrawer.type === 'employee' && detailDrawer.employee">
            <div class="detail-drawer-title"><b>{{ detailDrawer.employee.name || detailDrawer.employee.employee_name || '员工' }}</b><small>所选周期</small></div>
            <div class="kpi-grid report-kpis"><Kpi label="实收业绩" :value="money(detailDrawer.employee.actual_paid ?? detailDrawer.employee.amount ?? detailDrawer.employee.sales_amount)"/><Kpi label="订单数" :value="`${num(detailDrawer.employee.order_count)} 单`"/><Kpi label="销售克重" :value="`${weight(detailDrawer.employee.weight)}g`"/><Kpi v-if="!isManager || auth.can('report:commission')" label="提成" :value="money(detailDrawer.employee.commission)"/></div>
            <div class="detail-drawer-facts"><div v-if="!isManager || auth.can('report:recycle')"><span>回收业绩</span><b>{{ money(detailDrawer.employee.recycle_amount) }}</b></div><div><span>开发会员</span><b>{{ num(detailDrawer.employee.member_count) }} 人</b></div></div>
            <h3 class="detail-drawer-section">品类明细</h3><div v-for="row in detailDrawer.employee.categories || []" :key="row.category" class="report-list-row"><span>{{ row.category || '未分类' }}</span><strong>{{ money(row.amount) }}</strong><small>{{ num(row.item_count) }} 件 · {{ weight(row.weight) }}g</small></div><EmptyState v-if="!detailDrawer.employee.categories?.length" title="暂无品类明细" />
          </template>
          <template v-else-if="detailDrawer.sale">
            <div class="detail-drawer-title"><b>{{ detailDrawer.sale.goods_name || '商品' }}</b><small>{{ detailDrawer.sale.order_no || detailDrawer.sale.order_id || '无订单号' }}</small></div>
            <div class="detail-drawer-facts"><div><span>原应收</span><b>{{ money(detailDrawer.sale.original_amount) }}</b></div><div><span>优惠</span><b>{{ money(detailDrawer.sale.settlement_discount) }}</b></div><div><span>优惠后应收</span><b>{{ money(detailDrawer.sale.discounted_amount) }}</b></div><div><span>实收金额</span><b>{{ money(actualPaid(detailDrawer.sale)) }}</b></div><div><span>待收</span><b>{{ money(detailDrawer.sale.remaining_amount) }}</b></div><div><span>收款方式</span><b>{{ paymentLabel(detailDrawer.sale.pay_method) }}</b></div><div><span>优惠原因</span><b>{{ detailDrawer.sale.settlement_discount_reason || '-' }}</b></div><div><span>销售数量</span><b>{{ num(detailDrawer.sale.quantity || 1) }} 件</b></div><div><span>销售克重</span><b>{{ weight(detailDrawer.sale.weight) }}g</b></div><div><span>品类</span><b>{{ detailDrawer.sale.category || '未分类' }}</b></div><div><span>成交日期</span><b>{{ detailDrawer.sale.date || '-' }}</b></div><div><span>销售员工</span><b>{{ detailDrawer.sale.employee_name || detailDrawer.sale.employeeName || '-' }}</b></div></div>
          </template>
        </div>
      </section>
    </div>
    <BottomNav />
  </div>
</template>

<script setup>
import { useToast } from '../composables/useToast.js'
const { toast } = useToast()

import BottomNav from '../components/BottomNav.vue'

import EmptyState from '../components/EmptyState.vue'

import Chip from '../components/Chip.vue'

import DesignHeader from '../components/DesignHeader.vue'

import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { reportDate, reportRange } from '../utils/reportRange.js'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth.js'
import { useAppStore } from '../stores/app.js'
import { api } from '../api/request.js'
import Kpi from '../components/Kpi.vue'
import Panel from '../components/Panel.vue'
import GoldTrend from '../components/GoldTrend.vue'
const route=useRoute(); const router=useRouter(); const auth=useAuthStore(); const app=useAppStore(); const isManager=computed(()=>['ADMIN','MANAGER'].includes(auth.role));
const tabs=computed(()=>isManager.value?[
  {key:'overview',label:'概览',permission:'report:store-performance'},
  {key:'sales',label:'销售明细',permission:'report:store-performance'},
  {key:'employee',label:'员工排行',permission:'report:store-performance'},
  {key:'commission',label:'提成统计',permission:'report:commission'},
  {key:'monthly',label:'月报',permission:'report:monthly'},
  {key:'processing',label:'加工统计',permission:'report:processing'},
  {key:'recycle',label:'回收换新',permission:'report:recycle'},
  {key:'member',label:'会员分析',permission:'report:store-performance'},
  {key:'gold',label:'金价关联',permission:'report:store-performance'}
].filter(tab=>auth.can(tab.permission)):(auth.can('report:view')?[{key:'overview',label:'个人业绩'},{key:'sales',label:'个人销售明细'}]:[])); const periods=[{key:'today',label:'今天'},{key:'yesterday',label:'昨天'},{key:'week',label:'本周'},{key:'month',label:'本月'},{key:'lastMonth',label:'上月'},{key:'custom',label:'自定义'}];
const activeTab=computed(()=>{const kind=String(route.params.kind||'');return tabs.value.some(tab=>tab.key===kind)?kind:(tabs.value[0]?.key||'')}); const timeType=ref('month'); const reportMonth=ref(reportDate().slice(0,7)); const monthBased=computed(()=>['commission','monthly'].includes(activeTab.value));
const permissionScope=computed(()=>auth.role + ':' + JSON.stringify(auth.user?.permissions)); let loadVersion=0; let drawerVersion=0; const startDate=ref(''); const endDate=ref(''); const employeeId=ref(''); const category=ref(''); const loading=ref(false); const error=ref(''); const overview=ref({summary:{},trend:[],categories:[],employees:[],records:[]}); const sales=ref({summary:{},records:[]}); const employee=ref({employees:[]}); const recycle=ref({}); const member=ref({}); const gold=ref({prices:[],salesWeight:[]}); const commissions=ref([]); const monthly=ref([]); const processingStats=ref({}); const targetValue=ref(0); const targetInput=ref(0); const drawerLoading=ref(false); const detailDrawer=ref({open:false,type:'',employee:null,sale:null})
const summary=computed(()=>overview.value.summary||{}); const trend=computed(()=>overview.value.trend||[]); const categoryRows=computed(()=>overview.value.categories||[]); const records=computed(()=>overview.value.records||[]); const employees=computed(()=>overview.value.employees||[]); const employeeRows=computed(()=>employee.value.employees?.length?employee.value.employees:employees.value); const salesSummary=computed(()=>sales.value.summary?.summary||sales.value.summary||summary.value); const salesRecords=computed(()=>sales.value.records||[]); const categories=computed(()=>categoryRows.value); const employeeAverage=computed(()=>employeeRows.value.length?employeeRows.value.reduce((s,r)=>s+Number(r.actual_paid ?? r.sales_amount ?? r.amount ?? 0),0)/employeeRows.value.length:0); const goldValues=computed(()=> (gold.value.prices||[]).map(x=>Number(x.price||0)).filter(Boolean)); const goldAverage=computed(()=>Number(gold.value.average ?? (goldValues.value.length?goldValues.value.reduce((s,x)=>s+x,0)/goldValues.value.length:0))); const goldMax=computed(()=>Number(gold.value.max ?? Math.max(0,...goldValues.value))); const goldMin=computed(()=>Number(gold.value.min ?? (goldValues.value.length?Math.min(...goldValues.value):0)))
const num=v=>Number(v||0).toLocaleString('zh-CN',{maximumFractionDigits:0}); const tradeInStatus=v=>({1:'已成交',2:'待审批',4:'已驳回'}[Number(v)]||'-')
// 员工/品类改成底部选择面板：手机上原生 select 会弹出浮层压住页面数据，看不清也点不准
const picker=ref({open:false,kind:'',title:'',current:'',options:[]})
const employeeLabel=computed(()=>{const hit=employees.value.find(e=>String(e.user_id)===String(employeeId.value));return hit?(hit.name||hit.employee_name||'员工'):'全部员工'})
function openPicker(kind){
  if(kind==='employee') picker.value={open:true,kind,title:'选择员工',current:employeeId.value,options:[{value:'',label:'全部员工'},...employees.value.map(e=>({value:e.user_id,label:e.name||e.employee_name||'员工'}))]}
  else picker.value={open:true,kind,title:'选择品类',current:category.value,options:[{value:'',label:'全部品类'},...categories.value.map(c=>({value:c.category,label:c.category}))]}
}
function choosePicker(value){ if(picker.value.kind==='employee') employeeId.value=value; else category.value=value; picker.value.open=false; load() }
const oldNewText=row=>{const text=v=>{try{const o=typeof v==='string'?JSON.parse(v):v;if(o&&typeof o==='object')return Object.values(o).filter(Boolean).join(' / ')||'-';return String(v||'-')}catch{return String(v||'-')}};return `旧:${text(row.old_material_info)} → 新:${text(row.new_goods_info)}`}
const money=v=>`¥${Number(v||0).toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2})}`; const actualPaid=row=>Number(row?.actual_paid ?? row?.amount ?? row?.pay_amount ?? 0); const paymentLabel=v=>({CASH:'现金',WECHAT:'微信',ALIPAY:'支付宝',BALANCE:'储值',DOUTUAN:'抖音团购',MEITUAN:'美团团购'}[String(v||'').toUpperCase()]||v||'-'); const moneyShort=v=>{const n=Number(v||0);return n>=10000?`¥${(n/10000).toFixed(1)}万`:`¥${Math.round(n)}`}; const weight=v=>Number(v||0).toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:3}); const percent=(a,b)=>`${b?((Number(a||0)/Number(b||1))*100).toFixed(1):'0.0'}%`; const day=v=>String(v||'').slice(5,10)||'-'; const barHeight=(value,rows,key='amount')=>{const max=Math.max(...rows.map(r=>Number(r[key] != null ? r[key] : (r.amount || 0))),1);return Math.max(6,Number(value||0)/max*100)}
const priceBucket=v=>`¥${Number(v||0).toLocaleString('zh-CN',{maximumFractionDigits:0})} - ¥${(Number(v||0)+10).toLocaleString('zh-CN',{maximumFractionDigits:0})}`
function go(key){router.push(key==='overview'?'/report':`/report/${key}`)} function setDefaultCustomRange(){const now=new Date();const pad=value=>String(value).padStart(2,'0');if(!startDate.value)startDate.value=`${now.getFullYear()}-${pad(now.getMonth()+1)}-01`;if(!endDate.value)endDate.value=`${now.getFullYear()}-${pad(now.getMonth()+1)}-${pad(now.getDate())}`} function changePeriod(v){timeType.value=v;if(v==='custom')setDefaultCustomRange();load()} function closeDetailDrawer(){drawerVersion++;detailDrawer.value={open:false,type:'',employee:null,sale:null};drawerLoading.value=false}
async function openEmployeeDetail(row) {
  const version=++drawerVersion
  const scope=permissionScope.value
  detailDrawer.value={open:true,type:'employee',employee:row,sale:null}
  if(!row.categories&&row.user_id) {
    drawerLoading.value=true
    try {
      const params={timeType:timeType.value,employeeId:row.user_id}
      if(timeType.value==='custom')Object.assign(params,{startDate:startDate.value,endDate:endDate.value})
      const d=await api.reportEmployee(params)
      if(version!==drawerVersion||scope!==permissionScope.value)return
      const detail=(d?.employees||[])[0]
      if(detail)detailDrawer.value={...detailDrawer.value,employee:{...row,...detail}}
    } catch(e) { if(version===drawerVersion)toast(e?.message||'员工明细加载失败') }
    finally { if(version===drawerVersion)drawerLoading.value=false }
  }
} function openSalesDetail(row){detailDrawer.value={open:true,type:'sale',employee:null,sale:row}}
function clearReports() {
  overview.value={};sales.value={};employee.value={};recycle.value={};member.value={};gold.value={}
  commissions.value=[];monthly.value=[];processingStats.value={}
  closeDetailDrawer()
}
async function load() {
  const version=++loadVersion, tab=activeTab.value, scope=permissionScope.value
  clearReports();error.value=''
  if(!tab){loading.value=false;return}
  loading.value=true
  const current=()=>version===loadVersion && scope===permissionScope.value && tab===activeTab.value
  try {
    const params={timeType:timeType.value}
    if(timeType.value==='custom')Object.assign(params,{startDate:startDate.value,endDate:endDate.value})
    if(employeeId.value)params.employeeId=employeeId.value
    if(category.value)params.category=category.value
    const range=reportRange(timeType.value,startDate.value,endDate.value)
    if(range.from>range.to)throw new Error('开始日期应早于或等于结束日期')
    if(tab==='sales') {
      const [salesData,overviewData]=await Promise.all([api.reportSales(params),api.reportOverview(params)])
      if(current()){sales.value=salesData||{};overview.value=overviewData||{}}
    } else {
      const fetchers={
        overview:()=>api.reportOverview(params),employee:()=>api.reportEmployee(params),recycle:()=>api.reportRecycle(params),
        commission:()=>api.commission({month:reportMonth.value}),monthly:()=>api.reportMonthly({month:reportMonth.value}),
        processing:()=>api.processingStatistics(range),member:()=>api.reportMember(params),gold:()=>api.reportGold(params)
      }
      const result=await fetchers[tab]()
      const destinations={overview,employee,recycle,commissions,monthly,processingStats,member,gold}
      const key={commission:'commissions',processing:'processingStats'}[tab]||tab
      if(current())destinations[key].value=result||(['commissions','monthly'].includes(key)?[]:{})
    }
  } catch(e) {if(current())error.value=e?.message||'报表加载失败'}
  finally {if(version===loadVersion)loading.value=false}
}
async function loadTarget(){if(!isManager.value || !auth.can('report:store-performance') || !auth.can('system:manage'))return;const scope=permissionScope.value;try{const t=await api.systemTarget();if(scope!==permissionScope.value)return;targetValue.value=Number(t?.monthlySalesTarget||0);targetInput.value=targetValue.value}catch{}}
async function saveTarget(){if(!isManager.value || !auth.can('report:store-performance') || !auth.can('system:manage'))return;const v=Number(targetInput.value||0);if(v<0)return toast('目标金额不能为负');try{await api.setTarget(v);targetValue.value=v;toast('本月销售目标已保存')}catch(e){toast(e?.message||'目标保存失败')}}
onMounted(()=>{load();loadTarget()})
watch([activeTab,permissionScope],()=>{employeeId.value='';targetValue.value=0;targetInput.value=0;load();loadTarget()})
onBeforeUnmount(()=>{loadVersion++;drawerVersion++})
watch(()=>app.eventVersion,()=>{if(['REPORT_UPDATED','COMMISSION_UPDATED','TARGET_UPDATED','ORDER_COMPLETED','RECYCLE_COMPLETED','TRADE_IN_UPDATED','PROCESSING_ORDER_UPDATED'].includes(app.lastEventType)){load();if(app.lastEventType==='TARGET_UPDATED')loadTarget()}})
</script>
