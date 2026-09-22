<template>
  <div class="shell">
    <header class="topbar"><button class="back" @click="goBack">‹</button><strong>扫码入库</strong><span class="state" :class="{ disabled: app.offline }">{{ app.offline ? '离线' : '在线' }}</span></header>
    <main class="content page inbound-page">
      <div class="steps"><div v-for="(label, i) in STEP_LABELS" :key="label" :class="['step', { done: step > i + 1, cur: step === i + 1 }]"><i>{{ step > i + 1 ? '✓' : i + 1 }}</i><span>{{ label }}</span></div></div>
      <p v-if="notice" class="scan-notice">{{ notice }}</p>

      <template v-if="step === 1">
        <div class="scan-toolbar"><ScanCodeButton ref="scanButton" :disabled="busy" @scan="handleScan" @scan-error="onScanError" @scan-request="manualScan"/><button class="outline" @click="manualScan">手动输入条码</button><button class="outline" @click="openManualForm('')">手动添加</button></div>
        <p v-if="isH5 && !items.length" class="scan-h5-hint">当前为浏览器测试模式，点击“扫码添加”后输入条码；真机 App 会直接打开相机扫码。</p>
        <p v-if="purchaseExpected" class="purchase-progress">采购单已扫 <b>{{ totalQuantity }}/{{ purchaseExpected }}</b> 件</p>
      </template>

      <div v-else-if="step === 2 && active" class="form-card">
        <div class="match-badge">扫码匹配成功</div>
        <h3 class="inbound-detail-name">{{ active.name }}</h3>
        <dl class="inbound-facts">
          <dt>商品条码</dt><dd>{{ active.barcode || '系统生成' }}</dd>
          <template v-if="active.pieceNo"><dt>单件码</dt><dd class="piece-code">{{ active.pieceNo }}</dd></template>
          <dt>品类</dt><dd>{{ active.categoryName || '未命名品类' }}</dd>
          <dt>金重</dt><dd>{{ Number(active.goldWeight || 0).toFixed(3) }}g</dd>
          <template v-if="active.certificateNo"><dt>证书号</dt><dd>{{ active.certificateNo }}</dd></template>
          <dt>零售价</dt><dd>¥{{ money(active.labelPrice) }}</dd>
        </dl>
        <label v-if="!active.pieceNo" class="form-label">数量
          <div class="qty-stepper">
            <button type="button" aria-label="减少数量" @click="setQty(-1)">−</button>
            <input v-model.number="active.quantity" type="number" min="1" step="1" inputmode="numeric" @change="normalizeQty"/>
            <button type="button" aria-label="增加数量" @click="setQty(1)">＋</button>
          </div>
        </label>
        <p v-else class="identity-tip">该标签为一物一码，本次入库数量固定为 1 件。</p>
        <p v-if="goldPrice > 0" class="gold-ref">今日金价参考：{{ goldTypeName }} ¥{{ goldPrice.toFixed(2) }}/g</p>
        <div class="action-row"><button class="outline" @click="goBack">‹ 上一步</button><button class="primary" @click="toStep(3)">下一步·拍照留存 ›</button></div>
      </div>

      <div v-else-if="step === 3 && active" class="form-card">
        <h3>{{ active.name }}</h3>
        <p class="muted small">实物照片最多4张（当前共 {{ active.quantity }} 件），点照片可放大，× 可重拍。</p>
        <div class="piece-photos">
          <div v-for="(p, pi) in (active.pieces || [])" :key="pi" class="piece-photo">
            <span class="piece-tag">第{{ pi + 1 }}件</span>
            <div v-if="p" class="ph"><img :src="imageUrl(p)" alt="实物照片" @click="previewUrl = p"/><i aria-label="重拍" @click="clearPiecePhoto(pi)">×</i></div>
            <button v-else class="ph ph-add" type="button" @click="takePiecePhoto(pi)"><span>＋</span><small>拍照</small></button>
          </div>
        </div>
        <div class="action-row"><button class="outline" @click="goBack">‹ 上一步</button><button class="primary" @click="toStep(4)">下一步·确认入库 ›</button></div>
      </div>

      <div v-else-if="step === 4 && active" class="form-card">
        <h3>入库确认</h3>
        <dl class="inbound-facts">
          <dt>货品</dt><dd>{{ active.name }} × {{ active.quantity }}</dd>
          <dt>金重</dt><dd>{{ (Number(active.goldWeight || 0) * Number(active.quantity || 0)).toFixed(3) }}g</dd>
          <dt>零售价</dt><dd>¥{{ money(Number(active.labelPrice || 0) * Number(active.quantity || 0)) }}</dd>
          <dt>照片</dt><dd>已拍 {{ (active.pieces || []).filter(Boolean).length }} / {{ active.quantity }} 件</dd>
          <dt>入库类型</dt><dd>{{ typeName(meta.inboundType) }}</dd>
          <template v-if="sourceRow"><dt>{{ sourceRow[0] }}</dt><dd>{{ sourceRow[1] }}</dd></template>
          <dt>入库门店</dt><dd>{{ storeLabel }}</dd>
          <dt>操作人</dt><dd>{{ operatorName }}</dd>
        </dl>
        <div class="action-row"><button class="outline" @click="goBack">‹ 上一步</button><button class="primary" @click="confirmItem">确认入库</button></div>
      </div>

      <Panel title="入库清单">
        <template #actions><button class="panel-toggle" @click="listExpanded = !listExpanded">{{ listExpanded ? '收起' : `展开（${items.length}）` }} {{ listExpanded ? '⌃' : '⌄' }}</button></template>
        <div v-if="!items.length" class="empty">请扫描或手动添加货品<button v-if="isH5" class="outline empty-action" @click="manualScan">输入条码开始</button></div>
        <div v-else-if="listExpanded" class="inbound-list"><InboundItemCard v-for="item in items" :key="item.key" :item="item" @edit="editItem" @remove="removeItem" @photos="openPhotoFlow" @preview="previewItem" @remove-photo="removePhoto" @change="persist"/></div>
      </Panel>
      <div class="inbound-summary"><span>总件数 <b>{{ totalQuantity }}</b></span><span>总金重 <b>{{ totalWeight.toFixed(3) }}g</b></span><span>总金额 <b>¥{{ money(totalAmount) }}</b></span></div>
      <button v-permission="'stock:inbound:create'" class="primary full submit-inbound" :disabled="!items.length || submitting || step !== 1" @click="submit">{{ submitting ? '提交中...' : '提交入库' }}</button>
    </main>
    <GoodsManualForm v-model="manualForm" :initial-barcode="pendingBarcode" :initial-data="pendingManualData" @submit="addManual"/>
    <div v-if="barcodeDialog" class="mobile-modal" @click.self="closeBarcodeDialog"><div class="mobile-modal-card barcode-dialog"><div class="modal-head"><h3>输入条码</h3><button class="modal-close" aria-label="关闭" @click="closeBarcodeDialog">✕</button></div><p class="muted small">请输入饰品标签上的条码或二维码编号</p><input ref="barcodeInput" v-model.trim="barcodeValue" class="barcode-input" inputmode="numeric" autocomplete="off" placeholder="请输入条码号" @keyup.enter="confirmBarcode"/><p v-if="barcodeError" class="error">{{ barcodeError }}</p><div class="action-row"><button class="outline" @click="closeBarcodeDialog">取消</button><button class="primary" :disabled="!barcodeValue" @click="confirmBarcode">查询货品</button></div></div></div>
    <div v-if="previewUrl" class="mobile-modal" @click.self="previewUrl=''" @keyup.esc="previewUrl=''" tabindex="-1"><div class="mobile-modal-card photo-preview-card"><img :src="imageUrl(previewUrl)" alt="饰品大图"/><button class="outline full" @click="previewUrl=''">关闭</button></div></div>
  </div>
