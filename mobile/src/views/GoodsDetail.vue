<template>
  <div class="shell">
    <header class="topbar"><div><button class="back" @click="router.back()">&#8249;</button><strong>商品详情</strong></div><div></div></header>
    <div class="content page">
      <div v-if="loading" class="empty">加载中...</div>
      <template v-if="goods">
        <div class="detail-card">
          <h2>{{ goods.name }}</h2>
          <p class="muted">条码：{{ goods.barcode || '—' }}</p>
          <div class="detail-price">
            <div><span>售价</span><b>¥{{ money(priceOf(goods)) }}</b></div>
            <div><span>成本</span><b>¥{{ money(goods.cost_price) }}</b></div>
            <div><span>克重</span><b>{{ goods.weight ? goods.weight + 'g' : '—' }}</b></div>
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
          <div class="rank-row"><span>库存</span><b>{{ goods.stock }}</b></div>
          <div class="rank-row"><span>计价方式</span><b>{{ goods.price_type === 1 ? '按克计价' : '一口价' }}</b></div>
          <div class="rank-row"><span>金种</span><b>{{ goods.gold_type || '—' }}</b></div>
          <div class="rank-row"><span>状态</span><b :class="goods.status === 1 ? 'ok' : 'error'">{{ goods.status === 1 ? '在售' : '下架' }}</b></div>
        </div>
        <button class="primary full poster-btn" @click="makePoster">生成营销海报</button>
      </template>
      <div v-if="error" class="empty">{{ error }}</div>
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
import { isNativeApp, takeNativePhoto } from '../utils/nativeDevice.js'
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, http } from '../api/request.js'
import { normalizeGoodsImageUrl } from '../utils/goodsImages.js'
import { useAppStore } from '../stores/app.js'
import { useAuthStore } from '../stores/auth.js'
import { uploadImage } from '../api/upload.js'
const route = useRoute()
const router = useRouter()
const app = useAppStore()
const auth = useAuthStore()
const imageUrl = value => normalizeGoodsImageUrl(value, http.defaults.baseURL)
let initialGoods = null
try { initialGoods = route.query.goods ? JSON.parse(route.query.goods) : null } catch {}
const goods = ref(initialGoods)
const loading = ref(!goods.value)
const error = ref('')
const photos = ref([])
const uploading = ref(false)
const photoError = ref('')
const posterUrl = ref('')
const fileInput = ref(null)
const money = (v) => Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

function priceOf(g) {
  if (Number(g.price_type) === 1) return Number(g.weight || 0) * Number(app.primaryGold?.price || 612)
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
  ctx.fillStyle = '#faf7f2'; ctx.fillRect(0, 0, 720, 960)
  const grad = ctx.createLinearGradient(0, 0, 720, 250)
  grad.addColorStop(0, '#d4a437'); grad.addColorStop(1, '#b7791f')
  ctx.fillStyle = grad; ctx.fillRect(0, 0, 720, 250)
  ctx.fillStyle = '#fff'
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
    ctx.fillStyle = '#f0e6d2'; roundRect(ctx, 40, 290, 640, 400, 20); ctx.fill()
    ctx.fillStyle = '#b7791f'; ctx.font = '30px sans-serif'; ctx.textAlign = 'center'
    ctx.fillText('货品图片', 360, 505); ctx.textAlign = 'left'
  }
  ctx.fillStyle = '#1a1a1a'; ctx.font = 'bold 34px sans-serif'
  const gold = Number(app.primaryGold?.price || 612)
  const priceText = Number(g.price_type) === 1
    ? `金价 ¥${money(gold)}/g × ${g.weight || 0}g`
    : `一口价 ¥${money(g.sale_price)}`
  const amountText = `¥${money(priceOf(g))}`
  ctx.fillText(priceText, 40, 760)
  ctx.fillStyle = '#b7791f'; ctx.font = 'bold 64px sans-serif'
  ctx.fillText(amountText, 40, 845)
  ctx.fillStyle = '#7b8490'; ctx.font = '24px sans-serif'
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
    const d = await api.goods({ keyword: String(route.params.id), page: 1, size: 1 })
    const rows = d.records || d || []
    if (rows.length) { goods.value = rows[0]; photos.value = parseImages(rows[0].images) } else { error.value = '商品不存在' }
  } catch (e) { error.value = e.message || '加载失败' }
  finally { loading.value = false }
})
</script>
<style scoped>
.photo-card{background:#fff;border:1px solid #eceef2;border-radius:12px;padding:15px;margin-bottom:14px}
.photo-head{display:flex;justify-content:space-between;align-items:center;margin-bottom:10px}
.photo-head span{font-weight:600}
.photo-head small{color:#7b8490}
.photo-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:8px}
.photo-item{aspect-ratio:1;border-radius:8px;overflow:hidden;background:#f4f5f7}
.photo-item img{width:100%;height:100%;object-fit:cover;display:block}
.photo-add{aspect-ratio:1;border:1px dashed #c9ced6;border-radius:8px;background:#fafbfc;color:#7b8490;font-size:24px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:2px}
.photo-add small{font-size:11px}
.photo-add:disabled{opacity:.6}
.poster-btn{margin:6px 0 20px}
.poster-modal{text-align:center}
.poster-img{width:100%;border-radius:8px;border:1px solid #eceef2}
</style>
