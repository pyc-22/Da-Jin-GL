<template><div class="pie-wrap"><div v-if="!rows.length" class="empty">暂无品类数据</div><template v-else><div class="pie" :style="{background: gradient}"></div><div class="pie-legend"><div v-for="(r,i) in rows" :key="i" class="pie-legend-row"><i :style="{background: colors[i%colors.length]}"></i><span>{{ r.category || r.name || '未分类' }}</span><b>{{ pct(r) }}%</b><small>¥{{ money(r.revenue) }}</small></div></div></template></div></template>
<script setup>
import { computed } from 'vue'
const props = defineProps({ rows: { type: Array, default: () => [] } })
const colors = ['#b7791f', '#d69e2e', '#48bb78', '#4299e1', '#9f7aea', '#ed64a6', '#a0aec0']
const total = computed(() => props.rows.reduce((s, r) => s + Number(r.revenue || 0), 0))
const gradient = computed(() => { let acc = 0; const t = total.value || 1; const stops = props.rows.map((r, i) => { const from = acc; acc += Number(r.revenue || 0) / t * 100; return `${colors[i % colors.length]} ${from.toFixed(2)}% ${Math.min(acc, 100).toFixed(2)}%` }); return `conic-gradient(${stops.join(',')})` })
const money = (v) => Number(v || 0).toLocaleString('zh-CN', { maximumFractionDigits: 0 })
const pct = (r) => ((Number(r.revenue || 0) / (total.value || 1)) * 100).toFixed(1)
</script>
