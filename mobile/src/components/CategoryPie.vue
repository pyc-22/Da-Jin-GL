<template><div class="pie-wrap"><EmptyState v-if="!rows.length" title="暂无品类数据" /><template v-else><div class="pie" :style="{background: gradient}"></div><div class="pie-legend"><div v-for="(r,i) in rows" :key="i" class="pie-legend-row"><i :style="{background: colors[i%colors.length]}"></i><span>{{ r.category || r.name || '未分类' }}</span><b>{{ pct(r) }}%</b><small>¥{{ money(r.revenue) }}</small></div></div></template></div></template>
<script setup>
import EmptyState from './EmptyState.vue'

import { computed } from 'vue'
const props = defineProps({ rows: { type: Array, default: () => [] } })
const colors = ['var(--gold)', 'var(--gold-light)', 'var(--gold-line)', 'var(--ink-2)', 'var(--line-strong)', 'var(--ok)', 'var(--err)']
const total = computed(() => props.rows.reduce((s, r) => s + Number(r.revenue || 0), 0))
const gradient = computed(() => { let acc = 0; const t = total.value || 1; const stops = props.rows.map((r, i) => { const from = acc; acc += Number(r.revenue || 0) / t * 100; return `${colors[i % colors.length]} ${from.toFixed(2)}% ${Math.min(acc, 100).toFixed(2)}%` }); return `conic-gradient(${stops.join(',')})` })
const money = (v) => Number(v || 0).toLocaleString('zh-CN', { maximumFractionDigits: 0 })
const pct = (r) => ((Number(r.revenue || 0) / (total.value || 1)) * 100).toFixed(1)
</script>
