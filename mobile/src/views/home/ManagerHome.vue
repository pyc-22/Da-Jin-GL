<template>
  <section class="page home-page">
    <MarketQuoteBar :gold="app.primaryGold" :recycle="app.primaryGold" :silver="app.silverSale" :silver-recycle="app.silverRecycle" :countdown="marketCountdown" :show-meta="true" />
    <p v-if="dashboardError" class="error dashboard-error" role="alert">{{ dashboardError }} <button class="outline" @click="loadManager">重试</button></p>
    <div v-else class="kpi-grid home-kpis">
      <Kpi v-if="auth.can('report:store-performance')" label="今日营业额（实收）" :value="'¥' + money(dashboard?.amount)" @click="openKpiDetail('orders')" />
      <Kpi v-if="auth.can('report:store-performance')" label="今日订单数" :value="dashboard?.order_count ?? 0" @click="openKpiDetail('orders')" />
      <Kpi v-if="auth.can('report:store-performance')" label="销售克重（含加工补金）" :value="(dashboard?.weight ?? 0) + 'g'" @click="openKpiDetail('weight')" />
      <Kpi v-if="auth.can('report:recycle')" label="回收克重（含加工剩余金）" :value="(recycleData?.weight ?? 0) + 'g'" @click="openKpiDetail('recycle')" />
      <Kpi v-if="auth.can('report:store-performance')" label="客单价" :value="'¥' + money(dashboard?.avg_order)" @click="openKpiDetail('avg')" />
      <Kpi v-if="auth.can('stock:view')" label="库存预警" :value="warnings.length" @click="navigateSection('goods')" />
    </div>
    <button v-if="app.pendingInboundCount && auth.can('stock:inbound:create')" class="pending-inbound-entry" @click="router.push('/inbound/history')"><span>{{ app.pendingInboundCount }} 条待同步入库单</span><em>查看 ›</em></button>
    <Panel v-if="auth.can('approval:view')" title="待办审批">
      <template #actions><button class="text-action" @click="navigateSection('approval')">查看全部 {{ approvals.length }} ›</button></template>
      <button v-for="item in approvals.slice(0, 3)" :key="item.approval_id || item.id" class="home-task" @click="openApprovalDetail(item)"><span class="entry-symbol">批</span><span><b>{{ typeName(item.type) }}</b><small>{{ formatApprovalReason(item) }}</small></span><StatusPill tone="err">待审批</StatusPill></button>
      <EmptyState v-if="!approvals.length" title="暂无待办审批" />
    </Panel>
    <Panel v-if="auth.can('report:store-performance') && managerTrendRows.length" title="7日营业额趋势">
      <template #actions><button class="text-action" @click="navigateSection('trend')">每日明细 ›</button></template>
      <TrendBars :values="managerTrendValues" :labels="managerTrendWeekdayLabels" />
    </Panel>
    <Panel title="门店工作台"><div class="function-grid home-functions"><button v-for="item in managerFunctions" :key="item.key" v-permission="item.permission" class="function-entry" @click="openFunction(item)"><b>{{ FUNCTION_ICONS[item.key] || '价' }}</b><span>{{ item.label }}</span></button></div></Panel>
    <Panel v-if="auth.can('report:store-performance')" :title="`${rankingRangeLabel}员工排行榜`"><template #actions><button class="text-action" @click="router.push('/report')">查看全部 ›</button></template><div v-if="rankingLoading" class="muted">排行榜加载中…</div><p v-else-if="rankingError" class="error" role="alert">{{ rankingError }} <button class="outline" @click="loadManager">重试</button></p><template v-else><div v-for="(row,i) in ranking.slice(0,5)" :key="i" class="rank-row"><span class="rank-no">{{ i+1 }}</span><span>{{ row.real_name || row.name || row.employee_name || '员工' }}</span><strong>¥{{ money(row.amount || row.sales_amount) }}</strong><small v-if="auth.can('report:commission')">提成 ¥{{ money(row.commission) }}</small></div><EmptyState v-if="!ranking.length" title="暂无员工业绩" /></template></Panel>
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
const { app, auth, router, navigateSection, dashboard, dashboardError, recycleData, warnings, ranking, rankingLoading, rankingError, rankingRangeLabel, loadManager, approvals, money, openKpiDetail, managerFunctions, FUNCTION_ICONS, openFunction, typeName, formatApprovalReason, openApprovalDetail, managerTrendRows, managerTrendValues, managerTrendWeekdayLabels, marketCountdown } = inject(roleHomeKey)
</script>