</template>
<script setup>
import { normalizeGoodsImageUrl } from '../utils/goodsImages.js'
const imageUrl = value => normalizeGoodsImageUrl(value, http.defaults.baseURL)
const pageCache = scopedStorage()
const getStorage = pageCache.get, setStorage = pageCache.set
import { isNativeApp, takeNativePhoto } from '../utils/nativeDevice.js'
import { computed, nextTick, onMounted, onBeforeUnmount, ref } from 'vue'; import { useRouter } from 'vue-router'; import { useAppStore } from '../stores/app.js'; import { useAuthStore } from '../stores/auth.js'; import { api, http } from '../api/request.js'; import { uploadImage } from '../api/upload.js'; import { scopedStorage } from '../utils/storage.js'; import { inboundItemKey, parseInboundScanPayload, sameInboundIdentity } from '../utils/inboundIdentity.js'; import { isInboundPhotoSizeError, normalizeInboundPhotoSlots } from '../utils/inboundPhotos.js'; import Panel from '../components/Panel.vue'; import ScanCodeButton from '../components/ScanCodeButton.vue'; import InboundItemCard from '../components/InboundItemCard.vue'; import GoodsManualForm from '../components/GoodsManualForm.vue'
const router = useRouter(), app = useAppStore(), auth = useAuthStore(), items = ref([]), busy = ref(false), submitting = ref(false), notice = ref(''), manualForm = ref(false), pendingBarcode = ref(''), pendingManualData = ref({}), previewUrl = ref(''), scanButton = ref(null), listExpanded = ref(true), meta = ref({}), barcodeDialog = ref(false), barcodeValue = ref(''), barcodeError = ref(''), barcodeInput = ref(null); const draftKey = 'dajin-inbound-draft'; const clientIdKey = 'dajin-inbound-client-id'; const clientFpKey = 'dajin-inbound-client-fp'; const historyKey = 'dajin-inbound-history'; const autoScan = ref(true)
const STEP_LABELS = ['扫码', '货品详情', '拍照', '确认']; const step = ref(1); const active = ref(null)
const isH5 = !isNativeApp() && typeof uni === 'undefined'; const totalQuantity = computed(() => items.value.reduce((s, i) => s + Number(i.quantity || 0), 0)); const totalWeight = computed(() => items.value.reduce((s, i) => s + Number(i.goldWeight || 0) * Number(i.quantity || 0), 0)); const totalAmount = computed(() => items.value.reduce((s, i) => s + Number(i.labelPrice || 0) * Number(i.quantity || 0), 0)); const money = v => Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const purchaseExpected = computed(() => meta.value.inboundType === 'purchase' ? Number(meta.value.expectedQuantity || meta.value.purchaseTotal || 0) : 0)
const typeName = value => ({ purchase: '采购入库', transfer: '调拨入库', return: '退货入库', profit: '盘盈入库' }[String(value || '').toLowerCase()] || '入库')
const storeLabel = computed(() => meta.value.storeName || (meta.value.storeId ? `门店 #${meta.value.storeId}` : '—'))
const sourceRow = computed(() => {
  if (meta.value.inboundType === 'purchase') return ['供应商', meta.value.sourceName || (meta.value.sourceId ? `供应商 #${meta.value.sourceId}` : '—')]
  if (meta.value.inboundType === 'transfer') return ['调出门店', meta.value.sourceName || (meta.value.sourceId ? `门店 #${meta.value.sourceId}` : '—')]
  return null
})
const operatorName = computed(() => auth.user?.real_name || auth.user?.realName || auth.user?.username || '—')
const goldPrice = computed(() => Number(app.primaryGold?.price) || 0)
const goldTypeName = computed(() => String(app.primaryGold?.price_type || app.primaryGold?.priceType || '金价'))
function parseImages(raw) { try { const value = typeof raw === 'string' ? JSON.parse(raw) : raw; return Array.isArray(value) ? value.filter(Boolean).slice(0, 4) : [] } catch { return [] } }
function normalizeDraftItem(item) {
  if (!item || typeof item !== 'object') return item
  const normalized = { ...item, pieceNo: String(item.pieceNo || '').trim(), images: parseImages(item.images), catalogImages: parseImages(item.catalogImages), confirmed: Boolean(item.confirmed), pieces: Array.isArray(item.pieces) ? item.pieces.map(p => p || '') : [] }
  normalized.key = normalized.key || inboundItemKey(normalized)
  if (normalized.pieceNo) normalized.quantity = 1
  return normalized
}
function ensurePieces(item) { if (!item) return; const base = http.defaults.baseURL || ''; const pieces = Array.isArray(item.pieces) ? item.pieces.map(p => (p && typeof p === 'string' && p.startsWith('/') && base && !p.startsWith(base)) ? base + p : (p || '')) : []; item.pieces = normalizeInboundPhotoSlots(item.quantity, pieces) }
function persist() { for (const item of items.value) ensurePieces(item); setStorage(draftKey, JSON.stringify(items.value)) }
function rememberHistory(record) {
  try {
    const cutoffDate = new Date(); cutoffDate.setMonth(cutoffDate.getMonth() - 3); const cutoff = cutoffDate.getTime()
    const rows = JSON.parse(getStorage(historyKey, '[]'))
    const next = Array.isArray(rows) ? rows : []
    const key = record.inbound_id || record.clientRequestId
    const kept = next.filter(row => (row.inbound_id || row.clientRequestId) !== key && new Date(row.create_time || row.createdAt || 0).getTime() >= cutoff)
    kept.unshift({ ...record, createdAt: record.createdAt || Date.now() })
    setStorage(historyKey, JSON.stringify(kept.slice(0, 200)))
  } catch {}
}
function notify(text) { notice.value = text; setTimeout(() => { if (notice.value === text) notice.value = '' }, 2200) }
function enterFlow(item) { ensurePieces(item); active.value = item; step.value = 2; listExpanded.value = false }
function editItem(item) { enterFlow(item) }
function openPhotoFlow(item) { ensurePieces(item); active.value = item; step.value = 3; listExpanded.value = false }
function backToScan() { active.value = null; step.value = 1; listExpanded.value = true }
function goBack() { if (step.value > 1) { if (step.value === 2) backToScan(); else step.value-- } else router.back() }
async function toStep(target) {
  if (target === 4 && active.value && !((active.value.pieces || []).some(Boolean))) {
    if (!(await confirmSubmit('该货品还未拍照，确认跳过照片直接入库吗？'))) return
  }
  step.value = target
}
function setQty(delta) { if (!active.value || active.value.pieceNo) return; active.value.quantity = Math.max(1, Math.floor(Number(active.value.quantity) || 1) + delta); persist() }
function normalizeQty() { if (!active.value) return; active.value.quantity = active.value.pieceNo ? 1 : Math.max(1, Math.floor(Number(active.value.quantity) || 1)); persist() }
function confirmItem() {
  if (!active.value) return
  if (!(Number(active.value.quantity) > 0)) { notify('货品数量必须大于0'); return }
  active.value.confirmed = true
  const name = active.value.name
  persist()
  backToScan()
  notify(`${name} 已加入本批，可继续扫码`)
  if (autoScan.value && typeof uni !== 'undefined' && uni.scanCode) nextTick(() => scanButton.value?.scan())
}
async function handleScan(raw) {
  const parsed = parseInboundScanPayload(raw); const barcode = parsed.barcode; const pieceNo = parsed.pieceNo
  if (!barcode && !pieceNo) {
    if (parsed.name || parsed.categoryName || parsed.goldWeight || parsed.labelPrice) {
      notify('标签未提供条码，将按标签内容建档'); feedback(false); openManualForm('', parsed)
    }
    return
  }
  const existing = items.value.find(item => sameInboundIdentity(item, parsed))
  if (existing) { notify(pieceNo ? '该单件码已在本次入库清单中' : '该货品已在清单中，可修改后重新确认'); feedback(false); enterFlow(existing); return }
  busy.value = true
  try {
    let g
    try { g = await api.goodsByBarcode(barcode, meta.value.storeId, pieceNo) }
    catch (error) {
      const message = error?.response?.data?.message || error?.message || ''
      if (Number(error?.response?.status) === 409 || message.includes('已入库') || message.includes('已登记') || message.includes('重复入库')) { notify(message || '该单件码已入库'); feedback(false); return }
      g = null
    }
    const item = g ? { goodsId: g.goods_id, barcode: g.barcode, pieceNo, name: g.name || g.goods_name, categoryId: g.category_id, categoryName: g.category || '', goldWeight: Number(g.weight || 0), labelPrice: Number(g.sale_price || 0), certificateNo: g.certificate_no || g.certificateNo || '', quantity: 1, images: [], catalogImages: parseImages(g.images), confirmed: false } : null
    if (!item) { notify(parsed.categoryName ? `未匹配到货品，将按标签品类“${parsed.categoryName}”建档` : '未匹配到货品，请手动录入'); feedback(false); openManualForm(barcode, parsed); return }
    item.key = inboundItemKey(item)
    items.value.push(item); persist(); notify('扫码成功'); feedback(true); enterFlow(item)
  } finally { busy.value = false }
}
function openManualForm(barcode, data = {}) { pendingBarcode.value = String(barcode || ''); pendingManualData.value = { ...data }; manualForm.value = true }
function manualScan() { barcodeValue.value = ''; barcodeError.value = ''; barcodeDialog.value = true; nextTick(() => barcodeInput.value?.focus?.()) }
function onScanError(error) { if (!isNativeApp()) { manualScan(); return }; if (!String(error?.message || '').includes('取消')) notify(error?.message || '扫码失败，请检查相机权限') }
function closeBarcodeDialog() { barcodeDialog.value = false }
async function confirmBarcode() { const code = String(barcodeValue.value || '').trim(); if (!code) { barcodeError.value = '请输入条码号'; return }; barcodeDialog.value = false; await handleScan(code) }
function addManual(data) { const item = { ...data, barcode: String(data.barcode || pendingBarcode.value || ''), pieceNo: String(data.pieceNo || pendingManualData.value.pieceNo || '').trim(), quantity: 1, images: [], confirmed: false }; item.key = inboundItemKey(item); items.value.push(item); pendingBarcode.value = ''; pendingManualData.value = {}; persist(); notify('已加入清单'); feedback(true); enterFlow(item) }
function removeItem(item) { items.value = items.value.filter(i => i.key !== item.key); persist(); if (active.value?.key === item.key) backToScan() }
function removePhoto(item, index) { if (Array.isArray(item.pieces) && item.pieces[index] !== undefined) { item.pieces[index] = ''; persist(); notify('照片已删除') } }
async function takePiecePhoto(pi) {
  const item = active.value; if (!item) return
  const grab = async files => {
    const file = files && files[0]; if (!file) return
    try {
      const compressed = await compressImage(file)
      if (!pageCache.current()) throw new Error('账号已切换，请使用原账号继续拍照')
      if (app.offline) { item.pieces[pi] = await cachePhoto(compressed) } else {
        const result = typeof compressed === 'string' ? await uploadImage(compressed, null) : await uploadImage('', compressed)
        const url = result?.data?.url || result?.url || result?.data
        if (!url) throw new Error('照片上传失败')
        item.pieces[pi] = url
      }
      persist()
    } catch (error) {
      if (isInboundPhotoSizeError(error)) { notify(error.message); return }
      try { item.pieces[pi] = await cachePhoto(file); persist(); notify('网络不可用，照片已缓存，联网后自动上传') } catch { notify(error?.message || '照片上传失败，请重试') }
    }
  }
  if (isNativeApp()) {
    try { await grab([await takeNativePhoto()]) }
    catch (error) { if (!/cancel|取消/i.test(error?.message || '')) notify(error?.message || '拍照失败，请检查相机权限') }
  }
  else if (typeof uni !== 'undefined' && uni.chooseImage) { uni.chooseImage({ count: 1, sourceType: ['camera'], success: async r => { await grab(r.tempFilePaths || []) }, fail: () => {} }) }
  else { const input = document.createElement('input'); input.type = 'file'; input.accept = 'image/*'; input.onchange = async () => { await grab([...(input.files || [])]) }; input.click() }
}
function clearPiecePhoto(pi) { const item = active.value; if (!item) return; item.pieces[pi] = ''; persist(); notify('已清除，可重新拍照') }
async function compressImage(source) {
  if (typeof source === 'string' && typeof uni !== 'undefined' && uni.compressImage) {
    let dimensions = {}
    if (uni.getImageInfo) dimensions = await new Promise(resolve => uni.getImageInfo({ src: source, success: resolve, fail: () => resolve({}) }))
    const originalWidth = Number(dimensions.width || 0), originalHeight = Number(dimensions.height || 0), longest = Math.max(originalWidth, originalHeight)
    const scale = longest > 1280 ? 1280 / longest : 1
    const size = originalWidth && originalHeight ? { compressedWidth: Math.round(originalWidth * scale), compressedHeight: Math.round(originalHeight * scale) } : { compressedWidth: 1280 }
    let result = await new Promise((resolve, reject) => uni.compressImage({ src: source, quality: 80, ...size, success: resolve, fail: reject }))
    let path = result.tempFilePath || source
    if (uni.getFileInfo) {
      const info = await new Promise((resolve, reject) => uni.getFileInfo({ filePath: path, success: resolve, fail: reject }))
      if (Number(info.size || 0) > 2 * 1024 * 1024) {
        result = await new Promise((resolve, reject) => uni.compressImage({ src: path, quality: 60, ...size, success: resolve, fail: reject }))
        path = result.tempFilePath || path
        const smaller = await new Promise((resolve, reject) => uni.getFileInfo({ filePath: path, success: resolve, fail: reject }))
        if (Number(smaller.size || 0) > 2 * 1024 * 1024) throw new Error('照片压缩后仍超过2MB，请重拍')
      }
    }
    return path
  }
  if (typeof Blob === 'undefined' || !(source instanceof Blob)) return source
  const bitmap = await new Promise((resolve, reject) => { const image = new Image(); image.onload = () => resolve(image); image.onerror = reject; image.src = URL.createObjectURL(source) })
  const scale = Math.min(1, 1280 / Math.max(bitmap.width, bitmap.height)); const canvas = document.createElement('canvas'); canvas.width = Math.max(1, Math.round(bitmap.width * scale)); canvas.height = Math.max(1, Math.round(bitmap.height * scale)); canvas.getContext('2d').drawImage(bitmap, 0, 0, canvas.width, canvas.height)
  let quality = 0.8; let blob = null
  while (quality >= 0.45) { blob = await new Promise(resolve => canvas.toBlob(resolve, 'image/jpeg', quality)); if (blob && blob.size <= 2 * 1024 * 1024) break; quality -= 0.1 }
  if (!blob) return source
  if (blob.size > 2 * 1024 * 1024) throw new Error('照片压缩后仍超过2MB，请重拍')
  return new File([blob], source.name || `inbound-${Date.now()}.jpg`, { type: 'image/jpeg', lastModified: Date.now() })
}
async function cachePhoto(source) {
  if (typeof source === 'string' && typeof uni !== 'undefined' && uni.saveFile) {
    const result = await new Promise((resolve, reject) => uni.saveFile({ tempFilePath: source, success: resolve, fail: reject }))
    return result.savedFilePath || source
  }
  if (typeof source === 'string') return source
  if (typeof FileReader === 'undefined') return source
  return await new Promise((resolve, reject) => { const reader = new FileReader(); reader.onload = () => resolve(reader.result); reader.onerror = reject; reader.readAsDataURL(source) })
}
function feedback(success) { try { if (typeof uni !== 'undefined' && uni.vibrateShort) uni.vibrateShort({ type: success ? 'light' : 'heavy' }); else if (typeof navigator !== 'undefined' && navigator.vibrate) navigator.vibrate(success ? 50 : 120); playTone(success) } catch {} }
function playTone(success) { if (typeof window === 'undefined') return; const AudioContext = window.AudioContext || window.webkitAudioContext; if (!AudioContext) return; const context = new AudioContext(); const oscillator = context.createOscillator(), gain = context.createGain(); oscillator.frequency.value = success ? 880 : 240; gain.gain.setValueAtTime(0.08, context.currentTime); gain.gain.exponentialRampToValueAtTime(0.001, context.currentTime + 0.12); oscillator.connect(gain); gain.connect(context.destination); oscillator.start(); oscillator.stop(context.currentTime + 0.12); oscillator.onended = () => context.close() }
function previewItem(item, index = 0) { const url = item.pieces?.filter(Boolean)?.[index] || item.pieces?.find(Boolean) || item.images?.[index] || item.catalogImages?.[0]; if (url) previewUrl.value = url }
async function confirmSubmit(message) { if (typeof uni !== 'undefined' && uni.showModal) return await new Promise(resolve => uni.showModal({ title: '确认提交', content: message, success: r => resolve(Boolean(r.confirm)), fail: () => resolve(false) })); return window.confirm(message) }
async function submit() {
  if (items.value.some(item => !(Number(item.quantity) > 0))) { notify('货品数量必须大于0'); return }
  const unconfirmed = items.value.filter(i => !i.confirmed)
  if (unconfirmed.length && !(await confirmSubmit(`有${unconfirmed.length}件货品未完成确认流程，确认直接提交吗？`))) return
  const missing = items.value.filter(i => !((i.pieces || []).some(Boolean))); if (missing.length && !(await confirmSubmit(`有${missing.length}件未拍照，确认提交吗？`))) return
  if (!pageCache.current()) { notify('账号已切换，请重新打开入库页面'); return }
  submitting.value = true
  // 防重号绑定本批清单内容：断网重试同一批沿用旧号（后端幂等去重），换了货品/数量则换新号
  // ——否则第一批进离线队列后，第二批会复用旧号被后端当成重复单吞掉。
  const batchFingerprint = JSON.stringify(items.value.map(i => `${i.pieceNo || i.barcode || i.name}:${i.quantity}:${i.goldWeight || ''}:${i.labelPrice || ''}:${(i.pieces || []).filter(Boolean).length}`))
  let clientRequestId = getStorage(clientIdKey, '')
  if (!clientRequestId || getStorage(clientFpKey, '') !== batchFingerprint) { clientRequestId = `mobile-inbound-${Date.now()}-${Math.random().toString(36).slice(2,8)}`; setStorage(clientIdKey, clientRequestId) }
  setStorage(clientFpKey, batchFingerprint)
  const payload = { ...meta.value, clientRequestId, items: items.value.map(i => { ensurePieces(i); const pieceImages = (i.pieces || []).map(p => p || ''); return { goodsId: i.goodsId, barcode: i.barcode, pieceNo: i.pieceNo || '', pieceNos: i.pieceNo ? [i.pieceNo] : [], name: i.name, categoryId: i.categoryId, categoryName: i.categoryName, parentCategoryName: i.parentCategoryName, goldWeight: i.goldWeight, costPrice: i.costPrice, labelPrice: i.labelPrice, certificateNo: i.certificateNo || '', quantity: i.pieceNo ? 1 : i.quantity, images: pieceImages.filter(Boolean).slice(0, 4), pieceImages } }) }
  try { const result = await api.createInbound(payload); rememberHistory({ ...result, status: result.status || 'COMPLETED', clientRequestId }); setStorage(draftKey, ''); setStorage(clientIdKey, ''); setStorage(clientFpKey, ''); setStorage('dajin-inbound-meta', ''); items.value = []; active.value = null; step.value = 1; pageCache.current() && router.replace(`/inbound/detail/${result.inbound_id || result.inboundId}`) } catch (e) { const networkFailure = app.offline || !e?.response; if (networkFailure) { let queue = []; try { queue = JSON.parse(getStorage('dajin-inbound-queue', '[]')) } catch {} ; if (!queue.some(x => x.clientRequestId === clientRequestId)) queue.push(payload); setStorage('dajin-inbound-queue', JSON.stringify(queue)); rememberHistory({ ...payload, status: 'PENDING', createdAt: Date.now(), itemCount: payload.items.length, total_weight: payload.items.reduce((sum, item) => sum + Number(item.goldWeight || 0) * Number(item.quantity || 0), 0), total_amount: payload.items.reduce((sum, item) => sum + Number(item.labelPrice || 0) * Number(item.quantity || 0), 0) }); if (pageCache.current()) app.pendingInboundCount = queue.length; notify('当前网络不可用，已保存待同步入库单，可在最近记录中手动同步') } else { rememberHistory({ ...payload, status: 'FAILED', createdAt: Date.now(), error: e.message || '入库失败', itemCount: payload.items.length, total_weight: payload.items.reduce((sum, item) => sum + Number(item.goldWeight || 0) * Number(item.quantity || 0), 0), total_amount: payload.items.reduce((sum, item) => sum + Number(item.labelPrice || 0) * Number(item.quantity || 0), 0) }); notify(e.message || '入库失败，请检查填写内容') } } finally { submitting.value = false }
}
onMounted(() => {
  try { meta.value = JSON.parse(getStorage('dajin-inbound-meta', '{}')) || {} } catch { meta.value = {} }
  // A draft is only valid when it belongs to an active preparation flow.
  // This prevents a completed voucher's cached rows from reappearing when
  // the scan page is opened directly later.
  if (!meta.value || !Object.keys(meta.value).length) {
    items.value = []; setStorage(draftKey, '[]')
    // The scan page requires an inbound preparation context (type/store/source).
    // Guide direct visits back to preparation instead of leaving an empty list
    // that looks like the scan or photo action failed.
    router.replace('/inbound/create')
    return
  }
  try { const saved = JSON.parse(getStorage(draftKey, '[]')); if (Array.isArray(saved)) items.value = saved.map(normalizeDraftItem); items.value.forEach(ensurePieces) } catch { items.value = [] }
}); onBeforeUnmount(() => { persist() })
</script>
<style scoped>
.steps{display:flex;position:relative;margin:0 0 var(--s-3)}
.steps::before{content:'';position:absolute;top:13px;left:12.5%;right:12.5%;height:2px;background:var(--line)}
.step{flex:1;display:flex;flex-direction:column;align-items:center;gap:3px;font-size:var(--f-xs);color:var(--ink-3);position:relative;z-index:1}
.step i{width:26px;height:26px;border-radius:50%;background:var(--card);border:2px solid var(--line);color:var(--ink-3);font-style:normal;display:grid;place-items:center;font-weight:600;font-size:var(--f-xs)}
.step.cur{color:var(--gold-deep)}
.step.cur i{background:var(--gold);border-color:var(--gold);color:#fff}
.step.done{color:var(--ok)}
.step.done i{background:#e5f5ea;border-color:#9fd8ae;color:var(--ok)}
.inbound-detail-name{margin:var(--s-2) 0 var(--s-1)}
.match-badge{display:inline-block;background:#e5f5ea;color:var(--ok);font-size:var(--f-xs);padding:2px 8px;border-radius:999px;font-weight:600}
.inbound-facts{margin:var(--s-2) 0;display:grid;grid-template-columns:76px 1fr;gap:var(--s-1) var(--s-2);font-size:var(--f-sm)}
.inbound-facts dt{color:var(--ink-3)}
.inbound-facts dd{margin:0;overflow-wrap:anywhere;line-height:1.4}
.piece-code{font-weight:700;color:var(--gold-deep);overflow-wrap:anywhere}
.identity-tip{margin:var(--s-2) 0;padding:10px 12px;border-left:3px solid var(--gold);background:#fff8ea;color:var(--ink-2);font-size:var(--f-sm)}
.qty-stepper{display:flex;gap:var(--s-1);align-items:center;margin-top:var(--s-1)}
.qty-stepper button{width:44px;height:44px;border:1px solid var(--gold-line);border-radius:var(--r-md);background:var(--card);font-size:var(--f-lg);color:var(--gold-deep)}
.qty-stepper input{width:64px;min-height:44px;text-align:center;border:1px solid #dfe3e8;border-radius:var(--r-md);font-size:var(--f-sm)}
.gold-ref{margin:var(--s-2) 0 0;color:var(--ink-3);font-size:var(--f-xs)}
.photos{display:grid;grid-template-columns:repeat(4,1fr);gap:var(--s-2);margin:var(--s-2) 0}
.ph{position:relative;aspect-ratio:1;border-radius:var(--r-md);overflow:hidden;background:var(--line-soft)}
.ph img{width:100%;height:100%;object-fit:cover;display:block}
.ph i{position:absolute;right:4px;top:4px;width:22px;height:22px;border-radius:50%;background:var(--err);color:#fff;font-style:normal;font-size:14px;line-height:22px;text-align:center;z-index:1}
.ph-add{border:1px dashed var(--gold-line);display:flex;flex-direction:column;align-items:center;justify-content:center;gap:2px;color:var(--gold-deep);background:var(--card);font-size:var(--f-lg);border-radius:var(--r-md)}
.ph-add small{font-size:var(--f-xs)}
.piece-photos{display:grid;grid-template-columns:1fr 1fr;gap:var(--s-2);margin:var(--s-2) 0}
.piece-photo{display:flex;flex-direction:column;gap:4px}
.piece-tag{font-size:var(--f-xs);color:var(--ink-2)}
.piece-empty{font-size:10px;color:#b3a58f}
</style>
