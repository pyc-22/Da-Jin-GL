<template>
  <div class="shell">
    <header class="topbar"><button class="back" @click="router.back()">‹</button><div class="check-head"><strong>扫码盘点</strong><small>{{ meta.scopeName || '当前范围' }}</small></div><button class="outline" @click="router.push('/stock-check/history')">记录</button></header>
    <main class="content page check-page check-scan-page">
      <div v-if="notice" class="scan-notice">{{ notice }}</div>
      <div class="check-progress-card">
        <div><span>盘点进度</span><b>{{ countedCount }}/{{ items.length }}</b></div>
        <div class="check-progress-track"><i :style="{ width: progress + '%' }"></i></div>
        <div class="check-summary-row"><span>系统总数 <b>{{ number(systemTotal) }}</b></span><span>实盘总数 <b>{{ number(actualTotal) }}</b></span><span>总差异 <b :class="diffClass(diffTotal)">{{ signed(diffTotal) }}</b></span></div>
      </div>

      <div class="scan-toolbar check-scan-toolbar">
        <ScanCodeButton ref="scanButton" :disabled="busy" label="扫码盘点" @scan="handleScan" @scan-request="manualScan" @scan-error="notify('扫码未完成，请重试')" />
        <button class="outline" @click="manualScan">输入条码</button>
      </div>

      <div class="check-list-tools">
        <div class="filter-tabs"><button v-for="option in filters" :key="option.value" :class="{ active: filter === option.value }" @click="filter = option.value">{{ option.label }}</button></div>
        <input v-model.trim="keyword" placeholder="筛选名称或条码" />
      </div>
      <div v-if="!visibleItems.length" class="empty">当前筛选下暂无商品</div>
      <div v-else class="check-item-list"><StockCheckItemCard v-for="item in visibleItems" :key="item.key" :item="item" @change="changeActual" /></div>
      <div class="check-submit-spacer"></div>
    </main>
    <div class="check-submit-bar"><div><span>{{ uncountedCount ? `还有 ${uncountedCount} 件未扫码` : '本范围已全部确认' }}</span><b :class="diffClass(diffTotal)">差异 {{ signed(diffTotal) }}</b></div><button v-permission="'stock:check:submit'" class="primary" :disabled="!items.length || submitting" @click="submit">{{ submitting ? '提交中...' : '提交审批' }}</button></div>

    <div v-if="barcodeDialog" class="mobile-modal" @click.self="closeBarcodeDialog"><div class="mobile-modal-card barcode-dialog"><div class="modal-head"><h3>手动输入条码</h3><button class="modal-close" aria-label="关闭" @click="closeBarcodeDialog">×</button></div><p class="muted small">输入商品标签上的条码编号</p><input ref="barcodeInput" v-model.trim="barcodeValue" class="barcode-input" autocomplete="off" placeholder="请输入条码号" @keyup.enter="confirmBarcode"/><p v-if="barcodeError" class="error">{{ barcodeError }}</p><div class="action-row"><button class="outline" @click="closeBarcodeDialog">取消</button><button class="primary" :disabled="!barcodeValue" @click="confirmBarcode">确认盘点</button></div></div></div>
  </div>
</template>

<script setup>
const pageCache = scopedStorage()
const getStorage = pageCache.get, setStorage = pageCache.set
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api/request.js'
import { scopedStorage } from '../utils/storage.js'
import { parseInboundScanPayload } from '../utils/inboundIdentity.js'
import ScanCodeButton from '../components/ScanCodeButton.vue'
import StockCheckItemCard from '../components/StockCheckItemCard.vue'

const router = useRouter()
const meta = ref({})
const items = ref([])
const busy = ref(false)
const submitting = ref(false)
const notice = ref('')
const scanButton = ref(null)
const filter = ref('counted')
const keyword = ref('')
const barcodeDialog = ref(false)
const barcodeValue = ref('')
const barcodeError = ref('')
const barcodeInput = ref(null)
const filters = [{ value: 'counted', label: '已盘' }, { value: 'different', label: '有差异' }, { value: 'uncounted', label: '未盘' }, { value: 'all', label: '全部' }]
const draftKey = 'dajin-stock-check-draft'
const metaKey = 'dajin-stock-check-meta'
const clientKey = 'dajin-stock-check-client-id'

const countedCount = computed(() => items.value.filter(item => item.counted).length)
const uncountedCount = computed(() => items.value.length - countedCount.value)
const systemTotal = computed(() => items.value.reduce((sum, item) => sum + Number(item.systemStock || 0), 0))
const actualTotal = computed(() => items.value.reduce((sum, item) => sum + Number(item.actual || 0), 0))
const diffTotal = computed(() => Number((actualTotal.value - systemTotal.value).toFixed(3)))
const progress = computed(() => items.value.length ? Math.round(countedCount.value / items.value.length * 100) : 0)
const visibleItems = computed(() => {
  const word = keyword.value.toLowerCase()
  return items.value.filter(item => {
    const diff = Number(item.actual || 0) - Number(item.systemStock || 0)
    if (filter.value === 'counted' && !item.counted) return false
    if (filter.value === 'uncounted' && item.counted) return false
    if (filter.value === 'different' && (!item.counted || diff === 0)) return false
    return !word || String(item.name || '').toLowerCase().includes(word) || String(item.barcode || '').toLowerCase().includes(word)
  })
})
const number = value => Number(value || 0).toLocaleString('zh-CN', { maximumFractionDigits: 3 })
const signed = value => `${Number(value) > 0 ? '+' : ''}${number(value)}`
const diffClass = value => Number(value) > 0 ? 'check-profit' : Number(value) < 0 ? 'check-loss' : 'check-even'

