<template>
  <div class="shell">
    <DesignHeader title="待处理" :subtitle="storeName" :back="true" @back="router.back()"><button class="icon-btn" @click="load">刷新</button></DesignHeader>
    <main class="content page">
      <section class="form-card summary-card">
        <div><span>未完成订单</span><b>{{ pendingCount }} 笔</b></div>
        <div><span>待取货</span><b>{{ readyCount }} 笔</b></div>
        <div><span>未收尾款</span><b class="due">{{ money(outstandingTotal) }}</b></div>
        <div><span>待返款</span><b class="due">{{ money(refundOutstandingTotal) }}</b></div>
      </section>
      <p v-if="!loading && !error && rows.length" class="muted small">未结加工单按状态排列，款项由收银端处理。</p>
      <EmptyState v-if="loading" title="加载中..." />
      <EmptyState v-else-if="error" title=""><span>{{ error }}</span><button class="outline" @click="load">重试</button></EmptyState>
      <EmptyState v-else-if="!rows.length" title="暂无待处理加工单" />
      <article v-for="row in rows" :key="row.processing_order_id" class="list-card todo-card">
        <div class="todo-head">
          <b>{{ row.item_name_snapshot || '加工项目' }}</b>
          <span :class="['status-pill', statusClass(row.status)]">{{ statusLabel(row.status) }}</span>
        </div>
        <p>{{ row.customer_name || row.member_name || '散客' }} · {{ row.customer_phone || '—' }}</p>
        <p>{{ row.order_no }}</p>
        <p>整单应收 {{ money(row.settlement_due_amount ?? row.due_amount) }} · 已收 {{ money(row.actual_paid_amount ?? row.paid_amount) }} · <b class="due">未收 {{ money(outstanding(row)) }}（尾款）</b> · 待返款 {{ money(refundOutstanding(row)) }}</p>
        <p class="muted">师傅：{{ row.craftsman_name || '未指派' }} · 取货 {{ date(row.pickup_date) }}</p>
        <div class="todo-actions">
          <button v-if="row.customer_phone" class="outline" @click="call(row.customer_phone)">拨号</button>
          <button v-if="['PROCESSING', 'COMPLETED'].includes(row.status)" class="outline" @click="notify(row)">通知取货</button>
          <button v-if="canRefund && refundOutstanding(row) > 0" class="outline" @click="refund(row)">登记返款</button>
          <button v-if="canManage && String(row.status).toUpperCase() === 'COMPLETED'" class="outline" @click="openPickupPhotos(row)">取货拍照</button>
          <button v-if="canManage && String(row.status).toUpperCase() === 'PENDING' && Number(row.handover) !== 1" class="primary" @click="advance(row)">确认加工</button>
        </div>
        <p v-if="String(row.status).toUpperCase() === 'PENDING' && Number(row.handover) === 1" class="muted small">已转交前台，等待收银端确认加工。</p>
      </article>
    </main>
    <div v-if="photoOrder" class="sheet-mask" @click.self="closePickupPhotos">
      <section class="sheet">
        <header><strong>取货照片</strong><button class="icon-btn" @click="closePickupPhotos">×</button></header>
        <div class="sheet-body">
          <p class="muted small">{{ photoOrder.order_no }} · 确认客户取货前请先拍照留档。</p>
          <div class="pickup-photo-list">
            <img v-for="(url, i) in photoOrder.pickup_photos" :key="`${url}-${i}`" :src="photoUrl(url)" alt="取货照片" @click="previewPhoto(url)"/>
            <label v-if="photoOrder.pickup_photos.length < 6" class="add-photo" @click="nativePickupPhoto($event)">＋<input class="pickup-photo-input" type="file" accept="image/*" capture="environment" multiple hidden @change="addPickupPhotos($event)"/></label>
          </div>
          <p v-if="photoError" class="error">{{ photoError }}</p>
          <button class="primary full" :disabled="photoBusy" @click="closePickupPhotos">{{ photoBusy ? '上传中...' : '完成' }}</button>
        </div>
      </section>
    </div>
  </div>
</template>
<script setup>
import { useToast } from '../composables/useToast.js'
const { toast } = useToast()

import EmptyState from '../components/EmptyState.vue'

import DesignHeader from '../components/DesignHeader.vue'

