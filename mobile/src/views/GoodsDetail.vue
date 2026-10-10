<template>
  <div class="shell">
    <DesignHeader title="商品详情" :back="true" @back="router.back()"></DesignHeader>
    <div class="content page">
      <EmptyState v-if="loading" title="加载中..." />
      <template v-if="goods">
        <div class="detail-card">
          <h2>{{ goods.name }}</h2>
          <p class="muted">条码：{{ goods.barcode || '—' }}</p>
          <div class="detail-price">
            <div><span>售价</span><b>{{ Number(goods.price_type) === 1 && !(Number(goods.weight) > 0) ? '到店称重计价' : (priceOf(goods) == null ? '待同步金价' : '¥' + money(priceOf(goods))) }}</b></div>
            <div><span>成本</span><b>¥{{ money(goods.cost_price) }}</b></div>
            <div><span>克重</span><b>{{ Number(goods.price_type) === 1 && !(Number(goods.weight) > 0) ? '按克称重' : (goods.weight ? goods.weight + 'g' : '—') }}</b></div>
          </div>
        </div>
        <div class="photo-card">
          <div class="photo-head"><span>货品照片</span><small>{{ photos.length ? `${photos.length}/9 张` : '暂无照片' }}</small></div>
          <div class="photo-grid">
            <div v-for="(u,i) in photos" :key="i" class="photo-item"><img :src="imageUrl(u)" alt="货品照片" @click="preview(i)"/></div>
            <button v-if="photos.length < 9" class="photo-add" :disabled="uploading" @click="choosePhoto">📷<small>{{ uploading ? '上传中' : '拍照建档' }}</small></button>
          </div>
          <input ref="fileInput" type="file" accept="image/*" capture="environment" hidden @change="onCapture"/>
          <p v-if="photoError" class="error">{{ photoError }}</p>
          <p class="muted small">照片用于销售分享与建档，首张将用于海报</p>
        </div>
        <div class="detail-info">
          <div class="rank-row"><span>分类</span><b>{{ goods.category || '—' }}</b></div>
          <div class="rank-row"><span>库存</span><b>{{ inventoryText(goods) }}</b></div>
          <div class="rank-row"><span>计价方式</span><b>{{ goods.price_type === 1 ? '按克计价' : '一口价' }}</b></div>
          <div class="rank-row"><span>金种</span><b>{{ goods.gold_type || '—' }}</b></div>
          <div class="rank-row"><span>状态</span><b :class="goods.status === 1 ? 'ok' : 'error'">{{ goods.status === 1 ? '在售' : '下架' }}</b></div>
        </div>
        <label v-if="Number(goods.price_type) === 1" class="order-weight">开单计费克重（g）<input v-model.number="orderWeight" type="number" min="0.001" step="0.001" :max="Number(goods.available_stock ?? goods.stock ?? 0)" placeholder="请输入到店称重克重"/><small>可售库存 {{ Number(goods.available_stock ?? goods.stock ?? 0).toFixed(3) }}g</small></label>
        <button class="primary full direct-order-btn" :disabled="!canOrder" @click="directOrder">直接开单</button>
        <button class="primary full poster-btn" @click="makePoster">生成营销海报</button>
      </template>
      <EmptyState v-if="error" :title="error" />
    </div>
    <div v-if="posterUrl" class="mobile-modal" @click.self="posterUrl=''">
      <div class="mobile-modal-card poster-modal">
        <h3>营销海报</h3>
        <img class="poster-img" :src="posterUrl" alt="货品海报"/>
        <p class="muted small">长按图片可保存转发</p>
        <div class="action-row">
          <button class="outline" @click="posterUrl=''">关闭</button>
          <button class="primary" @click="downloadPoster">下载海报</button>
        </div>
      </div>
    </div>
  </div>
</template>
<script setup>
import EmptyState from '../components/EmptyState.vue'

import DesignHeader from '../components/DesignHeader.vue'