function persist() { setStorage(draftKey, JSON.stringify(items.value)) }
function notify(text) { notice.value = text; setTimeout(() => { if (notice.value === text) notice.value = '' }, 2400) }
function feedback() { try { if (typeof uni !== 'undefined' && uni.vibrateShort) uni.vibrateShort({ type: 'light' }); else navigator?.vibrate?.(50) } catch {} }
function changeActual(item, actual, counted) { item.actual = actual; item.counted = counted; persist() }
async function handleScan(raw) {
  const parsed = parseInboundScanPayload(raw)
  const barcode = parsed.barcode
  const pieceNo = parsed.pieceNo
  if ((!barcode && !pieceNo) || busy.value) return
  busy.value = true
  try {
    let item
    if (pieceNo) {
      const repeated = items.value.some(row => Array.isArray(row.scannedPieceNos) && row.scannedPieceNos.includes(pieceNo))
      if (repeated) { notify('该单件码已盘点，本次不重复计数'); return }
      let piece
      try { piece = await api.goodsByPieceNo(pieceNo) }
      catch (error) { notify(error?.message || '未找到该单件码'); return }
      item = items.value.find(row => Number(row.goodsId) === Number(piece.goods_id))
      if (item) {
        if (!Array.isArray(item.scannedPieceNos)) item.scannedPieceNos = []
        item.scannedPieceNos.push(pieceNo)
      }
    } else item = items.value.find(row => String(row.barcode) === barcode)
    if (!item) {
      try {
        const goods = barcode ? await api.goodsByBarcode(barcode) : null
        notify(goods ? '该商品不在本次盘点范围内' : '未找到商品')
      } catch { notify('未找到该条码商品') }
      return
    }
    item.actual = Number((Number(item.actual || 0) + 1).toFixed(3))
    item.counted = true
    filter.value = 'counted'
    keyword.value = ''
    persist()
    feedback()
    notify(`${item.name} 实盘数 ${number(item.actual)}${pieceNo ? ` · ${pieceNo}` : ''}`)
    if (typeof uni !== 'undefined' && uni.scanCode) nextTick(() => scanButton.value?.scan())
  } finally { busy.value = false }
}
function manualScan() { barcodeValue.value = ''; barcodeError.value = ''; barcodeDialog.value = true; nextTick(() => barcodeInput.value?.focus?.()) }
function closeBarcodeDialog() { barcodeDialog.value = false }
async function confirmBarcode() {
  if (!barcodeValue.value) { barcodeError.value = '请输入条码号'; return }
  const value = barcodeValue.value
  barcodeDialog.value = false
  await handleScan(value)
}
async function confirmSubmit(message) {
  if (typeof uni !== 'undefined' && uni.showModal) return new Promise(resolve => uni.showModal({ title: '确认提交', content: message, success: result => resolve(Boolean(result.confirm)), fail: () => resolve(false) }))
  return window.confirm(message)
}
async function submit() {
  const message = uncountedCount.value
    ? `还有${uncountedCount.value}件未扫码，系统将按实盘数量0提交。确认提交审批吗？`
    : `本次盘点差异为${signed(diffTotal.value)}，确认提交审批吗？`
  if (!(await confirmSubmit(message))) return
  if (!pageCache.current()) { notify('账号已切换，请重新打开盘点页面'); return }
  submitting.value = true
  let clientRequestId = getStorage(clientKey, '')
  if (!clientRequestId) { clientRequestId = `mobile-check-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`; setStorage(clientKey, clientRequestId) }
  try {
    const result = await api.createStockCheck({
      scopeType: meta.value.scopeType,
      scopeId: meta.value.scopeId,
      remark: meta.value.remark || '',
      clientRequestId,
      rows: items.value.map(item => ({ goodsId: item.goodsId, actual: Number(item.actual || 0) }))
    })
    setStorage(draftKey, '')
    setStorage(metaKey, '')
    setStorage(clientKey, '')
    if (pageCache.current()) router.replace(`/stock-check/detail/${result.check_id || result.checkId}`)
  } catch (e) { notify(e.message || '盘点提交失败，清单已保留') }
  finally { submitting.value = false }
}
onMounted(() => {
  try { meta.value = JSON.parse(getStorage(metaKey, '{}')) || {}; items.value = JSON.parse(getStorage(draftKey, '[]')) || []; items.value.forEach(item => { if (!Array.isArray(item.scannedPieceNos)) item.scannedPieceNos = [] }) }
  catch { meta.value = {}; items.value = [] }
  if (!meta.value.scopeType || !Array.isArray(items.value) || !items.value.length) router.replace('/stock-check/create')
})
onBeforeUnmount(persist)
</script>
