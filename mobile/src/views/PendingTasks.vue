<template>
  <div class="shell">
    <header class="topbar">
      <button class="back" @click="router.back()">‹</button>
      <div><strong>待处理</strong><span class="store">{{ storeName }}</span></div>
      <button class="icon-btn" @click="load">刷新</button>
    </header>
    <main class="content page">
      <section class="form-card summary-card">
        <div><span>未完成订单</span><b>{{ pendingCount }} 笔</b></div>
        <div><span>待取货</span><b>{{ readyCount }} 笔</b></div>
        <div><span>未收尾款</span><b class="due">{{ money(outstandingTotal) }}</b></div>
      </section>
      <p v-if="!loading && !error && rows.length" class="muted small">未结加工单按状态排列，收款与状态确认都在卡片上直接处理。</p>
      <div v-if="loading" class="empty">加载中...</div>
      <div v-else-if="error" class="empty"><span>{{ error }}</span><button class="outline" @click="load">重试</button></div>
      <div v-else-if="!rows.length" class="empty">暂无待处理加工单</div>
      <article v-for="row in rows" :key="row.processing_order_id" class="list-card todo-card">
        <div class="todo-head">
          <b>{{ row.item_name_snapshot || '加工项目' }}</b>
          <span :class="['status-pill', statusClass(row.status)]">{{ statusLabel(row.status) }}</span>
        </div>
        <p>{{ row.customer_name || row.member_name || '散客' }} · {{ row.customer_phone || '—' }}</p>
        <p>{{ row.order_no }}</p>
        <p>应收 {{ money(row.due_amount) }} · 已收 {{ money(row.paid_amount) }} · <b class="due">未收 {{ money(outstanding(row)) }}</b></p>
        <p class="muted">师傅：{{ row.craftsman_name || '未指派' }} · 取货 {{ date(row.pickup_date) }}</p>
        <div class="todo-actions">
          <button v-if="canPay && outstanding(row) > 0" class="outline" @click="openPay(row)">收款</button>
          <button v-if="row.customer_phone" class="outline" @click="call(row.customer_phone)">拨号</button>
          <button v-if="['PROCESSING', 'COMPLETED'].includes(row.status)" class="outline" @click="notify(row)">通知取货</button>
          <button v-if="canManage && String(row.status).toUpperCase() === 'COMPLETED'" class="outline" @click="openPickupPhotos(row)">取货拍照</button>
          <button v-if="canManage && String(row.status).toUpperCase() === 'PENDING' && Number(row.handover) !== 1" class="primary" @click="advance(row)">确认加工</button>
        </div>
        <p v-if="String(row.status).toUpperCase() === 'PENDING' && Number(row.handover) === 1" class="muted small">已转交前台，等待收银端确认加工。</p>
      </article>
    </main>
    <div v-if="paying" class="sheet-mask" @click.self="closePay">
      <section class="sheet">
        <header><strong>加工收款</strong><button class="icon-btn" @click="closePay">×</button></header>
        <div class="sheet-body">
          <div class="pay-head"><span>{{ paying.order_no }}</span><b class="due">未收 {{ money(outstanding(paying)) }}</b></div>
          <div class="form-label">收款类型
            <div class="filter-tabs">
              <button v-for="t in payTypes" :key="t.value" :class="{ active: payForm.paymentType === t.value }" @click="payForm.paymentType = t.value; syncAmount()">{{ t.label }}</button>
            </div>
          </div>
          <label class="form-label">收款金额 *<input v-model="payForm.amount" type="number" min="0" step="0.01" :disabled="payForm.paymentType === 'BALANCE'"/></label>
          <p v-if="payForm.paymentType === 'BALANCE'" class="muted small">尾款金额必须等于未收金额。</p>
          <label class="form-label">支付方式 *
            <select v-model="payForm.payMethod">
              <option v-for="m in payMethods" :key="m.value" :value="m.value">{{ m.label }}</option>
            </select>
          </label>
          <label class="form-label">备注<textarea v-model.trim="payForm.remark" rows="2" placeholder="选填"/></label>
          <p v-if="payError" class="error">{{ payError }}</p>
          <button class="primary full" :disabled="paySaving" @click="submitPay">{{ paySaving ? '提交中...' : '确认收款' }}</button>
        </div>
      </section>
    </div>
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
import { isNativeApp, takeNativePhoto } from '../utils/nativeDevice.js'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth.js'
import { useAppStore } from '../stores/app.js'
import { api, http } from '../api/request.js'
import { uploadImage } from '../api/upload.js'
const router = useRouter(), auth = useAuthStore(), app = useAppStore()
const storeName = computed(() => auth.user?.store_name || auth.user?.storeName || '默认门店')
const canManage = computed(() => ['ADMIN', 'MANAGER'].includes(auth.role))
const canPay = computed(() => ['ADMIN', 'MANAGER', 'CASHIER'].includes(auth.role))
const rows = ref([]), loading = ref(false), error = ref('')
const paying = ref(null), paySaving = ref(false), payError = ref('')
const photoOrder = ref(null), photoBusy = ref(false), photoError = ref('')
const payForm = reactive({ paymentType: 'BALANCE', amount: '', payMethod: 'CASH', remark: '' })
const payTypes = [{ value: 'DEPOSIT', label: '定金' }, { value: 'BALANCE', label: '尾款' }]
const payMethods = [{ value: 'CASH', label: '现金' }, { value: 'WECHAT', label: '微信' }, { value: 'ALIPAY', label: '支付宝' }, { value: 'BANK', label: '银行卡' }]
const STATUS_ORDER = { PENDING: 0, PROCESSING: 1, COMPLETED: 2 }
const money = v => `¥${Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
const outstanding = row => Math.max(Number(row?.due_amount || 0) - Number(row?.paid_amount || 0), 0)
const date = v => (v ? String(v).slice(0, 10) : '—')
const statusLabel = v => ({ PENDING: '待加工', PROCESSING: '加工中', COMPLETED: '已完成待取货', PICKED_UP: '已取货' }[String(v || '').toUpperCase()] || '未知')
const statusClass = v => String(v || '').toLowerCase()
const pendingCount = computed(() => rows.value.filter(row => ['PENDING', 'PROCESSING'].includes(String(row.status))).length)
const readyCount = computed(() => rows.value.filter(row => String(row.status) === 'COMPLETED').length)
const outstandingTotal = computed(() => rows.value.reduce((sum, row) => sum + outstanding(row), 0))
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
  } catch (e) { window.alert(e?.message || '状态更新失败') }
}
async function notify(row) {
  try { await api.processingNotify(row.processing_order_id); window.alert(`已记录通知 ${row.customer_name || '客户'} 取货`) } catch (e) { window.alert(e?.message || '通知失败') }
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
function openPay(row) {
  paying.value = row; payError.value = ''; payForm.paymentType = 'BALANCE'; payForm.payMethod = 'CASH'; payForm.remark = ''
  syncAmount()
}
function syncAmount() { payForm.amount = payForm.paymentType === 'BALANCE' ? outstanding(paying.value).toFixed(2) : '' }
function closePay() { paying.value = null; paySaving.value = false }
async function submitPay() {
  payError.value = ''
  const amount = Number(payForm.amount)
  if (!(amount > 0)) { payError.value = '请填写大于 0 的收款金额'; return }
  if (payForm.paymentType === 'BALANCE' && Math.abs(amount - outstanding(paying.value)) > 0.001) { payError.value = '尾款金额必须等于未收金额'; return }
  paySaving.value = true
  try {
    await api.processingPay(paying.value.processing_order_id, {
      paymentType: payForm.paymentType, amount, payMethod: payForm.payMethod,
      clientRequestId: window.crypto?.randomUUID ? window.crypto.randomUUID() : `pay-${Date.now()}-${Math.random().toString(16).slice(2)}`,
      ...(payForm.remark ? { remark: payForm.remark } : {})
    })
    closePay(); await load()
  } catch (e) { payError.value = e?.message || '收款失败' } finally { paySaving.value = false }
}
onMounted(load)
watch(()=>app.eventVersion,()=>{if(['PROCESSING_ORDER_CREATED','PROCESSING_ORDER_UPDATED','PROCESSING_HANDOVER'].includes(app.lastEventType))load()})
</script>
<style scoped>
.summary-card{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px;background:#fff;border:1px solid var(--line);border-radius:8px;padding:12px;text-align:center}
.summary-card span{display:block;color:var(--ink-3);font-size:11px}
.summary-card b{display:block;margin-top:4px;font-size:15px}
.due{color:#c0392b}
.todo-card{display:block}
.todo-head{display:flex;align-items:center;justify-content:space-between;gap:8px}
.todo-head b{font-size:var(--f-sm);min-width:0;overflow-wrap:anywhere;line-height:1.4}
.todo-card p{margin:5px 0 0;color:var(--ink-2);font-size:var(--f-xs);overflow-wrap:break-word}
.status-pill{padding:3px 7px;border-radius:12px;font-size:11px;white-space:nowrap}
.status-pill.pending{background:#fff4df;color:#a26712}
.status-pill.processing{background:#eef6ff;color:#245b87}
.status-pill.completed{background:#e7f6ed;color:#18864b}
.todo-actions{display:flex;flex-wrap:wrap;gap:8px;margin-top:10px}
.todo-actions button{flex:1;min-width:84px;padding:0 8px}
.sheet-mask{position:fixed;inset:0;background:rgba(15,20,28,.45);display:flex;align-items:flex-end;justify-content:center;z-index:60}
.sheet{width:100%;max-width:560px;max-height:88vh;display:flex;flex-direction:column;background:#fff;border-radius:14px 14px 0 0;overflow:hidden}
.sheet>header{display:flex;align-items:center;justify-content:space-between;gap:8px;padding:12px 14px;border-bottom:1px solid var(--line)}
.sheet>header strong{font-size:15px}
.sheet-body{padding:14px;overflow-y:auto;-webkit-overflow-scrolling:touch}
.pickup-photo-list{display:flex;flex-wrap:wrap;gap:8px;margin:10px 0 14px}
.pickup-photo-list img,.pickup-photo-list .add-photo{width:72px;height:72px;border-radius:8px;border:1px solid var(--line);object-fit:cover}
.pickup-photo-list .add-photo{display:flex;align-items:center;justify-content:center;border-style:dashed;color:var(--gold-deep);font-size:24px;cursor:pointer}
.pay-head{display:flex;align-items:center;justify-content:space-between;gap:8px;margin-bottom:12px;padding:10px 12px;background:var(--gold-soft);border-radius:8px;font-size:13px}
.form-label{display:block;margin-bottom:10px;font-size:13px;color:var(--ink-2)}
.form-label input,.form-label select,.form-label textarea{display:block;width:100%;margin-top:5px;min-height:42px;border:1px solid #dfe3e8;border-radius:8px;padding:8px 10px;font-size:14px;color:var(--ink);background:#fff}
.form-label input:disabled{background:#f5f6f8;color:var(--ink-3)}
.filter-tabs{margin:5px 0 0}
.error{margin:0 0 10px;color:#c0392b;font-size:12px}
</style>
