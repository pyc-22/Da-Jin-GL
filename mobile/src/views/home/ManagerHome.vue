<template>
  <section class="page home-page">
    <MarketQuoteBar :gold="app.primaryGold" :recycle="app.primaryGold" :silver="app.silverSale" :silver-recycle="app.silverRecycle" :countdown="marketCountdown" :show-meta="true" />
    <div class="kpi-grid home-kpis">
      <Kpi label="今日营业额（实收）" :value="'¥' + money(dashboard?.amount)" @click="openKpiDetail('orders')" />
      <Kpi label="今日订单数" :value="dashboard?.order_count ?? 0" @click="openKpiDetail('orders')" />
      <Kpi label="销售克重" :value="(dashboard?.weight ?? 0) + 'g'" @click="openKpiDetail('weight')" />
      <Kpi label="回收克重" :value="(dashboard?.recycle_weight ?? 0) + 'g'" @click="openKpiDetail('recycle')" />
      <Kpi label="客单价" :value="'¥' + money(dashboard?.avg_order)" @click="openKpiDetail('avg')" />
      <Kpi label="库存预警" :value="warnings.length" @click="section='goods'" />
    </div>
    <button v-if="app.pendingInboundCount && auth.can('stock:inbound:create')" class="pending-inbound-entry" @click="router.push('/inbound/history')"><span>{{ app.pendingInboundCount }} 条待同步入库单</span><em>查看 ›</em></button>
    <Panel v-if="auth.can('stock:check:approve')" title="待办审批">
      <template #actions><button class="text-action" @click="section='approval'">查看全部 {{ approvals.length }} ›</button></template>
      <button v-for="item in approvals.slice(0, 3)" :key="item.approval_id || item.id" class="home-task" @click="openApprovalDetail(item)"><span class="entry-symbol">批</span><span><b>{{ typeName(item.type) }}</b><small>{{ formatApprovalReason(item) }}</small></span><StatusPill tone="err">待审批</StatusPill></button>
      <EmptyState v-if="!approvals.length" title="暂无待办审批" />
    </Panel>
    <Panel v-if="managerTrendRows.length" title="7日营业额趋势">
      <template #actions><button class="text-action" @click="section='trend'">每日明细 ›</button></template>
      <TrendBars :values="managerTrendValues" :labels="managerTrendWeekdayLabels" />
    </Panel>
    <Panel title="门店工作台"><div class="function-grid home-functions"><button v-for="item in managerFunctions" :key="item.key" v-permission="item.permission" class="function-entry" @click="openFunction(item)"><b>{{ FUNCTION_ICONS[item.key] || '价' }}</b><span>{{ item.label }}</span></button></div></Panel>
    <Panel title="员工排行榜"><div v-for="(row,i) in ranking" :key="i" class="rank-row"><span class="rank-no">{{ i+1 }}</span><span>{{ row.real_name || row.name || '员工' }}</span><strong>¥{{ money(row.amount || row.sales_amount) }}</strong><small>提成 ¥{{ money(row.commission) }}</small></div><EmptyState v-if="!ranking.length" title="暂无员工业绩" /></Panel>
  </section>
</template>
<script setup>
import { inject } from 'vue'
import { roleHomeKey } from './context.js'
import Panel from '../../components/Panel.vue'
import Kpi from '../../components/Kpi.vue'
import MarketQuoteBar from '../../components/MarketQuoteBar.vue'
import TrendBars from '../../components/TrendBars.vue'
import StatusPill from '../../components/StatusPill.vue'
import EmptyState from '../../components/EmptyState.vue'
const { app, auth, router, section, dashboard, warnings, ranking, approvals, money, openKpiDetail, managerFunctions, FUNCTION_ICONS, openFunction, typeName, formatApprovalReason, openApprovalDetail, managerTrendRows, managerTrendValues, managerTrendWeekdayLabels, marketCountdown } = inject(roleHomeKey)
</script>
