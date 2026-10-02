<template>
<section  class="page">
<PageTitle :title="kpiDetailTitle">
<button class="outline" @click="section='dashboard'">返回看板</button>
</PageTitle>
<p class="muted small" style="margin:0 0 10px">{{ kpiDetailSummary }}</p>
<Panel :title="kpiDetailTitle">
<div v-if="kpiDetailLoading" class="empty">加载中...</div>
<template v-else>
<div v-for="(row,i) in kpiDetailRows" :key="i" class="rank-row">
<span>{{ row.title }}<small>{{ row.sub }}</small>
</span>
<strong>{{ row.amount }}</strong>
<small>{{ row.extra }}</small>
</div>
<div v-if="!kpiDetailRows.length" class="empty">今日暂无明细</div>
</template>
</Panel>
</section>
</template>
<script setup>
import { inject } from 'vue'
import { roleHomeKey } from './context.js'
import Panel from '../../components/Panel.vue'
import PageTitle from '../../components/PageTitle.vue'
const { section, dashboard, kpiDetailRows, kpiDetailSummary, kpiDetailLoading, kpiDetailTitle } = inject(roleHomeKey)
</script>
