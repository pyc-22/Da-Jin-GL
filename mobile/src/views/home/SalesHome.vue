<template>
  <section class="page home-page">
    <Panel v-if="auth.can('report:view')" dark class="performance-card" title="本月业绩与提成">
      <template #actions><small>{{ monthLabel }}</small></template>
      <div class="performance-amounts"><div><small>本月销售</small><b class="money">¥{{ money(performance.amount) }}</b></div><div><small>本月提成</small><b class="money accent">¥{{ money(performance.commission) }}</b></div></div>
      <div class="target-track" role="progressbar" aria-label="本月目标完成度" :aria-valuenow="Math.min(100, performance.progress || 0)" aria-valuemin="0" aria-valuemax="100"><i :style="{width: Math.min(100, performance.progress || 0) + '%'}" /></div>
      <div class="performance-meta"><small>{{ targetValue > 0 ? '已完成 ' + (performance.progress || 0) + '%' : '未设目标' }}</small><small v-if="targetValue > 0">目标 ¥{{ money(targetValue) }}</small></div>
      <div class="performance-meta"><small>成交 {{ performance.orders || 0 }} 单</small><small>客单 ¥{{ money(performance.avg) }}</small></div>
    </Panel>
    <MarketQuoteBar :gold="app.primaryGold" :recycle="app.primaryGold" :show-silver="false" :show-meta="false" source-label="今日金价" />
    <div class="home-order-actions">
      <button v-if="auth.can('order:create')" class="primary full" @click="section='order'">销售开单</button>
      <div><button v-if="auth.can('processing:view')" class="outline" @click="router.push('/processing?create=1')">✎ 加工开单</button><button v-if="auth.can('recycle:view')" class="outline" @click="router.push('/sales/recycle')">↻ 回收开单</button></div>
    </div>
    <Panel title="今日待办">
      <button v-if="auth.can('member:follow')" class="home-task" @click="router.push('/visits')"><span class="entry-symbol">访</span><span><b>客户回访</b><small>待回访 {{ visitStats.pending }} 位</small></span><span>›</span></button>
      <button v-if="auth.can('processing:view')" class="home-task" @click="router.push('/todo')"><span class="entry-symbol">工</span><span><b>加工待处理</b><small>查看待转交、加工与取货事项</small></span><span>›</span></button>
      <button v-if="auth.can('member:view')" class="home-task" @click="router.push('/sales/birthday')"><span class="entry-symbol">生</span><span><b>生日提醒</b><small>查看会员生日并安排回访</small></span><span>›</span></button>
    </Panel>
    <Panel title="常用功能"><div class="function-grid home-functions"><button v-for="item in salesFunctions" :key="item.key" v-permission="item.permission" class="function-entry" @click="openFunction(item)"><b>{{ SALES_FUNCTION_ICONS[item.key] || '▣' }}</b><span>{{ item.label }}</span></button></div></Panel>
    <Panel v-if="auth.can('report:view') && trendValues.length" title="近期趋势"><TrendBars :values="trendValues" :labels="salesTrendLabels" /></Panel>
  </section>
</template>
<script setup>
import { inject } from 'vue'
import { roleHomeKey } from './context.js'
import Panel from '../../components/Panel.vue'
import MarketQuoteBar from '../../components/MarketQuoteBar.vue'
import TrendBars from '../../components/TrendBars.vue'
const { app, auth, router, section, performance, money, monthLabel, targetValue, visitStats, salesFunctions, SALES_FUNCTION_ICONS, openFunction, trendValues, salesTrendLabels } = inject(roleHomeKey)
</script>
