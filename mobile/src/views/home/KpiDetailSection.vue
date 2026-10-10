<template>
<section  class="page">
<PageTitle :title="kpiDetailTitle">
<button class="outline" @click="navigateSection('dashboard')">返回看板</button>
</PageTitle>
<p class="muted small" style="margin:0 0 10px">{{ kpiDetailSummary }}</p>
<Panel :title="kpiDetailTitle">
<EmptyState v-if="kpiDetailLoading" title="加载中..." />
<template v-else>
<div v-for="(row,i) in kpiDetailRows" :key="i" class="rank-row">
<span>{{ row.title }}<small>{{ row.sub }}</small>
</span>
<strong>{{ row.amount }}</strong>
<small>{{ row.extra }}</small>
</div>
<EmptyState v-if="!kpiDetailRows.length" title="今日暂无明细" />
</template>
</Panel>
</section>
</template>
<script setup>
import EmptyState from '../../components/EmptyState.vue'

import { inject } from 'vue'
import { roleHomeKey } from './context.js'
import Panel from '../../components/Panel.vue'
import PageTitle from '../../components/PageTitle.vue'
const { navigateSection, kpiDetailRows, kpiDetailSummary, kpiDetailLoading, kpiDetailTitle } = inject(roleHomeKey)
</script>