import { isNativeApp, takeNativePhoto } from '../utils/nativeDevice.js'
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth.js'
import { useAppStore } from '../stores/app.js'
import { api, http } from '../api/request.js'
import { uploadImage } from '../api/upload.js'
const router = useRouter(), auth = useAuthStore(), app = useAppStore()
const storeName = computed(() => auth.user?.store_name || auth.user?.storeName || '默认门店')
const canManage = computed(() => ['ADMIN', 'MANAGER'].includes(auth.role))
const canRefund = computed(() => ['ADMIN', 'MANAGER', 'FRONT', 'CASHIER'].includes(auth.role))
const rows = ref([]), loading = ref(false), error = ref('')
const photoOrder = ref(null), photoBusy = ref(false), photoError = ref('')
const STATUS_ORDER = { PENDING: 0, PROCESSING: 1, COMPLETED: 2 }
const money = v => `¥${Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
const outstanding = row => row?.tail_due_amount != null ? Math.max(Number(row.tail_due_amount || 0), 0) : Math.max(Number(row?.due_amount || 0) - Number(row?.paid_amount || 0), 0)
const refundOutstanding = row => row?.refund_outstanding != null ? Math.max(Number(row.refund_outstanding || 0), 0) : Math.max(Number(row?.refund_amount || 0) - Number(row?.refund_paid_amount || 0), 0)
const date = v => (v ? String(v).slice(0, 10) : '—')
const statusLabel = v => ({ PENDING: '待加工', PROCESSING: '加工中', COMPLETED: '已完成待取货', PICKED_UP: '已取货' }[String(v || '').toUpperCase()] || '未知')
const statusClass = v => String(v || '').toLowerCase()
const pendingCount = computed(() => rows.value.filter(row => ['PENDING', 'PROCESSING'].includes(String(row.status))).length)
const readyCount = computed(() => rows.value.filter(row => String(row.status) === 'COMPLETED').length)
const outstandingTotal = computed(() => rows.value.reduce((sum, row) => sum + outstanding(row), 0))
const refundOutstandingTotal = computed(() => rows.value.reduce((sum, row) => sum + refundOutstanding(row), 0))
async function load() {
  loading.value = true; error.value = ''
  try {
    const data = await api.processingOrders({})
    const list = Array.isArray(data) ? data : data?.records || []
    rows.value = list.filter(row => String(row.status) !== 'PICKED_UP')
      .sort((a, b) => (STATUS_ORDER[String(a.status)] ?? 9) - (STATUS_ORDER[String(b.status)] ?? 9) || String(b.create_time || '').localeCompare(String(a.create_time || '')))
  } catch (e) { error.value = e?.message || '待处理加载失败' } finally { loading.value = false }
}
async function advance(row) {
  if (!window.confirm(`确认将 ${row.order_no}「确认加工」？`)) return
  try {
    await api.processingStatus(row.processing_order_id, 'PROCESSING')
    await load()
  } catch (e) { toast(e?.message || '状态更新失败') }
}
async function notify(row) {
  try { await api.processingNotify(row.processing_order_id); toast(`已记录通知 ${row.customer_name || '客户'} 取货`) } catch (e) { toast(e?.message || '通知失败') }
}
async function refund(row) {
  const amount = Number(window.prompt(`请输入返款金额（待返 ${refundOutstanding(row).toFixed(2)} 元）`, refundOutstanding(row).toFixed(2)))
  if (!Number.isFinite(amount) || amount <= 0 || amount > refundOutstanding(row)) return
  const payMethod = window.prompt('请输入返款方式（如 CASH / WECHAT / ALIPAY）', 'CASH')
  if (!payMethod) return
  try {
    const result = await api.processingRefund(row.processing_order_id, { amount, payMethod, clientRequestId: `${Date.now()}-${Math.random()}` })
    toast(result?.approvalRequired ? '返款超过审批上限，已提交审批' : '客户返款已登记')
    await load()
  } catch (e) { toast(e?.message || '返款失败') }
}
function parsePhotos(value) {
  if (Array.isArray(value)) return value.filter(Boolean).map(String)
  try { const list = JSON.parse(value || '[]'); return Array.isArray(list) ? list.filter(Boolean).map(String) : [] } catch { return [] }
}
function photoUrl(url) { return String(url || '').startsWith('/') ? `${http.defaults.baseURL}${url}` : url }
function openPickupPhotos(row) {
  photoError.value = ''
  photoOrder.value = { ...row, pickup_photos: parsePhotos(row.pickup_photos) }
}
function closePickupPhotos() { photoOrder.value = null; photoBusy.value = false; photoError.value = '' }
function previewPhoto(url) { if (url) window.open(photoUrl(url), '_blank') }
async function nativePickupPhoto(event) {
  if (!isNativeApp()) return
  event.preventDefault()
  try { await addPickupPhotos({ target: { files: [await takeNativePhoto()], value: '' } }) }
  catch (e) { if (!/cancel|取消/i.test(e?.message || '')) photoError.value = e?.message || '拍照失败，请检查相机权限' }
}
async function addPickupPhotos(event) {
  const files = [...(event.target.files || [])]
  event.target.value = ''
  if (!files.length || !photoOrder.value || photoBusy.value) return
  photoBusy.value = true; photoError.value = ''
  try {
    const urls = []
    for (const file of files.slice(0, 6 - photoOrder.value.pickup_photos.length)) {
      const result = await uploadImage('', file, { bizType: 'processing', orderNo: photoOrder.value.order_no })
      const url = typeof result === 'string' ? result : result?.data?.url || result?.url || result?.data
      if (url) urls.push(String(url))
    }
    if (urls.length) {
      const updated = await api.processingPhotos(photoOrder.value.processing_order_id, { type: 'pickup', urls })
      photoOrder.value = { ...photoOrder.value, ...updated, pickup_photos: parsePhotos(updated?.pickup_photos) }
      await load()
    }
  } catch (e) { photoError.value = e?.message || '照片上传失败' } finally { photoBusy.value = false }
}
function call(phone) { if (typeof uni !== 'undefined') uni.makePhoneCall({ phoneNumber: String(phone) }); else window.location.href = `tel:${phone}` }
onMounted(load)
watch(()=>app.eventVersion,()=>{if(['PROCESSING_ORDER_CREATED','PROCESSING_ORDER_UPDATED','PROCESSING_HANDOVER'].includes(app.lastEventType))load()})
</script>
<style scoped>
.summary-card{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px;background:var(--card);border:1px solid var(--line);border-radius:var(--r-md);padding:12px;text-align:center}
.summary-card span{display:block;color:var(--ink-3);font-size:11px}
.summary-card b{display:block;margin-top:4px;font-size:15px}
.due{color:var(--err)}
.todo-card{display:block}
.todo-head{display:flex;align-items:center;justify-content:space-between;gap:8px}
.todo-head b{font-size:var(--f-sm);min-width:0;overflow-wrap:anywhere;line-height:1.4}
.todo-card p{margin:5px 0 0;color:var(--ink-2);font-size:var(--f-xs);overflow-wrap:break-word}
.status-pill{padding:3px 7px;border-radius:12px;font-size:11px;white-space:nowrap}
.status-pill.pending{background:var(--gold-soft);color:var(--gold-deep)}
.status-pill.processing{background:var(--gold-soft);color:var(--ink-2)}
.status-pill.completed{background:var(--ok-soft);color:var(--ok)}
.todo-actions{display:flex;flex-wrap:wrap;gap:8px;margin-top:10px}
.todo-actions button{flex:1;min-width:84px;padding:0 8px}
.sheet-mask{position:fixed;inset:0;background:var(--overlay);display:flex;align-items:flex-end;justify-content:center;z-index:60}
.sheet{width:100%;max-width:560px;max-height:88vh;display:flex;flex-direction:column;background:var(--card);border-radius:14px 14px 0 0;overflow:hidden}
.sheet>header{display:flex;align-items:center;justify-content:space-between;gap:8px;padding:12px 14px;border-bottom:1px solid var(--line)}
.sheet>header strong{font-size:15px}
.sheet-body{padding:14px;overflow-y:auto;-webkit-overflow-scrolling:touch}
.pickup-photo-list{display:flex;flex-wrap:wrap;gap:8px;margin:10px 0 14px}
.pickup-photo-list img,.pickup-photo-list .add-photo{width:72px;height:72px;border-radius:var(--r-md);border:1px solid var(--line);object-fit:cover}
.pickup-photo-list .add-photo{display:flex;align-items:center;justify-content:center;border-style:dashed;color:var(--gold-deep);font-size:24px;cursor:pointer}
.error{margin:0 0 10px;color:var(--err);font-size:12px}
</style>
