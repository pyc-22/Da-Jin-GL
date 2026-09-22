<template><div v-if="values.length"><div class="trend"><div v-for="(v,i) in heights" :key="i" class="bar" :style="{height: `${v}%`}" :title="labels?.[i] || ''"></div></div><div v-if="labels?.length" class="trend-labels"><span v-for="(l,i) in labels" :key="i">{{ l }}</span></div></div></template>
<script setup>
import { computed } from 'vue'
const props = defineProps({ values: { type: Array, default: () => [35, 48, 42, 64, 55, 78, 68] }, labels: Array })
const heights = computed(() => {
  const nums = props.values.map(v => Math.max(0, Number(v) || 0))
  const max = Math.max(...nums)
  return nums.map(v => (max > 0 ? 10 + 90 * (v / max) : 10))
})
</script>