import { isNativeApp, takeNativePhoto } from '../utils/nativeDevice.js'
import { computed, ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, http } from '../api/request.js'
import { normalizeGoodsImageUrl } from '../utils/goodsImages.js'
import { useAppStore } from '../stores/app.js'
import { useAuthStore } from '../stores/auth.js'
import { uploadImage } from '../api/upload.js'
import { inventoryText } from '../utils/inventoryUnit.js'
import { useToast } from '../composables/useToast.js'
const route = useRoute()
const router = useRouter()
const app = useAppStore()
const auth = useAuthStore()
const { toast } = useToast()
const imageUrl = value => normalizeGoodsImageUrl(value, http.defaults.baseURL)
const goods = ref(null)
const orderWeight = ref(null)
const loading = ref(true)
const error = ref('')
const photos = ref([])
const uploading = ref(false)
const photoError = ref('')
const posterUrl = ref('')
const fileInput = ref(null)
const money = (v) => Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const canOrder = computed(() => auth.can?.('order:create') !== false && Number(goods.value?.status ?? 1) === 1 && Number(goods.value?.available_stock ?? goods.value?.stock ?? 0) > 0)
function directOrder() {
  if (!canOrder.value) return
  const gramWeight = Number(orderWeight.value)
  if (Number(goods.value.price_type) === 1 && !(gramWeight > 0)) return toast('按克商品请先输入有效克重后再开单')
  if (Number(goods.value.price_type) === 1 && gramWeight > Number(goods.value.available_stock ?? goods.value.stock ?? 0)) return toast('输入克重超过当前可售库存')
  router.push({ path: `/${String(auth.role || 'sales').toLowerCase()}/order`, query: { goodsId: String(goods.value.goods_id), ...(Number(goods.value.price_type) === 1 ? { gramWeight: gramWeight.toFixed(3) } : {}) } })
}

function priceOf(g) {
  if (Number(g.price_type) === 1) {
    const quote = Number(app.primaryGold?.salePrice || 0)
    const weight = Number(g.weight || 0)
    // 按克商品不设档案克重：只有已知克重时才给估算价，否则到店称重计价。
    return quote > 0 && weight > 0 ? weight * quote : null
  }
  return Number(g.sale_price || 0)
}
function parseImages(raw) {
  try { const arr = typeof raw === 'string' ? JSON.parse(raw) : raw; return Array.isArray(arr) ? arr.filter(Boolean) : [] } catch { return [] }
}
async function choosePhoto() {
  if (isNativeApp()) {
    try { await uploadFiles([await takeNativePhoto()]) }
    catch (e) { if (!/cancel|取消/i.test(e.message || '')) photoError.value = e.message || '拍照失败，请检查相机权限' }
    return
  }
  if (typeof uni !== 'undefined' && uni.chooseImage) {
    uni.chooseImage({ count: 9 - photos.value.length, sourceType: ['camera', 'album'], success: (r) => uploadPaths(r.tempFilePaths || []) })
  } else { fileInput.value?.click() }
}
async function onCapture(e) {
  const files = Array.from(e.target.files || [])
  e.target.value = ''
  if (!files.length) return
  await uploadFiles(files)
}
async function uploadPaths(paths) {
  uploading.value = true; photoError.value = ''
  try {
    for (const p of paths) {
      if (photos.value.length >= 9) break
      const d = await uploadImage(p, null)
      photos.value.push(d.url)
    }
    await persist()
  } catch (e) { photoError.value = e.message || '上传失败' } finally { uploading.value = false }
}
async function uploadFiles(files) {
  uploading.value = true; photoError.value = ''
  try {
    for (const f of files) {
      if (photos.value.length >= 9) break
      const d = await uploadImage(null, f)
      photos.value.push(d.url)
    }
    await persist()
  } catch (e) { photoError.value = e.message || '上传失败' } finally { uploading.value = false }
}
async function persist() {
  await api.updateGoodsImages(goods.value.goods_id, photos.value)
  goods.value = { ...goods.value, images: JSON.stringify(photos.value) }
}
function preview(i) {
  if (typeof uni !== 'undefined' && uni.previewImage) uni.previewImage({ urls: photos.value.map(imageUrl), current: i })
  else window.open(imageUrl(photos.value[i]), '_blank')
}

