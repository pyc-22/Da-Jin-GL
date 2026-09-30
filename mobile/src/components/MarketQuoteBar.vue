<template>
  <section class="market-quote-bar">
    <div v-if="showMeta" class="market-quote-head">
      <span>{{ sourceLabel }}</span>
      <span class="market-status" :class="statusClass">● {{ statusLabel }}</span>
    </div>
    <div class="market-quote-grid">
      <div v-for="quote in quotes" :key="quote.label" class="market-quote-item">
        <span>{{ quote.label }}</span>
        <strong>¥{{ money(quote.value) }}<small>/g</small></strong>
        <em v-if="!compact && quote.change != null" :class="Number(quote.change) >= 0 ? 'up' : 'down'">
          {{ Number(quote.change) >= 0 ? '+' : '' }}{{ quote.change }}%
        </em>
      </div>
    </div>
    <small v-if="showMeta && !compact" class="market-quote-meta">{{ source }}<template v-if="time"> · {{ time }}</template><template v-if="countdown"> · {{ countdown }}</template></small>
  </section>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  gold: { type: Object, default: null },
  recycle: { type: Object, default: null },
  silver: { type: Object, default: null },
  silverRecycle: { type: Object, default: null },
  sourceLabel: { type: String, default: '实时金银行情' },
  countdown: { type: String, default: '' },
  compact: { type: Boolean, default: false },
  showMeta: { type: Boolean, default: true }
})
const money = value => Number(value || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const status = computed(() => String(props.gold?.marketStatus || '').toUpperCase())
const statusLabel = computed(() => ({ OPEN: '开市', CLOSED: '休市', ERROR: '行情失败·旧价', FROZEN: '异常冻结' }[status.value] || '待同步'))
const statusClass = computed(() => ({ OPEN: 'open', CLOSED: 'closed', ERROR: 'error', FROZEN: 'frozen' }[status.value] || 'pending'))
const source = computed(() => props.gold?.source || props.recycle?.source || '统一行情数据源')
const time = computed(() => props.gold?.quoteTime || props.recycle?.quoteTime || '')
const quotes = computed(() => [
  { label: '足金卖价', value: props.gold?.salePrice ?? props.gold?.price },
  { label: '足金回收价', value: props.recycle?.recyclePrice ?? props.recycle?.price },
  { label: '银卖价', value: props.silver?.salePrice ?? props.silver?.price },
  { label: '银回收价', value: props.silverRecycle?.recyclePrice ?? props.silverRecycle?.price }
])
</script>
