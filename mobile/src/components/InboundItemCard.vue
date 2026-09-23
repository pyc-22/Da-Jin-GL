<template>
  <div class="inbound-swipe" @touchstart="touchStart" @touchmove="touchMove" @touchend="touchEnd">
    <button class="inbound-swipe-delete" aria-label="删除货品" @click="remove">删除</button>
    <article class="inbound-item-card" :style="{ transform: `translateX(${offset}px)` }" @click="closeSwipe">
      <div class="inbound-thumb" @click.stop="$emit('preview', item)"><img v-if="pieceCount || item.catalogImages?.length" :src="imageUrl(firstPiece || item.catalogImages?.[0])" alt="饰品"/><span v-else>无图</span></div>
      <div class="inbound-item-main" @click="$emit('edit', item)">
        <strong>{{ item.name }}</strong>
        <small>{{ item.categoryName || item.category || '未命名品类' }} · 商品条码 {{ item.barcode || '系统生成' }}</small>
        <small v-if="item.pieceNo" class="piece-identity">单件码 {{ item.pieceNo }}</small>
        <small v-if="item.certificateNo">证书号 {{ item.certificateNo }}</small>
        <small>金重 {{ item.goldWeight || 0 }}g · 零售价 ¥{{ money(item.labelPrice) }}</small>
        <div v-if="pieceCount" class="inbound-photo-strip"><button v-for="(image,index) in (item.pieces || [])" :key="`${item.key}-piece-${index}`" class="inbound-photo" @click.stop="$emit('preview', item, index)"><img v-if="image" :src="imageUrl(image)" :alt="`第${index+1}件`"/><small v-else class="piece-empty">空</small></button></div>
        <div class="inbound-item-actions"><button class="outline" @click.stop="$emit('photos', item)">{{ pieceCount ? '按件补拍' : '按件拍照' }}</button><label>数量<input v-model.number="item.quantity" type="number" min="1" step="1" :disabled="Boolean(item.pieceNo)" @click.stop @change="quantityChanged"/></label><button class="danger-text" @click.stop="remove">删除</button></div>
      </div>
      <div class="item-states"><span class="photo-state" :class="{ done: pieceCount }">{{ pieceCount ? `✓ 已拍 ${pieceCount}/${(item.pieces || []).length}` : '未拍' }}</span><span v-if="item.confirmed" class="photo-state done">已确认</span></div>
    </article>
  </div>
</template>
<script setup>
import { ref, computed } from 'vue'
import { http } from '../api/request.js'
import { normalizeGoodsImageUrl } from '../utils/goodsImages.js'
const imageUrl = value => normalizeGoodsImageUrl(value, http.defaults.baseURL)
const props = defineProps({ item: { type: Object, required: true } })
const emit = defineEmits(['remove', 'photos', 'preview', 'remove-photo', 'change', 'edit'])
const pieceCount = computed(() => (props.item.pieces || []).filter(Boolean).length)
const firstPiece = computed(() => (props.item.pieces || []).find(Boolean) || '')
const offset = ref(0)
let startX = 0, startY = 0, horizontal = false
function touchStart(event) { startX = event.changedTouches?.[0]?.clientX || 0; startY = event.changedTouches?.[0]?.clientY || 0; horizontal = false }
function touchMove(event) {
  const touch = event.changedTouches?.[0]; if (!touch) return
  const dx = touch.clientX - startX, dy = touch.clientY - startY
  if (!horizontal && Math.abs(dx) > 8 && Math.abs(dx) > Math.abs(dy)) horizontal = true
  if (!horizontal) return
  if (event.cancelable) event.preventDefault()
  offset.value = Math.max(-82, Math.min(0, dx))
}
function touchEnd() { offset.value = horizontal && offset.value < -42 ? -82 : 0; horizontal = false }
function closeSwipe() { if (offset.value) offset.value = 0 }
function remove() { offset.value = 0; emit('remove', props.item) }
function quantityChanged() { props.item.quantity = props.item.pieceNo ? 1 : Math.max(1, Math.floor(Number(props.item.quantity) || 1)); emit('change', props.item) }
const money = v => Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
</script>
<style scoped>
.item-states{display:flex;flex-direction:column;align-items:flex-end;gap:4px;white-space:nowrap}
.piece-identity{color:var(--gold-deep);font-weight:600;overflow-wrap:anywhere}
</style>
