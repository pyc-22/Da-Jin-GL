<template>
  <div class="shell">
    <header class="topbar">
      <button class="back" @click="router.back()">‹</button>
      <div><strong>加工订单</strong><span class="store">{{ storeName }}</span></div>
      <button class="icon-btn" @click="load">刷新</button>
    </header>
    <main class="content page">
      <section class="form-card">
        <div class="search">
          <input v-model.trim="keyword" placeholder="客户姓名 / 手机号 / 加工单号" @keyup.enter="load"/>
          <button class="primary" @click="load">查询</button>
        </div>
        <div class="filter-tabs">
          <button v-for="s in statuses" :key="s.key" :class="{ active: status === s.key }" @click="status = s.key; load()">{{ s.label }}</button>
        </div>
        <button class="primary full" @click="openCreate">＋ 开加工单</button>
        <p v-if="lateCount" class="muted small">有 {{ lateCount }} 笔加工单已超过预计取货日期，请及时联系客户。</p>
      </section>
      <div v-if="loading" class="empty">加载中...</div>
      <div v-else-if="error" class="empty"><span>{{ error }}</span><button class="outline" @click="load">重试</button></div>
      <div v-else-if="!orders.length" class="empty">{{ keyword || status ? '没有符合条件的加工单' : '暂无加工订单' }}</div>
      <article v-for="order in orders" :key="order.processing_order_id" class="list-card processing-card" @click="openDetail(order)">
        <div class="processing-main">
          <div class="processing-title">
            <b>{{ order.item_name_snapshot || '加工项目' }}</b>
            <span :class="['status-pill', statusClass(order.status)]">{{ Number(order.handover) === 1 && String(order.status).toUpperCase() === 'PENDING' ? '已转交前台' : statusLabel(order.status) }}</span>
          </div>
          <p>{{ order.customer_name || order.member_name || '散客' }} · {{ order.customer_phone || '—' }}</p>
          <p>{{ order.order_no }}</p>
          <p>{{ order.quantity || 1 }} 件 · 工费 {{ money(order.labor_fee) }} · 应收 {{ money(order.due_amount) }}</p>
          <p class="muted">师傅：{{ order.craftsman_name || '未指派' }} · 取货 {{ date(order.pickup_date) }}</p>
        </div>
        <div class="timeline">
          <div v-for="step in steps" :key="step.key" :class="['timeline-step', { done: step.done(order.status), current: step.current(order.status) }]"><i></i><span>{{ step.label }}</span></div>
        </div>
      </article>
    </main>
    <div v-if="detail" class="sheet-mask" @click.self="closeDetail">
      <section class="sheet">
        <header><strong>加工单详情</strong><button class="icon-btn" @click="closeDetail">×</button></header>
        <div class="sheet-body">
          <div v-if="detailLoading" class="empty">加载中...</div>
          <template v-else>
            <div class="detail-head">
              <b>{{ detail.item_name_snapshot || '加工项目' }}</b>
              <span :class="['status-pill', statusClass(detail.status)]">{{ statusLabel(detail.status) }}</span>
            </div>
            <div class="timeline">
              <div v-for="step in steps" :key="step.key" :class="['timeline-step', { done: step.done(detail.status), current: step.current(detail.status) }]"><i></i><span>{{ step.label }}</span></div>
            </div>
            <div class="detail-grid">
              <div><span>加工单号</span><b>{{ detail.order_no }}</b></div>
              <div><span>客户</span><b>{{ detail.customer_name || detail.member_name || '散客' }}</b></div>
              <div><span>联系电话</span><b>{{ detail.customer_phone || '—' }}</b></div>
              <div><span>数量</span><b>{{ detail.quantity || 1 }} 件</b></div>
              <div><span>单价工费</span><b>{{ money(detail.unit_labor_fee) }}</b></div>
              <div><span>工费合计</span><b>{{ money(detail.labor_fee) }}</b></div>
              <div><span>旧金克重</span><b>{{ grams(detail.old_gold_weight) }}</b></div>
              <div><span>旧金成色</span><b>{{ fineness(detail.old_gold_fineness) }}</b></div>
              <div v-if="Number(detail.store_gold_weight) > 0"><span>店供金料</span><b>{{ Number(detail.store_gold_weight).toFixed(3) }}g · {{ Number(detail.store_gold_price).toFixed(2) }}/g</b></div>
              <div v-if="Number(detail.store_gold_weight) > 0"><span>补金金额</span><b>{{ money(detail.store_gold_amount) }}</b></div>
              <div v-if="detail.finished_weight != null"><span>成品实重</span><b>{{ grams(detail.finished_weight) }}{{ detail.finished_fineness ? ' · ' + fineness(detail.finished_fineness) : '' }}</b></div>
              <div v-if="detail.loss_weight != null"><span>损耗{{ detail.loss_over ? '（超标）' : '' }}</span><b :style="detail.loss_over ? 'color:#c0392b' : ''">{{ grams(detail.loss_weight) }}{{ detail.loss_permille != null ? ' · ' + detail.loss_permille + '‰' : '' }}</b></div>
              <div><span>剩余旧料</span><b>{{ handlingLabel(detail.residual_gold_handling) }}</b></div>
              <div v-if="detail.residual_gold_handling === 'STORE_DEDUCT'"><span>旧料类型</span><b>{{ detail.residual_material_type || '—' }}</b></div>
              <div v-if="detail.residual_gold_handling === 'STORE_DEDUCT'"><span>旧料克重/成色</span><b>{{ grams(detail.residual_gold_weight) }} · {{ fineness(detail.residual_gold_fineness) }}</b></div>
              <div v-if="detail.residual_gold_handling === 'STORE_DEDUCT'"><span>留店抵扣</span><b>{{ money(detail.residual_gold_deduction) }}</b></div>
              <div><span>应收</span><b>{{ money(detail.due_amount) }}</b></div>
              <div><span>已收</span><b>{{ money(detail.paid_amount) }}</b></div>
              <div><span>未收</span><b>{{ money(outstanding(detail)) }}</b></div>
              <div><span>加工师傅</span><b>{{ detail.craftsman_name || '未指派' }}</b></div>
              <div><span>预计取货</span><b>{{ date(detail.pickup_date) }}</b></div>
              <div><span>开单人</span><b>{{ detail.creator_name || '—' }}</b></div>
              <div><span>开单时间</span><b>{{ dateTime(detail.create_time) }}</b></div>
            </div>
            <p v-if="detail.remark" class="remark">备注：{{ detail.remark }}</p>
            <div v-if="(detail.payments || []).length" class="pay-list">
              <h4>收款记录</h4>
              <div v-for="pay in detail.payments" :key="pay.payment_id"><span>{{ dateTime(pay.create_time) }} · {{ pay.pay_method || '—' }} · {{ pay.operator_name || '' }}</span><b>{{ money(pay.amount) }}</b></div>
            </div>
            <div v-if="(detail.incoming_photos?.length || detail.weigh_photos?.length || detail.pickup_photos?.length) || (canManage && detail.status !== 'PICKED_UP')" class="photo-strip">
              <div v-if="(detail.incoming_photos?.length) || (canManage && detail.status !== 'PICKED_UP')"><h4>来料照片</h4>
                <div class="thumbs"><img v-for="(p,i) in detail.incoming_photos" :key="'in'+i" :src="p" @click="preview(p)"/>
                <label v-if="canManage && detail.status !== 'PICKED_UP'" class="add-photo" @click="nativeProcessingPhoto($event, 'incoming')">＋<input type="file" accept="image/*" multiple hidden @change="addPhotos($event, 'incoming')"/></label></div>
              </div>
              <div v-if="(detail.weigh_photos?.length) || (canManage && detail.status !== 'PICKED_UP')"><h4>称重照片</h4>
                <div class="thumbs"><img v-for="(p,i) in detail.weigh_photos" :key="'w'+i" :src="p" @click="preview(p)"/>
                <label v-if="canManage && detail.status !== 'PICKED_UP'" class="add-photo" @click="nativeProcessingPhoto($event, 'weigh')">＋<input type="file" accept="image/*" multiple hidden @change="addPhotos($event, 'weigh')"/></label></div>
              </div>
              <div v-if="(detail.pickup_photos?.length) || (canManage && detail.status === 'COMPLETED')"><h4>取货照片</h4>
                <div class="thumbs"><img v-for="(p,i) in detail.pickup_photos" :key="'p'+i" :src="p" @click="preview(p)"/>
                <label v-if="canManage && detail.status === 'COMPLETED' && detail.pickup_photos.length < 6" class="add-photo" @click="nativeProcessingPhoto($event, 'pickup')">＋<input class="pickup-photo-input" type="file" accept="image/*" capture="environment" multiple hidden @change="addPhotos($event, 'pickup')"/></label></div>
              </div>
            </div>
            <p v-if="detail.status === 'PROCESSING' && outstanding(detail) > 0" class="muted small">尾款未收清（未收 {{ money(outstanding(detail)) }}），请在收银端「前台待办」收款。</p>
            <p v-if="detail.status === 'PENDING' && Number(detail.handover) === 1" class="muted small">已转交前台，等待收银端确认加工。</p>
            <div class="sheet-actions">
              <button v-if="detail.customer_phone" class="outline" @click="call(detail.customer_phone)">一键拨号</button>
              <button v-if="detail.status === 'PENDING' && canHandover && Number(detail.handover) !== 1" class="primary" @click="advance(detail)">转交前台</button>
              <p v-if="['PROCESSING', 'COMPLETED'].includes(detail.status)" class="muted small" style="width:100%">加工中的单子请到收银端「前台待办」收尾款、登记补金/损耗、确认取货。</p>
            </div>
          </template>
        </div>
      </section>
    </div>
    <div v-if="creating" class="sheet-mask" @click.self="closeCreate">
      <section class="sheet">
        <header><strong>开加工单</strong><button class="icon-btn" @click="closeCreate">×</button></header>
        <div class="sheet-body">
          <label class="form-label">关联会员（选填）<input v-model.trim="memberKeyword" placeholder="姓名或手机号搜索会员" @input="searchMember"/></label>
          <div v-if="memberHits.length" class="member-hits"><button v-for="m in memberHits" :key="m.member_id" type="button" @click="pickMember(m)">{{ m.name }} · {{ m.phone }}</button></div>
          <small v-if="form.memberId" class="member-picked">已选：{{ form.customerName }} · {{ form.customerPhone }} <button class="outline" type="button" @click="clearMember">更换</button></small>
          <label class="form-label">预计取货日期<input v-model="form.pickupDate" type="date"/></label>
          <label class="form-label">客户姓名 *<input v-model.trim="form.customerName" placeholder="请输入客户姓名"/></label>
          <label class="form-label">客户电话 *<input v-model.trim="form.customerPhone" type="tel" placeholder="11 位手机号，用于取货通知"/></label>
          <label class="form-label">加工项目 *
            <select v-model="form.itemId" @change="handleItemChange">
              <option value="" disabled>请选择加工项目</option>
              <option v-for="item in items" :key="item.item_id" :value="item.item_id">{{ item.name }}（{{ item.category_name }}）· {{ money(item.labor_fee) }}{{ item.pricing_unit === '按克' ? '/g' : '/件' }} · {{ item.duration_text || `${item.processing_days}天` }}</option>
            </select>
          </label>
          <label class="form-label">数量 *<input v-model.number="form.quantity" type="number" min="1" step="1"/></label>
          <label v-if="selectedItem?.pricing_unit === '按克'" class="form-label">计费总克重 (g) *<input v-model.number="form.billingWeight" type="number" min="0.001" step="0.001"/></label>
          <label class="form-label">加工师傅
            <select v-model="form.craftsmanId">
              <option value="">暂不指派</option>
              <option v-if="!craftsmen.length" value="" disabled>请先在管理端人员中新增打金师傅</option>
              <option v-for="worker in craftsmen" :key="worker.user_id" :value="worker.user_id">{{ worker.real_name || worker.username }}</option>
            </select>
          </label>
          <div class="form-row">
            <label class="form-label">旧金克重<input v-model="form.oldGoldWeight" type="number" min="0" step="0.001" placeholder="选填"/></label>
            <label class="form-label">旧金成色<input v-model="form.oldGoldFineness" type="number" min="0" max="1" step="0.001" placeholder="如 0.999"/></label>
          </div>
          <div class="form-label">剩余旧料处理
            <div class="filter-tabs">
              <button v-for="h in handlings" :key="h.value" :class="{ active: form.residualGoldHandling === h.value }" @click="form.residualGoldHandling = h.value">{{ h.label }}</button>
            </div>
          </div>
          <template v-if="form.residualGoldHandling === 'STORE_DEDUCT'">
            <label class="form-label">旧料类型 *
              <select v-model="form.residualMaterialType">
                <option v-for="type in materialTypes" :key="type" :value="type">{{ type }}</option>
              </select>
            </label>
            <div class="form-row">
              <label class="form-label">旧料克重 *<input v-model="form.residualGoldWeight" type="number" min="0" step="0.001"/></label>
              <label class="form-label">旧料成色 *<input v-model="form.residualGoldFineness" type="number" min="0" max="1" step="0.001"/></label>
            </div>
            <p class="muted small">抵扣按 旧料克重 × 成色 × 当前回收金价（{{ money(recyclePrice) }}/g）估算，最高不超过工费，提交后以系统计算为准。</p>
          </template>
          <div class="estimate-lines">
            <div><span>加工工费</span><b>{{ money(laborFee) }}</b></div>
            <div v-if="deductionPreview > 0"><span>旧料抵扣</span><b>-{{ money(deductionPreview) }}</b></div>
            <div class="total"><span>本单应收</span><b>{{ money(duePreview) }}</b></div>
          </div>
          <div class="form-row">
            <label class="form-label">收取定金<input v-model="form.deposit" type="number" min="0" step="0.01" placeholder="0 表示不收"/></label>
            <label class="form-label">定金支付方式
              <select v-model="form.depositMethod">
                <option v-for="m in depositMethods" :key="m.value" :value="m.value">{{ m.label }}</option>
              </select>
            </label>
          </div>
          <p v-if="form.deposit !== '' && Number(form.deposit) > 0" class="muted small">尾款待收 {{ money(Math.max(0, duePreview - Number(form.deposit || 0))) }}，可在"待处理"或加工详情中收尾款。</p>
          <label class="form-label">备注<textarea v-model.trim="form.remark" rows="2" placeholder="选填"/></label>
          <p v-if="createError" class="error">{{ createError }}</p>
          <button class="primary full" :disabled="saving" @click="submit">{{ saving ? '提交中...' : '创建加工单并收定金' }}</button>
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
const canHandover = computed(() => ['ADMIN', 'MANAGER', 'SALES'].includes(auth.role))
const statuses = [{ key: '', label: '全部' }, { key: 'PENDING', label: '待加工' }, { key: 'PROCESSING', label: '加工中' }, { key: 'COMPLETED', label: '待取货' }, { key: 'PICKED_UP', label: '已取货' }]
const steps = [
  { key: 'PENDING', label: '已收料', done: () => true, current: s => s === 'PENDING' },
  { key: 'PROCESSING', label: '加工中', done: s => ['PROCESSING', 'COMPLETED', 'PICKED_UP'].includes(s), current: s => s === 'PROCESSING' },
  { key: 'COMPLETED', label: '待取货', done: s => ['COMPLETED', 'PICKED_UP'].includes(s), current: s => s === 'COMPLETED' },
  { key: 'PICKED_UP', label: '已取货', done: s => s === 'PICKED_UP', current: s => s === 'PICKED_UP' }
]
const handlings = [{ value: 'TAKE_AWAY', label: '客户带走' }, { value: 'STORE_DEDUCT', label: '留店抵扣' }]
const keyword = ref(''), status = ref(''), orders = ref([]), loading = ref(false), error = ref('')
const detail = ref(null), detailLoading = ref(false)
const creating = ref(false), saving = ref(false), createError = ref('')
const items = ref([]), craftsmen = ref([]), materialTypes = ref(['足金旧料', '18K旧料', '22K旧料', '银旧料'])
const form = reactive({ customerName: '', customerPhone: '', itemId: '', quantity: 1, billingWeight: '', craftsmanId: '', pickupDate: '', oldGoldWeight: '', oldGoldFineness: '', residualGoldHandling: 'TAKE_AWAY', residualMaterialType: '足金旧料', residualGoldWeight: '', residualGoldFineness: '', deposit: '', depositMethod: 'CASH', remark: '', memberId: null })
const memberKeyword = ref(''), memberHits = ref([])
const depositMethods = [{ value: 'CASH', label: '现金' }, { value: 'WECHAT', label: '微信' }, { value: 'ALIPAY', label: '支付宝' }, { value: 'BANK', label: '银行卡' }]
const recyclePrice = computed(() => { const rows = Array.isArray(app.gold) ? app.gold : []; const hit = rows.find(x => String(x.price_type || x.priceType || x.name || '').includes('回收')); return Number(hit?.price || 0) })
const deductionPreview = computed(() => { if (form.residualGoldHandling !== 'STORE_DEDUCT') return 0; const value = Number(form.residualGoldWeight || 0) * Number(form.residualGoldFineness || 0) * recyclePrice.value; return Math.min(value, laborFee.value) })
const duePreview = computed(() => Math.max(0, laborFee.value - deductionPreview.value))
const money = v => `¥${Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
const grams = v => `${Number(v || 0).toFixed(3)}g`
const outstanding = row => Math.max(Number(row?.due_amount || 0) - Number(row?.paid_amount || 0), 0)
const fineness = v => (v === null || v === undefined || v === '' ? '—' : `${(Number(v) * 100).toFixed(1)}%`)
const date = v => (v ? String(v).slice(0, 10) : '—')
const dateTime = v => {
  if (!v) return '—'
  const text = String(v).trim().replace(' ', 'T')
  const source = /[zZ]|[+-]\d{2}:?\d{2}$/.test(text) ? text : `${text}Z`
  const time = new Date(source)
  if (Number.isNaN(time.getTime())) return '—'
  return new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(time).replace(/\//g, '-')
}
const statusLabel = v => ({ PENDING: '待加工', PROCESSING: '加工中', COMPLETED: '已完成待取货', PICKED_UP: '已取货' }[String(v || '').toUpperCase()] || '未知')
const statusClass = v => String(v || '').toLowerCase()
const handlingLabel = v => ({ TAKE_AWAY: '客户带走', STORE_DEDUCT: '留店抵扣' }[String(v || '').toUpperCase()] || '—')
async function advance(order) {
  if (!window.confirm(`确认将 ${order.order_no}「转交前台」？`)) return
  try {
    await api.processingHandover(order.processing_order_id)
    window.alert('已转交前台，请在电脑收银端「前台待办」确认加工')
    detail.value = null; await load()
  } catch (e) { window.alert(e?.message || '状态更新失败') }
}
const selectedItem = computed(() => items.value.find(item => Number(item.item_id) === Number(form.itemId)) || null)
const laborFee = computed(() => Math.round(Number(selectedItem.value?.labor_fee || 0) * Number((selectedItem.value?.pricing_unit === '按克' ? form.billingWeight : form.quantity) || 0) * 100) / 100)
const lateCount = computed(() => orders.value.filter(o => o.status !== 'PICKED_UP' && o.pickup_date && new Date(String(o.pickup_date).slice(0, 10)) < new Date(new Date().toISOString().slice(0, 10))).length)
const list = rows => (Array.isArray(rows) ? rows : rows?.records || [])
async function load() {
  loading.value = true; error.value = ''
  try {
    const rows = list(await api.processingOrders({ ...(keyword.value ? { keyword: keyword.value } : {}), ...(status.value ? { status: status.value } : {}) }))
    orders.value = status.value === 'PENDING' ? rows.filter(o => Number(o.handover) !== 1) : rows
  } catch (e) { error.value = e?.message || '加工订单加载失败' } finally { loading.value = false }
}
async function openDetail(order) {
  detail.value = order; detail.value.incoming_photos = parsePhotos(order.incoming_photos); detail.value.weigh_photos = parsePhotos(order.weigh_photos); detail.value.pickup_photos = parsePhotos(order.pickup_photos); detailLoading.value = true
  try {
    detail.value = await api.processingOrder(order.processing_order_id)
    detail.value.incoming_photos = parsePhotos(detail.value.incoming_photos)
    detail.value.weigh_photos = parsePhotos(detail.value.weigh_photos)
    detail.value.pickup_photos = parsePhotos(detail.value.pickup_photos)
  } finally { detailLoading.value = false }
}
function closeDetail() { detail.value = null }
async function notify(order) {
  try { await api.processingNotify(order.processing_order_id); window.alert(`已记录通知 ${order.customer_name || '客户'} 取货`) } catch (e) { window.alert(e?.message || '通知失败') }
}
function call(phone) { if (typeof uni !== 'undefined') uni.makePhoneCall({ phoneNumber: String(phone) }); else window.location.href = `tel:${phone}` }
async function openCreate() {
  creating.value = true; createError.value = ''
  if (!items.value.length) {
    try { items.value = list(await api.processingItems({ status: 1 })) } catch (e) { createError.value = e?.message || '加工项目加载失败' }
  }
  if (!craftsmen.value.length) await loadCraftsmen()
  try { const types = list(await api.oldMaterialTypes()).filter(type => Number(type.status ?? 1) === 1); if (types.length) materialTypes.value = types.map(type => type.name) } catch (e) { /* 无权限时沿用常用旧料类型 */ }
  app.loadGold()
}
async function loadCraftsmen() {
  try {
    craftsmen.value = list(await api.processingCraftsmen())
    if (form.craftsmanId && !craftsmen.value.some(worker => Number(worker.user_id) === Number(form.craftsmanId))) form.craftsmanId = ''
  } catch (e) { craftsmen.value = [] }
}
function closeCreate() { creating.value = false; saving.value = false }
async function searchMember() {
  const kw = memberKeyword.value.trim()
  if (!kw) { memberHits.value = []; return }
  try { const d = await api.members({ keyword: kw, size: 5 }); memberHits.value = (d?.records || d || []).slice(0, 5) } catch (e) { memberHits.value = [] }
}
function pickMember(m) { form.memberId = m.member_id; form.customerName = m.name || ''; form.customerPhone = m.phone || ''; memberKeyword.value = ''; memberHits.value = [] }
function clearMember() { form.memberId = null; memberKeyword.value = ''; memberHits.value = [] }
const photoBusy = ref(false)
let nativePhotoBusy = false
async function nativeProcessingPhoto(event, type) {
  if (!isNativeApp()) return
  event.preventDefault()
  if (nativePhotoBusy || photoBusy.value) return
  nativePhotoBusy = true
  try { await addPhotos({ target: { files: [await takeNativePhoto()], value: '' } }, type) }
  catch (error) { if (!/cancel|取消/i.test(error?.message || '')) window.alert(error?.message || '拍照失败，请检查相机权限') }
  finally { nativePhotoBusy = false }
}
async function addPhotos(event, type) {
  const files = [...(event.target.files || [])]
  event.target.value = ''
  if (!files.length || photoBusy.value) return
  photoBusy.value = true
  try {
    const urls = []
    for (const file of files.slice(0, 6 - (detail.value[`${type}_photos`]?.length || 0))) {
      const res = await uploadImage('', file, { bizType: 'processing', orderNo: detail.value.order_no })
      const url = typeof res === 'string' ? res : res?.data?.url || res?.url || res?.data
      if (url) urls.push(String(url))
    }
    if (urls.length) detail.value = await api.processingPhotos(detail.value.processing_order_id, { type, urls })
    detail.value.incoming_photos = parsePhotos(detail.value.incoming_photos)
    detail.value.weigh_photos = parsePhotos(detail.value.weigh_photos)
    detail.value.pickup_photos = parsePhotos(detail.value.pickup_photos)
  } catch (e) { window.alert(e?.message || '照片上传失败') } finally { photoBusy.value = false }
}
function parsePhotos(v) { let list = v; if (!Array.isArray(list)) { try { list = JSON.parse(v || '[]') } catch { list = [] } } return (Array.isArray(list) ? list : []).filter(Boolean).map(u => String(u).startsWith('/') ? http.defaults.baseURL + u : u) }
function preview(url) { if (url) window.open(url) }
function handleItemChange() {
  const base = new Date()
  base.setDate(base.getDate() + Number(selectedItem.value?.processing_days || 0))
  form.pickupDate = base.toISOString().slice(0, 10)
}
function resetForm() {
  Object.assign(form, { customerName: '', customerPhone: '', itemId: '', quantity: 1, billingWeight: '', craftsmanId: '', pickupDate: '', oldGoldWeight: '', oldGoldFineness: '', residualGoldHandling: 'TAKE_AWAY', residualMaterialType: materialTypes.value[0] || '足金旧料', residualGoldWeight: '', residualGoldFineness: '', deposit: '', depositMethod: 'CASH', remark: '', memberId: null })
  memberKeyword.value = ''; memberHits.value = []
}
async function submit() {
  createError.value = ''
  if (!form.customerName) { createError.value = '请填写客户姓名'; return }
  if (!/^1\d{10}$/.test(form.customerPhone)) { createError.value = '请填写 11 位手机号'; return }
  if (!form.itemId) { createError.value = '请选择加工项目'; return }
  if (!(Number(form.quantity) > 0)) { createError.value = '数量必须大于 0'; return }
  if (selectedItem.value?.pricing_unit === '按克' && !(Number(form.billingWeight) > 0)) { createError.value = '请填写大于0的计费总克重'; return }
  if (form.residualGoldHandling === 'STORE_DEDUCT' && (!form.residualMaterialType || !(Number(form.residualGoldWeight) > 0) || !(Number(form.residualGoldFineness) > 0))) {
    createError.value = '留店抵扣需填写旧料类型、克重和成色'; return
  }
  const deposit = Number(form.deposit || 0)
  if (deposit < 0 || deposit > duePreview.value) { createError.value = '定金应在 0 到本单应收之间'; return }
  const payload = {
    customerName: form.customerName, customerPhone: form.customerPhone, processingItemId: Number(form.itemId), quantity: Number(form.quantity),
    billingWeight: selectedItem.value?.pricing_unit === '按克' ? Number(form.billingWeight) : null,
    residualGoldHandling: form.residualGoldHandling,
    ...(form.memberId ? { memberId: Number(form.memberId) } : {}),
    ...(form.craftsmanId ? { craftsmanId: Number(form.craftsmanId) } : {}),
    ...(form.pickupDate ? { pickupDate: form.pickupDate } : {}),
    ...(form.oldGoldWeight !== '' ? { oldGoldWeight: Number(form.oldGoldWeight) } : {}),
    ...(form.oldGoldFineness !== '' ? { oldGoldFineness: Number(form.oldGoldFineness) } : {}),
    ...(form.remark ? { remark: form.remark } : {})
  }
  if (form.residualGoldHandling === 'STORE_DEDUCT') {
    payload.residualMaterialType = form.residualMaterialType
    payload.residualGoldWeight = Number(form.residualGoldWeight)
    payload.residualGoldFineness = Number(form.residualGoldFineness)
  }
  saving.value = true
  try {
    const created = await api.processingCreate(payload)
    let depositError = ''
    if (deposit > 0 && created?.processing_order_id) {
      try {
        await api.processingPay(created.processing_order_id, { paymentType: 'DEPOSIT', amount: deposit, payMethod: form.depositMethod, clientRequestId: `mobile-deposit-${Date.now()}`, remark: '移动端开单定金' })
      } catch (e) { depositError = e?.message || '定金登记失败' }
    }
    closeCreate(); resetForm(); await load()
    if (created?.processing_order_id) await openDetail(created)
    if (depositError) window.alert(`加工单已创建，但定金登记失败：${depositError}，请在待处理中补收`)
  } catch (e) { createError.value = e?.message || '开加工单失败' } finally { saving.value = false }
}
onMounted(load)
watch(()=>app.eventVersion,()=>{if(['PROCESSING_ORDER_CREATED','PROCESSING_ORDER_UPDATED','PROCESSING_CATALOG_UPDATED','STAFF_UPDATED'].includes(app.lastEventType)){if(app.lastEventType==='PROCESSING_CATALOG_UPDATED')items.value=[];if(app.lastEventType==='STAFF_UPDATED'){craftsmen.value=[];if(creating.value)loadCraftsmen()}load()}})
</script>
<style scoped>
.form-card{background:#fff;border:1px solid var(--line);border-radius:8px;padding:14px}
.search{display:flex;gap:8px}
.search input{flex:1;min-width:0;min-height:44px;border:1px solid #dfe3e8;border-radius:8px;padding:0 10px}
.search button{min-width:72px}
.filter-tabs{margin:10px 0}
.filter-tabs button{white-space:nowrap}
.form-card .full{margin-top:4px}
.processing-card{display:block;cursor:pointer}
.processing-main{min-width:0}
.processing-title{display:flex;align-items:center;justify-content:space-between;gap:8px}
.processing-title b{font-size:var(--f-sm);min-width:0;overflow-wrap:anywhere;line-height:1.4}
.processing-card p{margin:5px 0 0;color:var(--ink-2);font-size:var(--f-xs);overflow-wrap:break-word}
.status-pill{padding:3px 7px;border-radius:12px;font-size:11px;white-space:nowrap}
.status-pill.pending{background:#fff4df;color:#a26712}
.status-pill.processing{background:#eef6ff;color:#245b87}
.status-pill.completed{background:#e7f6ed;color:#18864b}
.status-pill.picked_up{background:#f0f1f3;color:var(--ink-3)}
.timeline{display:flex;justify-content:space-between;margin:16px 0 2px}
.timeline-step{position:relative;flex:1;min-width:0;text-align:center;color:var(--ink-3);font-size:11px}
.timeline-step:not(:last-child)::after{content:'';position:absolute;top:5px;left:50%;width:100%;height:2px;background:#e5e7eb;z-index:0}
.timeline-step i{position:relative;z-index:1;display:block;width:12px;height:12px;border-radius:50%;background:#dfe3e8;margin:0 auto 5px}
.timeline-step.done{color:var(--gold-deep)}
.timeline-step.done i{background:var(--gold)}
.timeline-step.current{font-weight:600}
.sheet-mask{position:fixed;inset:0;background:rgba(15,20,28,.45);display:flex;align-items:flex-end;justify-content:center;z-index:60}
.sheet{width:100%;max-width:560px;max-height:88vh;display:flex;flex-direction:column;background:#fff;border-radius:14px 14px 0 0;overflow:hidden}
.sheet>header{display:flex;align-items:center;justify-content:space-between;gap:8px;padding:12px 14px;border-bottom:1px solid var(--line)}
.sheet>header strong{font-size:15px}
.sheet-body{padding:14px;overflow-y:auto;-webkit-overflow-scrolling:touch}
.detail-head{display:flex;align-items:center;justify-content:space-between;gap:8px}
.detail-head b{font-size:15px;min-width:0;overflow-wrap:anywhere;line-height:1.4}
.detail-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;margin-top:14px;padding:12px;background:var(--gold-soft);border-radius:8px;font-size:12px}
.detail-grid span{display:block;color:var(--ink-3)}
.detail-grid b{display:block;margin-top:3px;font-size:13px;overflow-wrap:break-word}
.remark{margin:10px 0 0;font-size:12px;color:var(--ink-2)}
.pay-list{margin-top:12px;border-top:1px dashed var(--line);padding-top:10px}
.pay-list h4{margin:0 0 6px;font-size:13px}
.pay-list div{display:flex;align-items:center;justify-content:space-between;gap:8px;font-size:12px;color:var(--ink-2);padding:3px 0}
.sheet-actions{display:flex;flex-wrap:wrap;gap:8px;margin-top:14px}
.sheet-actions button{flex:1;min-width:110px}
.form-label{display:block;margin-bottom:10px;font-size:13px;color:var(--ink-2)}
.form-label input,.form-label select,.form-label textarea{display:block;width:100%;margin-top:5px;min-height:42px;border:1px solid #dfe3e8;border-radius:8px;padding:8px 10px;font-size:14px;color:var(--ink);background:#fff}
.form-row{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}
.estimate-lines{margin:6px 0 12px;padding:10px 12px;background:var(--gold-soft);border-radius:8px;font-size:13px}
.estimate-lines div{display:flex;align-items:center;justify-content:space-between;gap:8px;padding:3px 0}
.estimate-lines span{color:var(--ink-3)}
.estimate-lines b{color:var(--ink)}
.estimate-lines .total{border-top:1px dashed #e3d5b4;margin-top:4px;padding-top:6px}
.estimate-lines .total b{color:var(--gold-deep);font-size:16px}
.error{margin:0 0 10px;color:#c0392b;font-size:12px}
.photo-strip{margin-top:12px;display:grid;gap:10px}
.photo-strip h4{margin:0 0 6px;font-size:13px}
.thumbs{display:flex;flex-wrap:wrap;gap:8px}
.thumbs img{width:64px;height:64px;object-fit:cover;border-radius:8px;border:1px solid var(--line);cursor:pointer}
.add-photo{width:64px;height:64px;display:flex;align-items:center;justify-content:center;border:1px dashed #b9b2a4;border-radius:8px;color:var(--gold-deep);font-size:22px;cursor:pointer}
</style>
