<template>
<section  class="page">
<PageTitle title="7日营业额趋势">
<button class="outline" @click="section='dashboard'">返回看板</button>
</PageTitle>
<Panel title="每日营业额">
<TrendBars :values="managerTrendValues" :labels="managerTrendLabels"/>
<div class="trend-detail-list">
<div v-for="row in managerTrendRows" :key="row.day" class="rank-row">
<span>{{ formatDay(row.day) }}</span>
<strong>¥{{ money(row.amount) }}</strong>
<small>{{ row.orderCount }} 单 · 已结算</small>
</div>
<div v-if="!managerTrendRows.length" class="empty">暂无趋势数据</div>
</div>
</Panel>
<p class="muted small">数据与管理端仪表盘共用 /api/admin/dashboard，订单完成后自动刷新。</p>
</section>
</template>
<script setup>
import { inject } from 'vue'
import { roleHomeKey } from './context.js'
import Panel from '../../components/Panel.vue'
import PageTitle from '../../components/PageTitle.vue'
import TrendBars from '../../components/TrendBars.vue'
const { api, section, dashboard, money, managerTrendRows, managerTrendValues, managerTrendLabels, formatDay } = inject(roleHomeKey)
</script>