function loadImage(url) {
  return new Promise((resolve) => {
    const img = new Image()
    img.crossOrigin = 'anonymous'
    img.onload = () => resolve(img)
    img.onerror = () => resolve(null)
    img.src = url
  })
}
function roundRect(ctx, x, y, w, h, r) {
  ctx.beginPath()
  ctx.moveTo(x + r, y)
  ctx.arcTo(x + w, y, x + w, y + h, r)
  ctx.arcTo(x + w, y + h, x, y + h, r)
  ctx.arcTo(x, y + h, x, y, r)
  ctx.arcTo(x, y, x + w, y, r)
  ctx.closePath()
}
async function makePoster() {
  const g = goods.value
  const c = document.createElement('canvas')
  c.width = 720; c.height = 960
  const ctx = c.getContext('2d')
  const tokens = getComputedStyle(document.documentElement)
  const color = name => tokens.getPropertyValue(name).trim()
  ctx.fillStyle = color('--bg'); ctx.fillRect(0, 0, 720, 960)
  const grad = ctx.createLinearGradient(0, 0, 720, 250)
  grad.addColorStop(0, color('--gold-light')); grad.addColorStop(1, color('--gold-deep'))
  ctx.fillStyle = grad; ctx.fillRect(0, 0, 720, 250)
  ctx.fillStyle = color('--card')
  ctx.font = 'bold 42px sans-serif'
  ctx.fillText((g.name || '货品').slice(0, 14), 40, 105)
  ctx.font = '26px sans-serif'
  ctx.fillText(`条码 ${g.barcode || '—'}`, 40, 160)
  ctx.font = '26px sans-serif'
  ctx.fillText(`${g.category || ''}${g.gold_type ? ' · ' + g.gold_type : ''}`, 40, 205)
  const photo = photos.value.length ? await loadImage(imageUrl(photos.value[0])) : null
  if (photo) {
    ctx.save()
    roundRect(ctx, 40, 290, 640, 400, 20); ctx.clip()
    const scale = Math.max(640 / photo.width, 400 / photo.height)
    const dw = photo.width * scale, dh = photo.height * scale
    ctx.drawImage(photo, 40 + (640 - dw) / 2, 290 + (400 - dh) / 2, dw, dh)
    ctx.restore()
  } else {
    ctx.fillStyle = color('--gold-soft'); roundRect(ctx, 40, 290, 640, 400, 20); ctx.fill()
    ctx.fillStyle = color('--gold-deep'); ctx.font = '30px sans-serif'; ctx.textAlign = 'center'
    ctx.fillText('货品图片', 360, 505); ctx.textAlign = 'left'
  }
  ctx.fillStyle = color('--ink'); ctx.font = 'bold 34px sans-serif'
  const gold = Number(app.primaryGold?.salePrice || 0)
  const priceText = Number(g.price_type) === 1
    ? (gold > 0
      ? (Number(g.weight || 0) > 0 ? `金价 ¥${money(gold)}/g × ${g.weight}g` : `金价 ¥${money(gold)}/g · 以门店称重为准`)
      : '金价待同步，请咨询门店')
    : `一口价 ¥${money(g.sale_price)}`
  const amountText = priceOf(g) == null ? '价格待确认' : `¥${money(priceOf(g))}`
  ctx.fillText(priceText, 40, 760)
  ctx.fillStyle = color('--gold-deep'); ctx.font = 'bold 64px sans-serif'
  ctx.fillText(amountText, 40, 845)
  ctx.fillStyle = color('--ink-3'); ctx.font = '24px sans-serif'
  ctx.fillText(`${storeLabel()} · ${new Date().toLocaleDateString('zh-CN')}`, 40, 915)
  ctx.textAlign = 'right'
  ctx.fillText('长按识别 · 到店选购', 680, 915)
  ctx.textAlign = 'left'
  posterUrl.value = c.toDataURL('image/png')
}
function storeLabel() { return auth.user?.store_name || '金店零售' }
function downloadPoster() {
  if (typeof uni !== 'undefined' && uni.previewImage) { uni.previewImage({ urls: [posterUrl.value] }); return }
  const a = document.createElement('a')
  a.href = posterUrl.value
  a.download = `goods-${goods.value.barcode || goods.value.goods_id}.png`
  a.click()
}

onMounted(async () => {
  app.loadGold()
  if (goods.value) { photos.value = parseImages(goods.value.images); loading.value = false; return }
  try {
    const row = await api.goodsById(route.params.id)
    if (row) { goods.value = row; orderWeight.value = Number(row.weight) > 0 ? Number(row.weight) : null; photos.value = parseImages(row.images) } else { error.value = '商品不存在' }
  } catch (e) { error.value = e.message || '加载失败' }
  finally { loading.value = false }
})
</script>
<style scoped>
.order-weight{display:grid;gap:5px;margin:14px 0;color:var(--ink-2);font-size:13px}.order-weight input{min-height:var(--tap);border:1px solid var(--line);border-radius:var(--r-md);padding:0 12px;background:var(--card);color:var(--ink)}.order-weight small{color:var(--ink-3)}
.photo-card{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:15px;margin-bottom:14px}
.photo-head{display:flex;justify-content:space-between;align-items:center;margin-bottom:10px}
.photo-head span{font-weight:600}
.photo-head small{color:var(--ink-3)}
.photo-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:8px}
.photo-item{aspect-ratio:1;border-radius:var(--r-md);overflow:hidden;background:var(--line-soft)}
.photo-item img{width:100%;height:100%;object-fit:cover;display:block}
.photo-add{aspect-ratio:1;border:1px dashed var(--line);border-radius:var(--r-md);background:var(--bg);color:var(--ink-3);font-size:24px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:2px}
.photo-add small{font-size:11px}
.photo-add:disabled{opacity:.6}
.poster-btn{margin:6px 0 20px}
.poster-modal{text-align:center}
.poster-img{width:100%;border-radius:var(--r-md);border:1px solid var(--line)}
</style>
