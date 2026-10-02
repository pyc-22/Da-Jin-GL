<template>
<section  class="page">
<PageTitle title="业绩报表">
<select v-model="period" @change="loadReport">
<option value="daily">日</option>
<option value="weekly">周</option>
<option value="monthly">月</option>
</select>
</PageTitle>
<div class="kpi-grid">
<Kpi label="营业额" :value="`¥${money(report?.turnover ?? report?.amount)}`"/>
<Kpi label="毛利" :value="`¥${money(report?.gross_profit)}`"/>
<Kpi label="客单价" :value="`¥${money(report?.avg_order)}`"/>
<Kpi label="回收量" :value="`${report?.recycle_weight || 0}g`"/>
</div>
<p v-if="reportError" class="muted small">{{ reportError }}</p>
<Panel title="员工明细">
<div v-for="(row,i) in commission" :key="i" class="rank-row">
<span>{{ row.employee_name || row.real_name || '员工' }}</span>
<strong>¥{{ money(row.sales_amount || row.amount) }}</strong>
<small>{{ row.order_count || 0 }} 单 · 提成 ¥{{ money(row.commission_amount) }}</small>
</div>
</Panel>
<Panel :title="reportTabTitle">
<div class="chips">
<button v-for="t in reportTabs" :key="t.key" :class="{active: reportTab===t.key}" @click="reportTab=t.key">{{ t.label }}</button>
</div>
<template v-if="reportTab==='category'">
<CategoryPie :rows="categoryRows"/>
</template>
<template v-else-if="reportTab==='pay'">
<CategoryPie :rows="payMethodRows"/>
<div v-if="!payMethodRows.length" class="empty">今日暂无收款</div>
</template>
<template v-else>
<div class="kpi-grid">
<Kpi label="回收单数" :value="recycleData.order_count||0"/>
<Kpi label="回收克重" :value="`${recycleData.weight||0}g`"/>
<Kpi label="回收金额" :value="`¥${money(recycleData.amount)}`"/>
<Kpi label="折旧损耗" :value="`¥${money(recycleData.deduct_loss)}`"/>
</div>
</template>
</Panel>
</section>
</template>
<script setup>
import { inject } from 'vue'
import { roleHomeKey } from './context.js'
import Kpi from '../../components/Kpi.vue'
import Panel from '../../components/Panel.vue'
import PageTitle from '../../components/PageTitle.vue'
import CategoryPie from '../../components/CategoryPie.vue'
const { section, report, period, commission, reportError, recycleData, reportTab, reportTabs, reportTabTitle, payMethodRows, money, categoryRows, loadReport } = inject(roleHomeKey)
</script>
