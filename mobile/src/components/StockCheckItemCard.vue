<template>
  <article class="check-item-card" :class="{ counted: item.counted, different: difference !== 0 }">
    <div class="check-item-thumb">
      <img v-if="image" :src="image" alt="货品图片" />
      <span v-else>无图</span>
    </div>
    <div class="check-item-main">
      <div class="check-item-title"><strong>{{ item.name }}</strong><span :class="differenceClass">{{ differenceText }}</span></div>
      <small>{{ item.barcode || '无条码' }} · {{ category }}</small>
      <div class="check-stock-line"><span>系统 <b>{{ number(item.systemStock) }}{{ unit }}</b></span><span>实盘 <b>{{ number(item.actual) }}{{ unit }}</b></span></div>
      <div class="check-stepper">
        <button :aria-label="`减少实盘${unit === 'g' ? '克重' : '数量'}`" @click="changeBy(-step)">−</button>
        <input :value="item.actual" type="number" min="0" :step="step" inputmode="decimal" :aria-label="`实盘${unit === 'g' ? '克重' : '数量'}`" @input="setActual($event.target.value)" />
        <button :aria-label="`增加实盘${unit === 'g' ? '克重' : '数量'}`" @click="changeBy(step)">＋</button>
        <button class="check-confirm" @click="confirmCurrent">{{ item.counted ? '已盘' : '确认数量' }}</button>
      </div>
    </div>
  </article>
</template>

<script setup>
import { computed } from 'vue'
import { isGramPriced } from '../utils/inventoryUnit.js'

const props = defineProps({ item: { type: Object, required: true } })
const emit = defineEmits(['change'])

const number = value => Number(value || 0).toLocaleString('zh-CN', { maximumFractionDigits: 3 })
const difference = computed(() => Number((Number(props.item.actual || 0) - Number(props.item.systemStock || 0)).toFixed(3)))
const differenceText = computed(() => difference.value === 0 ? '无差异' : `${difference.value > 0 ? '盘盈 +' : '盘亏 '}${number(difference.value)}${unit.value}`)
const differenceClass = computed(() => difference.value > 0 ? 'check-profit' : difference.value < 0 ? 'check-loss' : 'check-even')
const category = computed(() => [props.item.parentCategory, props.item.category].filter(Boolean).join(' / ') || '未分类')
const unit = computed(() => isGramPriced(props.item) ? 'g' : '件')
const step = computed(() => unit.value === 'g' ? 0.001 : 1)
const image = computed(() => {
  const raw = props.item.images
  if (Array.isArray(raw)) return raw[0] || ''
  try { return JSON.parse(raw || '[]')[0] || '' } catch { return '' }
})

function update(value, counted = true) {
  const actual = Math.max(0, Number(value || 0))
  emit('change', props.item, Number(actual.toFixed(3)), counted)
}
function setActual(value) { update(value) }
function changeBy(delta) { update(Number(props.item.actual || 0) + delta) }
function confirmCurrent() { update(props.item.actual, true) }
</script>
