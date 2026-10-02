<template>
<section  class="page">
<PageTitle title="个人业绩"/>
<div class="progress">
<span>本月目标完成度</span>
<b>{{ targetValue>0 ? `${performance.progress||0}%` : '未设目标' }}</b>
<em>
<i :style="{width: `${targetValue>0?(performance.progress||0):0}%`}">
</i>
</em>
</div>
<div class="kpi-grid">
<Kpi label="销售额" :value="`¥${money(performance.amount)}`"/>
<Kpi label="成交单数" :value="performance.orders || 0"/>
<Kpi label="客单价" :value="`¥${money(performance.avg)}`"/>
<Kpi label="提成金额" :value="`¥${money(performance.commission)}`"/>
</div>
<div class="kpi-grid">
<Kpi label="成品销售提成" :value="`¥${money(commission[0]?.sales_commission)}`"/>
<Kpi label="加工工费基数" :value="`¥${money(commission[0]?.processing_base)}`"/>
<Kpi label="加工导购提成" :value="`¥${money(commission[0]?.processing_commission)}`"/>
</div>
<Panel title="近期趋势">
<TrendBars :values="trendValues" :labels="salesTrendLabels"/>
</Panel>
<Panel title="提成明细">
<div v-for="(row,i) in commission" :key="i" class="rank-row">
<span>{{ row.month || row.date || '本月' }}</span>
<strong>¥{{ money(row.commission_amount || row.commission) }}</strong>
<small>成品销售提成 ¥{{ money(row.sales_commission) }} · 加工导购提成 ¥{{ money(row.processing_commission) }}</small>
</div>
</Panel>
</section>
</template>
<script setup>
import { inject } from 'vue'
import { roleHomeKey } from './context.js'
import Kpi from '../../components/Kpi.vue'
import Panel from '../../components/Panel.vue'
import PageTitle from '../../components/PageTitle.vue'
import TrendBars from '../../components/TrendBars.vue'
const { section, commission, performance, trendValues, targetValue, salesTrendLabels, money } = inject(roleHomeKey)
</script>
