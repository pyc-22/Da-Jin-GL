<template><div class="gold-trend"><div v-if="!points.length" class="empty">暂无金价历史数据</div><template v-else><svg viewBox="0 0 300 100" preserveAspectRatio="none" class="gold-svg"><polyline :points="polyPoints" fill="none" stroke="#b7791f" stroke-width="2" vector-effect="non-scaling-stroke"/><circle v-for="(p,i) in plotted" :key="i" :cx="p.x" :cy="p.y" r="2.5" fill="#b7791f"/></svg><div class="trend-meta"><span>{{ points[0]?.date }}</span><span>最高 ¥{{ maxV }} · 最低 ¥{{ minV }}</span><span>{{ points[points.length-1]?.date }}</span></div></template></div></template>
<script setup>
import { computed } from 'vue'
const props = defineProps({ points: { type: Array, default: () => [] } })
const values = computed(() => props.points.map(p => Number(p.price || 0)))
const maxV = computed(() => Math.max(...values.value, 0).toFixed(2))
const minV = computed(() => Math.min(...values.value, Number.MAX_SAFE_INTEGER).toFixed(2))
const plotted = computed(() => { const vs = values.value; if (!vs.length) return []; const lo = Math.min(...vs), hi = Math.max(...vs); const span = Math.max(hi - lo, 1); const stepX = vs.length > 1 ? 300 / (vs.length - 1) : 0; return vs.map((v, i) => ({ x: i * stepX, y: 95 - ((v - lo) / span) * 85 })) })
const polyPoints = computed(() => plotted.value.map(p => `${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(' '))
</script>
