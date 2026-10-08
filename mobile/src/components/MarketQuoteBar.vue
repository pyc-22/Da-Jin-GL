<template>
  <section class="market-quote-bar">
    <div v-if="showMeta" class="market-quote-head">
      <span>{{ sourceLabel }}</span>
      <StatusPill :tone="status === 'OPEN' ? 'ok' : ['ERROR', 'FROZEN'].includes(status) ? 'err' : 'muted'">● {{ statusLabel }}</StatusPill>
    </div>
    <div class="market-quote-grid">
      <div v-for="quote in quotes" :key="quote.label" class="market-quote-item">
        <span>{{ quote.label }}</span>
        <strong>¥{{ money(quote.value) }}<small>/g</small></strong>
        <em v-if="!compact && quote.change != null" :class="Number(quote.change) >= 0 ? 'up' : 'down'">
          {{ Number(quote.change) > 0 ? '+' : '' }}{{ quote.change }}%
        </em>
      </div>
    </div>
    <small v-if="showMeta && !compact" class="market-quote-meta">{{ source }}<template v-if="time"> · {{ time }}</template><template v-if="countdown"> · {{ countdown }}</template></small>
  </section>
</template>

<script setup>
import StatusPill from './StatusPill.vue'
import { computed } from 'vue'

const props = defineProps({
  showSilver: { type: Boolean, default: true },
  gold: { type: Object, default: null },
  recycle: { type: Object, default: null },
  silver: { type: Object, default: null },
  silverRecycle: { type: Object, default: null },
  sourceLabel: { type: String, default: '今日金银价格' },
  countdown: { type: String, default: '' },
  compact: { type: Boolean, default: false },
  showMeta: { type: Boolean, default: true }
})
const money = value => value == null ? '—' : Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const status = computed(() => String(props.gold?.marketStatus || '').toUpperCase())
const statusLabel = computed(() => ({ OPEN: '开市', CLOSED: '休市', ERROR: '行情失败·旧价', FROZEN: '异常冻结' }[status.value] || '待同步'))
const statusClass = computed(() => ({ OPEN: 'open', CLOSED: 'closed', ERROR: 'error', FROZEN: 'frozen' }[status.value] || 'pending'))
const source = computed(() => props.gold?.source || props.recycle?.source || '来源待同步')
const time = computed(() => props.gold?.quoteTime || props.recycle?.quoteTime || '')
const quotes = computed(() => [
  { label: '足金卖价', value: props.gold?.salePrice },
  { label: '足金回收价', value: props.gold?.recyclePrice ?? props.recycle?.recyclePrice },
  ...(props.showSilver ? [{ label: '银卖价', value: props.silver?.salePrice },
  { label: '银回收价', value: props.silver?.recyclePrice ?? props.silverRecycle?.recyclePrice }] : [])
])
</script>
