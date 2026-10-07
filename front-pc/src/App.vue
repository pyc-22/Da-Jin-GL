<script setup>
import { computed, h, onBeforeUnmount, onMounted, reactive, ref, toRaw, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import zhCn from 'element-plus/dist/locale/zh-cn.mjs'
import {
  Archive, ArrowRight, ArrowRightLeft, Bell, BookOpen, Calculator, Check, ChevronRight, CircleDollarSign, Cloud,
  ClipboardCheck, ClipboardList, Clock3, CreditCard, Gem, Grid2X2, History, Landmark, Menu,
  Minus, Monitor, PackageCheck, PanelLeft, Phone, Plus, Printer, Receipt, RefreshCw, Search,
  Settings2, ShieldAlert, ShoppingCart, Store, Trash2, Upload, UserRound, Users, Wifi, WifiOff, X
} from 'lucide-vue-next'
import { apiBase, fetchBackend, getToken, login, openBackendSocket, request, setApiBase, setToken, uploadBackendPhoto } from './api'
import { filterCatalogProducts } from './catalog'
import { calculateOldMaterialSettlement, handoverCheckout } from './checkout'
import { buildShiftPreview, buildShiftPrintHtml, parsePurity, PURITY_OPTIONS, goldBalance, finenessFactor, oldGoldDeduction } from './cashier'
import { isBackendOnline } from './connectivity'
import { buildReceiptPreview, formatPrintTime } from './escpos'
import { cancelQueuedOrder, enqueueWithId, localConflicts, localGold, localMembers, localProducts, requestOrQueue, resolveLocalConflict, syncQueue, uuid } from './offline'
import { shouldRefreshCatalog, shouldRefreshProcessingReferences } from './sync-events'
import { activatePaymentMethod, paymentInputMethods, reconcilePaymentMethods, togglePaymentMethod } from './payment-methods'
import { markProcessingPickedUp, processingOutstanding, processingRefundOutstanding } from './processing-pickup'
import { createLatestOnlyGuard } from './front-todo'
import { pendingSaleOrderId, shouldReusePendingSale } from './pending-sale'
import { mergeGoldPriceUpdate, resolveGoldReferences } from './gold-pricing'

const menus = [
  { id: 'order', label: '开单', icon: ShoppingCart },
  { id: 'processing', label: '加工开单', icon: Settings2 },
  { id: 'process', label: '项目', icon: Settings2 },
  { id: 'member', label: '会员', icon: Users },
  { id: 'stock', label: '盘点', icon: ClipboardCheck },
  { id: 'bill', label: '单据', icon: Receipt },
  { id: 'shift', label: '交班', icon: Calculator },
  { id: 'todo', label: '前台待办', icon: ClipboardList, badge: () => printJobs.value.length + todoHandovers.value.length + todoOrders.value.length + todoProcessings.value.length + todoPickups.value.length }
]
const hiddenCatalogTabs = ['加工', '回收']
const categoryRows = ref([])
const activeSubCategory = ref(null)
const categoryTabs = computed(() => {
  const tabs = categoryRows.value.filter(item => Number(item.level) === 1 && !hiddenCatalogTabs.includes(item.name)).map(item => ({ id: Number(item.category_id), name: item.name }))
  return [{ id: null, name: '全部' }, ...tabs]
})
const subCategoryOptions = computed(() => categoryRows.value.filter(item => Number(item.level) === 2 && Number(item.parent_id) === Number(activeCategory.value)).map(item => ({ id: Number(item.category_id), name: item.name })))
const defaultMethods = [
  { code: 'CASH', name: '现金', icon: Landmark },
  { code: 'WECHAT', name: '微信', icon: Phone },
  { code: 'ALIPAY', name: '支付宝', icon: CircleDollarSign },
  { code: 'BANK', name: '银行卡', icon: CreditCard },
  { code: 'BALANCE', name: '储值', icon: UserRound }
]
const paymentLabels = {
  CASH: '现金',
  WECHAT: '微信',
  ALIPAY: '支付宝',
  BANK: '银行卡',
  BALANCE: '储值',
  DOUYIN_GROUP: '抖音团购',
  MEITUAN_GROUP: '美团团购',
  COMBINATION: '组合支付'
}

const activeMenu = ref('order')
const products = ref([])
const members = ref([])
const gold = ref([{ price_type: '足金', price: 612 }, { price_type: '回收金价', price: 578 }, { price_type: '18K', price: 428 }, { price_type: '银', price: 0 }, { price_type: '银回收价', price: 0 }])
const search = ref('')
const activeCategory = ref(null)
const cart = ref([])
const selectedMember = ref(null)
const selectedSalesId = ref(null)
const salespeople = ref([])
const SALES_DEFAULT_KEY = 'dajin_last_sales_id'
const discount = ref(1)
const config = reactive({ discountThreshold: 0.85, recycleLimit: 10000 })
const oldMaterialTypes = ref(['足金999', '足金990', '22K金', '18K金', '14K金', '铂金950', '铂金900', '纯银'])
const browserOnline = ref(navigator.onLine)
const backendReachable = ref(false)
const backendConfigured = ref(!window.dajin?.network)
const online = computed(() => isBackendOnline({ browserOnline: browserOnline.value, backendReachable: backendReachable.value }))
const syncing = ref(false)
const conflictCount = ref(0)
const conflictRows = ref([])
const noticeCount = ref(0)
const notifications = ref([])
const unreadReminders = computed(() => notifications.value.filter(n => n.action === 'REMIND').length)
const noticeBadge = computed(() => noticeCount.value + unreadReminders.value)
async function loadNotifications() {
  if (!online.value || !getToken()) return
  try { notifications.value = await request('/api/notification') || [] } catch { /* keep last list on failure */ }
}
async function openNotifications() {
  noticeCount.value = 0
  activeDialog.value = 'notifications'
  await loadNotifications()
  const unread = notifications.value.filter(n => n.action === 'REMIND')
  for (const item of unread) { try { await request(`/api/notification/${item.notification_id}/read`, { method: 'POST' }) } catch { /* ignore single failure */ } }
  if (unread.length) await loadNotifications()
}
const cashier = reactive({ name: '演示收银员', role: 'FRONT', storeName: '郑州旗舰店' })
const cashierRoleName = computed(() => ({ ADMIN: '系统管理员', MANAGER: '店长', FRONT: '收银员' }[cashier.role] || cashier.role))
const loginForm = reactive({ username: '', password: '' })
const memberCreateForm = reactive({ name: '', phone: '', birthday: '' })
const currentTime = ref(new Date())
const activeDialog = ref('')
const toast = ref('')
const orderDraft = ref(null)
const checkoutSubmitting = ref(false)
const paymentSubmitting = ref(false)
const settlementMode = ref('FULL')
const settlementDiscountReason = ref('')
const approval = reactive({ required: false, status: '', id: null, message: '', kind: '' })
const oldMetals = ref([])
const oldMetalForm = reactive({ weight: 0, purityChoice: '0.999', customPurity: '', materialType: '足金999', priceType: '回收金价', note: '' })
const paymentMethods = ref(defaultMethods.map(x => ({ ...x, amount: 0, selected: false })))
const groupPaymentMethods = ref([])
const oldMaterialPayoutMethod = ref('CASH')
const oldMaterialPayoutAmount = ref(0)
const payoutMethods = computed(() => paymentMethods.value.filter(method => !['BALANCE', 'COMBINATION'].includes(method.code)))
const paymentTotal = ref(0)
const printPreview = ref({ type: 'receipt', text: '', html: '' })
const processingPrintModel = ref(null)
// Whether a successful work-order print should also advance the processing order to PROCESSING.
// Printing from 待确认加工 confirms the job; reprinting from 加工中 must not touch the status.
const processingPrintAdvance = ref(false)
const printSettings = reactive({ paperWidth: 58, deviceName: '', silent: false })
const apiEndpoint = ref(apiBase())
const printers = ref([])
const recycleForm = reactive({ memberId: null, materialType: '足金999', purityChoice: '0.999', customPurity: '', weight: 0, deductLossRate: 0, recyclePrice: 578, payMethod: 'CASH' })
const tradeForm = reactive({ memberId: null, oldValue: 0, newValue: 0, oldMaterialInfo: '', newGoodsInfo: '' })
const tradeOldMetals = ref([])
const tradeOldForm = reactive({ weight: 0, purityChoice: '0.999', customPurity: '', materialType: '足金999', note: '' })
const checkRows = ref([])
const billRows = ref([])
const shiftRows = ref([])
const shiftMeta = ref({})
const memberKeyword = ref('')
const memberHits = ref([])
const memberPickList = computed(() => (memberKeyword.value ? memberHits.value : members.value))
async function searchMembersRemote() {
  const kw = memberKeyword.value.trim()
  if (!kw) { memberHits.value = []; return }
  try {
    const result = await request(`/api/member/list?page=1&size=20&keyword=${encodeURIComponent(kw)}`)
    memberHits.value = (result?.records || result || []).map(item => ({ ...item, id: item.member_id ?? item.id }))
  } catch {
    memberHits.value = members.value.filter(m => `${m.name}${m.phone}`.includes(kw))
  }
}
const billKeyword = ref('')
const shiftCash = ref(0)
const shiftRemark = ref('')
const shiftPrintModel = ref(null)
const shiftPaper = ref('58')
const heldOrders = ref([])
const HELD_ORDERS_KEY = 'dajin_held_orders'
const processingItems = ref([])
let todoTimer = null
function startTodoPolling() { loadFrontTodo(); loadNotifications(); if (!todoTimer) todoTimer = setInterval(() => { loadFrontTodo(); loadNotifications() }, 30000) }
const todoHandovers = ref([])
const todoOrders = ref([])
const todoPickups = ref([])
const todoProcessings = ref([])
const processingDrafts = ref([])
const printJobs = ref([])
const activePrintJob = ref(null)
const todoTab = ref('handover')
const frontTodoLoadGuard = createLatestOnlyGuard()
const todoTabs = [
  { key: 'print', label: '待打印', count: () => printJobs.value.length },
  { key: 'handover', label: '待确认加工', count: () => todoHandovers.value.length },
  { key: 'pay', label: '待收款', count: () => todoOrders.value.length },
  { key: 'processing', label: '加工中', count: () => todoProcessings.value.length },
  { key: 'pickup', label: '待取货', count: () => todoPickups.value.length }
]
const procManage = ref(null)
const procWarrantyHtml = ref('')
const procPayMethod = ref('CASH')
const procPayType = ref('BALANCE')
const procPayAmount = ref(null)
const procVoucherNo = ref('')
const procPayBusy = ref(false)
const procPayRequestId = ref('')
const procSettlementMode = ref('FULL')
const procSettlementDiscountReason = ref('')
const procPayMethods = computed(() => {
  if (procPayType.value === 'REFUND') return payoutMethods.value
  return procPayType.value === 'BALANCE' && procManage.value?.status === 'COMPLETED' && !procManage.value?.promotion_channel
    ? [...paymentMethods.value, ...groupPaymentMethods.value] : paymentMethods.value
})
const procGroupPayment = computed(() => ['DOUYIN_GROUP', 'MEITUAN_GROUP'].includes(procPayMethod.value))
const procPayDiscount = computed(() => Math.max(0, Math.round(processingOutstanding(procManage.value) * 100 - Number(procPayAmount.value || 0) * 100) / 100))
const procGoldForm = reactive({ weight: null, fineness: 0.999, price: 0, downMaterial: null })
const procWeighForm = reactive({ finishedWeight: null, finishedFineness: null, recoveredWeight: null, meltedWeight: null, billingWeight: null, note: '' })
const procFinish = reactive({ row: null, detail: null, goldWeight: null, goldFineness: 0.999, goldPrice: 0, downMaterialWeight: null, billingWeight: null, oldGoldWeight: null, oldGoldFineness: 0.999, meltWeight: null, finishedWeight: null, finishedFineness: null, recoveredWeight: null, note: '', residualGoldHandling: 'TAKE_AWAY', residualMaterialType: '足金999', residualRecyclePrice: 0, residualGoldWeight: null, residualGoldFineness: 0.999, incoming: [], weighPhotos: [], baseIncoming: [], baseWeigh: [], busy: false })
const procPickup = reactive({ row: null, photos: [], busy: false })
async function loadFrontTodo() {
  if (!online.value || !getToken()) return
  const loadId = frontTodoLoadGuard.begin()
  const current = () => frontTodoLoadGuard.isCurrent(loadId)
  try {
    const jobs = await request('/api/processing/print-jobs?status=PENDING')
    if (!current()) return
    printJobs.value = Array.isArray(jobs) ? jobs : jobs?.records || []
  } catch { if (current()) printJobs.value = [] }
  try {
    const handovers = await request('/api/processing/handovers')
    if (!current()) return
    todoHandovers.value = Array.isArray(handovers) ? handovers : handovers?.records || []
  } catch { if (current()) todoHandovers.value = [] }
  try {
    const orders = await request('/api/order/list?page=1&size=50&status=0')
    if (!current()) return
    todoOrders.value = orders?.records || (Array.isArray(orders) ? orders : [])
  } catch { if (current()) todoOrders.value = [] }
  try {
    const pickups = await request('/api/processing/orders?status=COMPLETED')
    if (!current()) return
    todoPickups.value = Array.isArray(pickups) ? pickups : pickups?.records || []
  } catch (error) { if (current()) ElMessage.error(error?.message || '待取货列表加载失败') }
  try {
    const processings = await request('/api/processing/orders?status=PROCESSING')
    if (!current()) return
    todoProcessings.value = Array.isArray(processings) ? processings : processings?.records || []
  } catch (error) { if (current()) ElMessage.error(error?.message || '加工中列表加载失败') }
  try {
    const drafts = await request('/api/processing/orders?status=PENDING')
    if (current()) processingDrafts.value = Array.isArray(drafts) ? drafts : drafts?.records || []
  } catch { if (current()) processingDrafts.value = [] }
}
async function completeProcessing(row) {
  try {
    await request(`/api/processing/orders/${row.processing_order_id}/status`, { method: 'PATCH', body: JSON.stringify({ status: 'COMPLETED' }) })
    ElMessage.success(`加工单 ${row.order_no} 已完成加工`)
    await loadFrontTodo()
  } catch (error) { ElMessage.error(error?.message || '完成加工失败（尾款未收清时请先收款）') }
}
function openProcPay(row, type = 'BALANCE') {
  procManage.value = row
  procPayType.value = type
  procPayAmount.value = type === 'BALANCE' ? processingOutstanding(row) : type === 'REFUND' ? processingRefundOutstanding(row) : null
  procSettlementMode.value = type === 'BALANCE' ? 'FULL' : 'PARTIAL'
  procSettlementDiscountReason.value = ''
  procPayMethod.value = paymentMethods.value[0]?.code || ''
  procVoucherNo.value = ''
  procPayRequestId.value = uuid()
  activeDialog.value = 'procPay'
}
function changeProcPayMethod() {
  procPayAmount.value = procPayType.value === 'REFUND' ? processingRefundOutstanding(procManage.value) : processingOutstanding(procManage.value)
  if (procGroupPayment.value) procSettlementMode.value = 'FULL'
  procVoucherNo.value = ''
}
async function confirmProcPay() {
  const row = procManage.value; if (!row || procPayBusy.value) return
  if (!procPayMethod.value) return ElMessage.warning('当前没有可用的支付方式，请联系管理员启用')
  if (procPayType.value === 'REFUND') {
    const outstanding = processingRefundOutstanding(row)
    const amount = Number(procPayAmount.value)
    if (!Number.isFinite(amount) || amount <= 0 || amount > outstanding) return ElMessage.warning('返款金额须大于0且不超过待返金额')
    procPayBusy.value = true
    try {
      const result = await request(`/api/processing/orders/${row.processing_order_id}/refund`, { method: 'POST', body: JSON.stringify({ amount, payMethod: procPayMethod.value, clientRequestId: procPayRequestId.value }) })
      if (result?.approvalRequired) return ElMessage.warning('返款金额超过审批上限，已提交店长/管理员审批；审批通过后请再次确认返款')
      ElMessage.success(`加工单 ${row.order_no} 客户返款已完成`)
      activeDialog.value = ''; await loadFrontTodo()
    } catch (error) { ElMessage.error(error?.message || '返款失败') } finally { procPayBusy.value = false }
    return
  }
  const remaining = processingOutstanding(row)
  const amount = Number(procPayAmount.value)
  if (!Number.isFinite(amount) || amount <= 0 || Math.abs(Math.round(amount * 100) - amount * 100) > 0.000001 || amount > remaining) return ElMessage.warning('收款金额须大于0、最多两位小数且不超过未收金额')
  if (procGroupPayment.value && !procVoucherNo.value.trim()) return ElMessage.warning('请输入团购核销单号')
  if (procPayType.value === 'BALANCE' && amount < remaining && !online.value) return ElMessage.warning('优惠收款需要联网提交审批或记账，请连接后重试')
  procPayBusy.value = true
  try {
    const body = { paymentType: procPayType.value, payMethod: procPayMethod.value, amount, settlementMode: procPayType.value === 'DEPOSIT' ? 'PARTIAL' : 'FULL', ...(procGroupPayment.value ? { voucherNo: procVoucherNo.value.trim() } : {}), clientRequestId: procPayRequestId.value }
    const result = await request(`/api/processing/orders/${row.processing_order_id}/payments`, { method: 'POST', body: JSON.stringify(body) })
    if (result?.approvalRequired) {
      ElMessage.warning(`优惠低于${Math.round(config.discountThreshold * 100)}折，已提交店长/管理员审批；审批通过后请再次确认收款`)
      return
    }
    if (result?.idempotentReplay) {
      const replay = result.replayedPayment || {}
      const replayAmount = replay.amount == null ? amount : Number(replay.amount)
      const replayMethod = replay.pay_method || procPayMethod.value
      ElMessage.warning(`该笔收款已完成：实收 ${money(replayAmount)}，支付方式 ${paymentLabel(replayMethod)}，系统未重复记账`)
    } else {
      ElMessage.success(`加工单 ${row.order_no} ${procPayType.value === 'DEPOSIT' ? '定金已收取' : result?.settlementDiscount > 0 ? '优惠结清' : '尾款已收清'}`)
    }
    activeDialog.value = ''; await loadFrontTodo()
  } catch (error) { ElMessage.error(error?.message || '收款失败') } finally { procPayBusy.value = false }
}
function openProcGold(row) { procManage.value = row; procGoldForm.weight = null; procGoldForm.fineness = 0.999; procGoldForm.price = 0; procGoldForm.downMaterial = Number(row?.down_material_weight) > 0 ? Number(row.down_material_weight) : null; const preview = goldBalance({ melt: row?.melt_weight, finished: row?.finished_weight }); procGoldForm.weight = preview.topUp > 0 ? preview.topUp : null; activeDialog.value = 'procGold' }
async function confirmProcGold() {
  const row = procManage.value; if (!row) return
  if (!(Number(procGoldForm.weight) > 0)) return ElMessage.warning('请填写补金克重')
  try {
    await request(`/api/processing/orders/${row.processing_order_id}/store-gold`, { method: 'POST', body: JSON.stringify({ weight: Number(procGoldForm.weight), fineness: Number(procGoldForm.fineness || 0.999), price: Number(procGoldForm.price || 0), downMaterialWeight: Number(procGoldForm.downMaterial || 0) }) })
    ElMessage.success(`加工单 ${row.order_no} 补金已登记，库存已扣减`)
    activeDialog.value = ''; await loadFrontTodo()
  } catch (error) { ElMessage.error(error?.message || '补金登记失败') }
}
function openProcWeigh(row) { procManage.value = row; procWeighForm.finishedWeight = row.finished_weight || null; procWeighForm.finishedFineness = null; procWeighForm.recoveredWeight = null; procWeighForm.meltedWeight = row.melt_weight != null ? Number(row.melt_weight) : null; procWeighForm.billingWeight = Number(row.billing_weight) > 0 ? Number(row.billing_weight) : (row.pricing_unit === '按克' ? (row.finished_weight || null) : null); procWeighForm.note = row.loss_note || ''; activeDialog.value = 'procWeigh' }
async function confirmProcWeigh() {
  const row = procManage.value; if (!row) return
  if (!(Number(procWeighForm.finishedWeight) > 0)) return ElMessage.warning('成品实重必须大于 0')
  try {
    const result = await request(`/api/processing/orders/${row.processing_order_id}/weighing`, { method: 'POST', body: JSON.stringify({ finishedWeight: Number(procWeighForm.finishedWeight), finishedFineness: procWeighForm.finishedFineness ? Number(procWeighForm.finishedFineness) : null, recoveredWeight: procWeighForm.recoveredWeight != null && procWeighForm.recoveredWeight !== '' ? Number(procWeighForm.recoveredWeight) : null, meltedWeight: procWeighForm.meltedWeight != null && procWeighForm.meltedWeight !== '' ? Number(procWeighForm.meltedWeight) : null, billingWeight: Number(procWeighForm.billingWeight) > 0 ? Number(procWeighForm.billingWeight) : null, note: procWeighForm.note || '' }) })
    if (result?.loss_over) ElMessage.warning(`已登记：损耗 ${Number(procWeighForm.recoveredWeight || 0)}g 超过约定比例，已标预警`)
    else if (result?.refillMissing) ElMessage.warning('成品比来料+补金还重，已按实际登记；请确认是否漏登记补金')
    else ElMessage.success('称重损耗已登记')
    activeDialog.value = ''; await loadFrontTodo()
  } catch (error) { ElMessage.error(error?.message || '损耗登记失败') }
}
function absFileUrl(u) { return String(u || '').startsWith('/') ? apiBase() + u : String(u || '') }
function parsePhotoList(v) { try { const l = typeof v === 'string' ? JSON.parse(v || '[]') : v; return Array.isArray(l) ? l.filter(Boolean) : [] } catch { return [] } }
function openProcFinish(row) {
  procFinish.row = row
  request(`/api/processing/orders/${row.processing_order_id}`).then(detail => {
    procFinish.detail = detail
    const o = detail?.order || detail || {}
    procFinish.baseIncoming = parsePhotoList(o.incoming_photos); procFinish.incoming = [...procFinish.baseIncoming]
    procFinish.baseWeigh = parsePhotoList(o.weigh_photos); procFinish.weighPhotos = [...procFinish.baseWeigh]
    procFinish.finishedWeight = o.finished_weight != null ? Number(o.finished_weight) : null
    // 按克项目：计费总克重到成品称重这一步才知道，默认带出成品实重（可手改）
    procFinish.billingWeight = Number(o.billing_weight) > 0
      ? Number(o.billing_weight)
      : (o.pricing_unit === '按克' && o.finished_weight != null ? Number(o.finished_weight) : null)
    procFinish.finishedFineness = o.finished_fineness != null ? Number(o.finished_fineness) : null
    procFinish.recoveredWeight = o.recovered_weight != null ? Number(o.recovered_weight) : null
    procFinish.meltWeight = o.melt_weight != null ? Number(o.melt_weight) : null
    procFinish.oldGoldWeight = o.old_gold_weight != null ? Number(o.old_gold_weight) : null
    procFinish.oldGoldFineness = o.old_gold_fineness != null ? Number(o.old_gold_fineness) : 0.999
    procFinish.note = o.loss_note || ''
    procFinish.goldWeight = null; procFinish.goldFineness = 0.999; procFinish.goldPrice = 0
    procFinish.downMaterialWeight = o.down_material_weight != null && Number(o.down_material_weight) > 0 ? Number(o.down_material_weight) : null
    const goldPreview = goldBalance({ melt: procFinish.meltWeight, finished: procFinish.finishedWeight })
    procFinish.goldWeight = goldPreview.topUp > 0 ? goldPreview.topUp : null
    procFinish.residualGoldHandling = o.residual_gold_handling === 'STORE_DEDUCT' ? 'STORE_DEDUCT' : 'TAKE_AWAY'
    procFinish.residualMaterialType = o.residual_material_type || oldMaterialTypes.value[0] || '足金999'
    procFinish.residualGoldWeight = o.residual_gold_weight != null ? Number(o.residual_gold_weight) : null
    procFinish.residualRecyclePrice = 0
    procFinish.residualGoldFineness = o.residual_gold_fineness != null ? Number(o.residual_gold_fineness) : 0.999
  }).catch(() => { procFinish.detail = null; procFinish.incoming = []; procFinish.weighPhotos = []; procFinish.baseIncoming = []; procFinish.baseWeigh = [] })
  activeDialog.value = 'procFinish'
}
async function uploadProcessingPhoto(file, orderNo) {
  const res = await uploadBackendPhoto(file, orderNo)
  const body = await res.json().catch(() => ({}))
  if (!res.ok || (body.code !== undefined && body.code !== 200)) throw new Error(body.message || '照片上传失败')
  return body.data?.url || body.url || body.data
}
async function uploadFinishPhoto(file) {
  const orderNo = procFinish.row?.order_no || procFinish.detail?.order?.order_no
  return uploadProcessingPhoto(file, orderNo)
}
function pickFinishPhotos(kind, input) {
  const files = [...(input.files || [])].slice(0, 6)
  input.value = ''
  for (const file of files) {
    uploadFinishPhoto(file).then(url => { if (url) procFinish[kind] = [...procFinish[kind], url] }).catch(e => ElMessage.error(e?.message || '照片上传失败'))
  }
}
function dropFinishPhotos(kind, event) {
  const files = [...(event.dataTransfer?.files || [])].filter(f => f.type.startsWith('image/'))
  if (!files.length) return
  for (const file of files) {
    uploadFinishPhoto(file).then(url => { if (url) procFinish[kind] = [...procFinish[kind], url] }).catch(e => ElMessage.error(e?.message || '照片上传失败'))
  }
}
function removeFinishPhoto(kind, index) { procFinish[kind] = procFinish[kind].filter((_, i) => i !== index) }
async function openProcPickup(row) {
  const detail = await request(`/api/processing/orders/${row.processing_order_id}`)
  const order = detail?.order || detail || row
  procPickup.row = row
  procPickup.photos = parsePhotoList(order.pickup_photos)
  activeDialog.value = 'procPickup'
}
function pickPickupPhotos(input) {
  const files = [...(input.files || [])].filter(file => file.type.startsWith('image/')).slice(0, Math.max(0, 6 - procPickup.photos.length))
  input.value = ''
  for (const file of files) {
    uploadProcessingPhoto(file, procPickup.row?.order_no).then(url => {
      if (url) procPickup.photos = [...procPickup.photos, url].slice(0, 6)
    }).catch(e => ElMessage.error(e?.message || '照片上传失败'))
  }
}
function dropPickupPhotos(event) {
  const files = [...(event.dataTransfer?.files || [])].filter(file => file.type.startsWith('image/')).slice(0, Math.max(0, 6 - procPickup.photos.length))
  for (const file of files) {
    uploadProcessingPhoto(file, procPickup.row?.order_no).then(url => {
      if (url) procPickup.photos = [...procPickup.photos, url].slice(0, 6)
    }).catch(e => ElMessage.error(e?.message || '照片上传失败'))
  }
}
function removePickupPhoto(index) { procPickup.photos = procPickup.photos.filter((_, i) => i !== index) }
async function submitProcPickup() {
  const row = procPickup.row
  if (!row || procPickup.busy) return
  const photos = procPickup.photos.filter(photo => String(photo || '').trim())
  if (!photos.length) return ElMessage.warning('请先添加至少1张取货照片')
  procPickup.busy = true
  try {
    await markProcessingPickedUp(row, request, photos)
    ElMessage.success('取货照片已保存，取货已确认')
    activeDialog.value = ''
    await loadFrontTodo()
  } catch (error) { ElMessage.error(error?.message || '取货确认失败') } finally { procPickup.busy = false }
}
async function submitProcFinish() {
  const row = procFinish.row; if (!row || procFinish.busy) return
  if (procFinish.oldGoldWeight === null || procFinish.oldGoldWeight === '' || !(Number(procFinish.oldGoldWeight) >= 0)) return ElMessage.warning('请先填写来料克重（客户没有旧金请填 0）')
  if (!(Number(procFinish.oldGoldFineness) > 0 && Number(procFinish.oldGoldFineness) <= 1)) return ElMessage.warning('请填写来料成色（0~1）')
  procFinish.busy = true
  try {
    const oid = row.processing_order_id
    // 补金必须先于称重：损耗率与回收屑都依赖店供金登记；尾款在待取货环节收取
    if (Number(procFinish.goldWeight) > 0 || Number(procFinish.downMaterialWeight) > 0) {
      await request(`/api/processing/orders/${oid}/store-gold`, { method: 'POST', body: JSON.stringify({ weight: Number(procFinish.goldWeight || 0), fineness: Number(procFinish.goldFineness || 0.999), price: Number(procFinish.goldPrice || 0), downMaterialWeight: Number(procFinish.downMaterialWeight || 0) }) })
    }
    if (Number(procFinish.finishedWeight) > 0) {
      // 来料随称重一起提交：后端先存来料再核算损耗，否则会拿旧的来料算账
      const weighed = await request(`/api/processing/orders/${oid}/weighing`, { method: 'POST', body: JSON.stringify({ finishedWeight: Number(procFinish.finishedWeight), finishedFineness: procFinish.finishedFineness != null && procFinish.finishedFineness !== '' ? Number(procFinish.finishedFineness) : null, recoveredWeight: procFinish.recoveredWeight != null && procFinish.recoveredWeight !== '' ? Number(procFinish.recoveredWeight) : null, meltedWeight: procFinish.meltWeight != null && procFinish.meltWeight !== '' ? Number(procFinish.meltWeight) : null, billingWeight: Number(procFinish.billingWeight) > 0 ? Number(procFinish.billingWeight) : null, oldGoldWeight: Number(procFinish.oldGoldWeight), oldGoldFineness: Number(procFinish.oldGoldFineness), note: procFinish.note || '' }) })
      if (weighed?.refillMissing) ElMessage.warning('成品比来料+补金还重，已按实际登记；请确认是否漏登记补金')
    }
    const newIncoming = procFinish.incoming.filter(u => !procFinish.baseIncoming.includes(u))
    const newWeigh = procFinish.weighPhotos.filter(u => !procFinish.baseWeigh.includes(u))
    if (newIncoming.length) await request(`/api/processing/orders/${oid}/photos`, { method: 'POST', body: JSON.stringify({ type: 'incoming', urls: newIncoming }) })
    if (newWeigh.length) await request(`/api/processing/orders/${oid}/photos`, { method: 'POST', body: JSON.stringify({ type: 'weigh', urls: newWeigh }) })
    // 来料随完成加工一起提交：后端会先更新来料、再据此计算回收屑与抵扣
    await request(`/api/processing/orders/${oid}/status`, { method: 'PATCH', body: JSON.stringify({
      status: 'COMPLETED',
      oldGoldWeight: Number(procFinish.oldGoldWeight),
      oldGoldFineness: Number(procFinish.oldGoldFineness),
      residualGoldHandling: procFinish.residualGoldHandling,
      residualRecyclePrice: procFinish.residualGoldHandling === 'STORE_DEDUCT' ? Number(procFinish.residualRecyclePrice || 0) : null,
      residualMaterialType: procFinish.residualGoldHandling === 'STORE_DEDUCT' ? procFinish.residualMaterialType : null
    }) })
    ElMessage.success(`加工单 ${row.order_no} 已完成，进入待取货`)
    activeDialog.value = ''
    await loadFrontTodo()
    // 同一张工单完成后重打一次：此时实测值与金额齐全，且不改订单状态
    await confirmProcessingHandover({ processing_order_id: oid, order_no: row.order_no }, { advanceStatus: false })
  } catch (error) { ElMessage.error(error?.message || '完成加工失败') } finally { procFinish.busy = false }
}
async function pickupWithWarranty(row) {
  try {
    if (processingOutstanding(row) > 0) return ElMessage.warning(`尾款未收清（未收 ${money(processingOutstanding(row))}），请先收款`)
    const response = await fetchBackend(`/api/processing/orders/${row.processing_order_id}/warranty`, { headers: { Authorization: `Bearer ${getToken()}` } })
    if (!response.ok) throw new Error('质保单加载失败，请检查登录状态后重试')
    procWarrantyHtml.value = await response.text()
    procManage.value = row
    printPreview.value = { type: 'pwarranty', text: '', html: procWarrantyHtml.value }
    activeDialog.value = 'print'
  } catch (error) { ElMessage.error(error?.message || '调取质保单失败') }
}
async function printProcessingWarranty() {
  const html = procWarrantyHtml.value
  if (!html) return ElMessage.warning('质保单数据不存在')
  if (window.dajin?.print?.system) {
    try {
      const result = await window.dajin.print.system(html, { silent: printSettings.silent, deviceName: printSettings.deviceName, pageSize: 'A4' })
      if (result?.success === false) return ElMessage.warning(result.reason || '打印机未响应')
      ElMessage.success('加工质保单打印任务已发送，请再确认取货并上传照片')
    } catch (error) { return ElMessage.error(error?.message || '加工质保单打印失败') }
  } else {
    const popup = window.open('', '_blank', 'width=900,height=1100')
    if (!popup) return ElMessage.warning('浏览器拦截了打印窗口，请允许弹出窗口后重试')
    let printed = false
    const printPopup = () => { if (printed || popup.closed) return; printed = true; popup.focus(); popup.print(); setTimeout(() => { if (!popup.closed) popup.close() }, 250) }
    popup.addEventListener('load', printPopup, { once: true })
    popup.document.open(); popup.document.write(html); popup.document.close()
    setTimeout(printPopup, 100)
    ElMessage.success('质保单打印窗口已打开，请再确认取货并上传照片')
  }
  activeDialog.value = ''
}
async function confirmProcessingPickup(row) {
  if (processingOutstanding(row) > 0) return ElMessage.warning(`尾款未收清（未收 ${money(processingOutstanding(row))}），请先收款`)
  if (processingRefundOutstanding(row) > 0) return ElMessage.warning(`客户返款未完成（待返 ${money(processingRefundOutstanding(row))}），请先返款`)
  try {
    await ElMessageBox.confirm(`确认客户已领取加工单 ${row.order_no} 的成品？`, '确认取货', { confirmButtonText: '确认取货', cancelButtonText: '取消', type: 'warning' })
    await openProcPickup(row)
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(error?.message || '取货确认失败')
  }
}
// 工单只有后端 /print 一个模板；advanceStatus 为真时才在打印成功后推进到加工中
async function openProcessingPrint(processingOrderId, orderNo, options = {}) {
  processingPrintAdvance.value = options.advanceStatus === true
  const response = await fetchBackend(`/api/processing/orders/${processingOrderId}/print`, { headers: { Authorization: `Bearer ${getToken()}` } })
  if (!response.ok) throw new Error('加工工单加载失败，请检查登录状态后重试')
  processingPrintModel.value = { orderNo, processingOrderId }
  printPreview.value = { type: 'processing', text: '', html: await response.text() }
  activeDialog.value = 'print'
}
async function confirmProcessingHandover(row, options = {}) {
  try {
    await openProcessingPrint(row.processing_order_id, row.order_no, { advanceStatus: options.advanceStatus !== false })
  } catch (error) { ElMessage.error(error?.message || '调取工单失败') }
}
// Starts the job without printing a paper work order; the work order can still be reprinted later.
async function confirmProcessingStart(row) {
  try {
    await request(`/api/processing/orders/${row.processing_order_id}/status`, { method: 'PATCH', body: JSON.stringify({ status: 'PROCESSING' }) })
    ElMessage.success(`加工单 ${row.order_no} 已确认开始加工（未打印工单），可在「加工中」补打`)
    await loadFrontTodo()
  } catch (error) {
    ElMessage.error(error?.message || '确认加工失败')
    await loadFrontTodo()
  }
}
async function openPrintJob(row) {
  try {
    const path = row.job_type === 'SALES'
      ? `/api/order/${row.order_id}/receipt`
      : `/api/processing/orders/${row.order_id}/print`
    const response = await fetchBackend(path, { headers: { Authorization: `Bearer ${getToken()}` } })
    if (!response.ok) throw new Error('打印内容加载失败')
    activePrintJob.value = row
    processingPrintModel.value = row.job_type === 'PROCESSING'
      ? { orderNo: row.order_no, processingOrderId: row.order_id, printJobId: row.job_id }
      : null
    printPreview.value = { type: 'queued', text: '', html: await response.text() }
    activeDialog.value = 'print'
  } catch (error) { ElMessage.error(error?.message || '调取待打印单据失败') }
}
async function completeQueuedPrint(job) {
  await request(`/api/processing/print-jobs/${job.job_id}/done`, { method: 'POST' })
  ElMessage.success(`单据 ${job.order_no} 已标记为已打印`)
  activePrintJob.value = null
  activeDialog.value = ''
  await loadFrontTodo()
}
async function printQueuedJob() {
  const job = activePrintJob.value
  const html = printPreview.value.html
  if (!job || !html) return ElMessage.warning('待打印任务数据不存在')
  if (window.dajin?.print?.system) {
    try {
      const result = await window.dajin.print.system(html, { silent: printSettings.silent, deviceName: printSettings.deviceName, pageSize: job.job_type === 'PROCESSING' ? 'A4' : undefined })
      if (result?.success === false) return ElMessage.warning(result.reason || '打印机未响应')
      await completeQueuedPrint(job)
    } catch (error) { ElMessage.error(error?.message || '打印失败') }
    return
  }
  const popup = window.open('', '_blank', job.job_type === 'SALES' ? 'width=420,height=800' : 'width=640,height=900')
  if (!popup) return ElMessage.warning('浏览器拦截了打印窗口，请允许弹出窗口后重试')
  let opened = false
  const showPrint = () => { if (opened || popup.closed) return; opened = true; popup.focus(); popup.print() }
  popup.addEventListener('load', showPrint, { once: true })
  popup.document.open(); popup.document.write(html); popup.document.close()
  setTimeout(showPrint, 100)
  try {
    await ElMessageBox.confirm('请确认打印机已经正常出纸。确认后该任务会从待打印列表移除。', '确认打印结果', { confirmButtonText: '已正常出纸', cancelButtonText: '保留待打印', type: 'info' })
    await completeQueuedPrint(job)
  } catch (error) { if (!['cancel', 'close'].includes(error)) ElMessage.error(error?.message || '更新打印任务失败') }
}
async function ignorePrintJob(row) {
  try {
    await ElMessageBox.confirm(`确定忽略单据 ${row.order_no} 的打印任务吗？`, '忽略打印任务', { confirmButtonText: '确认忽略', cancelButtonText: '取消', type: 'warning' })
    await request(`/api/processing/print-jobs/${row.job_id}/ignore`, { method: 'POST' })
    ElMessage.success('打印任务已忽略并保留操作记录')
    await loadFrontTodo()
  } catch (error) { if (!['cancel', 'close'].includes(error)) ElMessage.error(error?.message || '忽略打印任务失败') }
}
async function payHandoverOrder(row) {
  try {
    const detail = await request(`/api/order/${row.order_id ?? row.id}`)
    const order = detail?.order || row
    const checkout = handoverCheckout({ ...detail, order })
    cart.value = checkout.items
    selectedMember.value = order.member_id ? { id: order.member_id } : null
    discount.value = Number(order.discount || 1)
    oldMetals.value = []
    orderDraft.value = { ...order, id: order.order_id, orderId: order.order_id, billNo: order.order_no, clientRequestId: order.client_request_id || null, payAmount: order.actual_paid ?? order.pay_amount ?? 0, status: order.status, oldMaterialExcess: order.old_material_excess || 0, settlement: checkout, paymentHistory: detail?.payments || [] }
    settlementMode.value = 'FULL'
    settlementDiscountReason.value = ''
    paymentMethods.value.forEach(method => { method.amount = 0; method.selected = false })
    oldMaterialPayoutMethod.value = order.old_material_payout_method || 'CASH'
    oldMaterialPayoutAmount.value = Number(order.old_material_payout_amount || order.oldMaterialPayoutAmount || order.old_material_excess || 0)
    approval.required = Number(order.status) === 3
    approval.status = approval.required ? '待审批' : ''
    approval.kind = approval.required ? 'ORDER_DISCOUNT' : ''
    activeDialog.value = 'payment'
  } catch (error) { ElMessage.error(error?.message || '调取订单失败') }
}
async function resumeSalesDraft(row) {
  try {
    if (cart.value.length || orderDraft.value) return ElMessage.warning('请先完成或清空当前订单，再继续开单')
    const orderId = row.order_id ?? row.orderId ?? row.id
    const detail = await request(`/api/order/${orderId}`)
    const order = detail?.order || row
    const checkout = handoverCheckout({ ...detail, order })
    cart.value = checkout.items
    selectedMember.value = order.member_id ? members.value.find(member => String(member.id ?? member.member_id) === String(order.member_id)) || { id: order.member_id } : null
    selectedSalesId.value = order.sales_id ?? lastSalesId()
    discount.value = Number(order.discount || 1)
    oldMetals.value = (detail?.oldMaterials || []).map(item => ({ id: uuid(), weight: Number(item.weight || 0), purity: Number(item.purity || 0), materialType: item.material_type || '足金旧料', priceType: item.price_type || '回收金价', note: item.note || '' }))
    orderDraft.value = { ...order, id: orderId, orderId, billNo: order.order_no, clientRequestId: order.client_request_id || null, payAmount: order.actual_paid ?? order.pay_amount ?? 0, status: order.status, oldMaterialExcess: order.old_material_excess || 0, settlement: checkout, paymentHistory: detail?.payments || [] }
    settlementMode.value = 'FULL'
    approval.required = Number(order.status) === 3
    approval.status = approval.required ? '待审批' : ''
    approval.kind = approval.required ? 'ORDER_DISCOUNT' : ''
    activeMenu.value = 'order'
    activeDialog.value = 'payment'
  } catch (error) { ElMessage.error(error?.message || '调取销售草稿失败') }
}
async function cancelHandoverOrder(row) {
  const orderId = row.order_id ?? row.id
  try {
    await ElMessageBox.confirm(`取消待收款单 ${row.order_no} 后会立即释放已占用库存，是否继续？`, '取消订单', { confirmButtonText: '确认取消', cancelButtonText: '返回', type: 'warning' })
    await request(`/api/order/${orderId}/cancel`, { method: 'POST', body: JSON.stringify({ reason: '收银端取消待收款单' }) })
    ElMessage.success('订单已取消，库存占用已释放')
    await Promise.all([loadFrontTodo(), refreshProducts()])
  } catch (error) {
    if (!['cancel', 'close'].includes(error)) ElMessage.error(error?.message || '取消订单失败')
  }
}
async function withdrawSalesOrder(row) {
  const orderId = row.order_id ?? row.orderId ?? row.id
  const status = Number(row.status)
  if (!orderId || [4, 5].includes(status)) return
  const paid = Number(row.actual_paid ?? row.actualPaid ?? row.pay_amount ?? 0)
  const title = status === 1 ? '撤回成品销售单' : '撤回销售草稿'
  const detail = paid > 0
    ? '撤回后原单将从日常单据中消失。系统保留原流水并生成反向流水，库存、会员、旧料和提成同步回滚；客户退款由门店线下完成。需要重办时请新开单。'
    : '撤回后原单将从日常单据中消失。系统释放预占库存并回滚关联状态；需要重办时请新开单。'
  try {
    await ElMessageBox.confirm(`${detail}\n\n确定撤回单据 ${row.order_no || orderId} 吗？`, title, { confirmButtonText: '确认撤回', cancelButtonText: '取消', type: 'warning' })
    const result = await request(`/api/order/${orderId}/withdraw`, { method: 'POST', body: JSON.stringify({ version: row.version }) })
    if (result?.clientRefundRequired) ElMessage.warning(result.message || '账务已冲销，请在线下完成客户退款')
    else ElMessage.success(result?.message || '销售单已撤回')
    await Promise.all([loadPageData(), loadFrontTodo(), refreshProducts()])
  } catch (error) {
    if (!['cancel', 'close'].includes(error)) ElMessage.error(error?.message || '撤回销售单失败')
  }
}
async function withdrawProcessingOrder(row) {
  const orderId = row.processing_order_id ?? row.processingOrderId ?? row.id
  if (!orderId || row.status === 'PICKED_UP') return
  const paid = Number(row.actual_paid_amount ?? row.paid_amount ?? row.paidAmount ?? 0)
  const details = paid > 0
    ? '撤回后原加工单将从日常列表中消失。系统保留原收款和返款流水并生成反向流水，储值、店供补金、留店旧料、提成和审批同步回滚；客户退款由门店线下完成。需要重办时请新开单。'
    : '撤回后原加工单将从日常列表中消失。系统回滚店供补金、留店旧料、提成和审批；需要重办时请新开单。'
  try {
    await ElMessageBox.confirm(`${details}\n\n确定撤回加工单 ${row.order_no || orderId} 吗？`, '撤回加工单', { confirmButtonText: '确认撤回', cancelButtonText: '取消', type: 'warning' })
    const result = await request(`/api/processing/orders/${orderId}/withdraw`, { method: 'POST', body: JSON.stringify({ version: row.version }) })
    if (result?.clientRefundRequired) ElMessage.warning(result.message || '加工单已冲销，请在线下完成客户退款')
    else ElMessage.success(result?.message || '加工单已撤回，后续需新开单')
    await Promise.all([loadFrontTodo(), loadProcessingData(), refreshProducts()])
  } catch (error) {
    if (!['cancel', 'close'].includes(error)) ElMessage.error(error?.message || '撤回加工单失败')
  }
}
const processRows = computed(() => processingItems.value.map(item => ({
  id: item.item_id ?? item.id,
  name: item.name,
  type: item.pricing_unit || '按件',
  base_fee: Number(item.labor_fee || 0),
  duration: item.duration_text || `${item.processing_days || 0}天`,
  process_steps: item.process_steps || '—'
})))
const processingCraftsmen = ref([])
const processingSalespeople = ref([])
const processingSubmitting = ref(false)
const processingDraftId = ref(null)
const processingDraftVersion = ref(null)
const processingDraftOrderNo = ref('')
const processingForm = reactive({
  memberId: '', customerName: '', customerPhone: '', processingItemId: '', quantity: 1, billingWeight: '',
  pickupDate: '', craftsmanId: '', salesId: '', oldGoldWeight: '', oldGoldFineness: 0.999,
  deposit: 0, depositMethod: 'CASH', remark: ''
})
const processingMemberSalesNotice = ref('')
// 导购必须是当前有效的销售账号：会员身上的历史导购若已停用或不是销售，按「无导购（散客）」处理，避免后端 400732
const processingSalesIds = computed(() => new Set(
  (processingSalespeople.value?.length ? processingSalespeople.value : salespeople.value || [])
    .map(person => String(person.user_id ?? person.id))
))
function validSalesId(value) {
  if (value == null || value === '') return ''
  return processingSalesIds.value.has(String(value)) ? String(value) : ''
}
function applyProcessingMemberSales(rawSalesId) {
  const id = validSalesId(rawSalesId)
  processingMemberSalesNotice.value = rawSalesId != null && String(rawSalesId) !== '' && !id
    ? '该会员绑定的导购已停用或不是销售账号，本单按「无导购（散客）」处理，如需归属导购请在下方重新选择'
    : ''
  processingForm.salesId = id
}
function applyPaymentChannels(channels) {  const icons = Object.fromEntries(defaultMethods.map(item => [item.code, item.icon]))
  const groupCodes = ['DOUYIN_GROUP', 'MEITUAN_GROUP']
  groupPaymentMethods.value = (channels || []).filter(channel => Number(channel.status) === 1 && groupCodes.includes(String(channel.channel_code).toUpperCase())).map(channel => ({ code: String(channel.channel_code).toUpperCase(), name: channel.channel_name }))
  paymentMethods.value = reconcilePaymentMethods((channels || []).filter(channel => !groupCodes.includes(String(channel.channel_code).toUpperCase())), paymentMethods.value).map(method => ({
    ...method,
    icon: icons[method.code] || CircleDollarSign
  }))
  const collectionCodes = new Set(paymentMethods.value.map(method => method.code))
  const payoutCodes = new Set(paymentMethods.value.filter(method => method.code !== 'BALANCE').map(method => method.code))
  const firstCollection = paymentMethods.value[0]?.code || ''
  const firstPayout = paymentMethods.value.find(method => method.code !== 'BALANCE')?.code || ''
  if (!collectionCodes.has(processingForm.depositMethod)) processingForm.depositMethod = firstCollection
  if (!collectionCodes.has(procPayMethod.value)) procPayMethod.value = firstCollection
  if (!payoutCodes.has(oldMaterialPayoutMethod.value)) oldMaterialPayoutMethod.value = firstPayout
  if (!payoutCodes.has(recycleForm.payMethod)) recycleForm.payMethod = firstPayout
}
const dialogModel = computed({ get: () => Boolean(activeDialog.value), set: value => { if (!value) closeDialog() } })
const dialogTitle = computed(() => ({ account: '当前账号', login: '收银台登录', oldMetal: '旧金处理', memberSelect: '会员档案', memberCreate: '填写会员资料', payment: '收款结算', print: '打印预览', recycle: '旧料回收', tradein: '以旧换新', approval: '审批状态', conflict: '同步冲突', settings: '设备设置', notifications: '消息通知', procPay: procPayType.value === 'DEPOSIT' ? '加工单收定金' : procPayType.value === 'REFUND' ? '加工单客户返款' : '加工单收尾款', procGold: '补金登记', procWeigh: '称重与损耗登记', procPickup: '确认取货' }[activeDialog.value] || '工作流'))
const dialogHeading = computed(() => ({ account: '账号信息', login: '登录后开始营业', oldMetal: '录入旧金估值', memberSelect: '选择本单会员', memberCreate: '填写会员资料', payment: '组合支付', print: '确认票据', recycle: '验金与回收', tradein: '以旧换新', approval: '等待店长审批', conflict: '需要人工处理', settings: '打印与离线设置', notifications: '超期与库存提醒', procPay: procPayType.value === 'DEPOSIT' ? '收定金' : procPayType.value === 'REFUND' ? '向客户返款' : '收尾款', procGold: '补金登记', procWeigh: '称重与损耗', procPickup: '上传取货照片' }[activeDialog.value] || ''))
const ScanLineIcon = { name: 'ScanLineIcon', setup: () => () => h('span', { class: 'scan-glyph' }, '▦') }
const ws = ref(null)
let clockTimer
let syncTimer
let healthTimer
let configTimer
let wsRetryTimer

function plainPayload(value) {
  return JSON.parse(JSON.stringify(toRaw(value)))
}
function lastSalesId() {
  try { return localStorage.getItem(SALES_DEFAULT_KEY) || null } catch { return null }
}
function rememberSales(id) {
  const value = id == null || id === '' ? '' : String(id)
  try { if (value) localStorage.setItem(SALES_DEFAULT_KEY, value); else localStorage.removeItem(SALES_DEFAULT_KEY) } catch { /* local storage is optional */ }
}
function salesName(id) {
  return salespeople.value.find(item => String(item.user_id) === String(id))?.real_name || '无导购（散客）'
}
function selectOrderMember(member) {
  selectedMember.value = member || null
  selectedSalesId.value = member ? (member.sales_id ?? null) : lastSalesId()
  if (member?.sales_id != null) rememberSales(member.sales_id)
  closeDialog()
}

const filteredProducts = computed(() => filterCatalogProducts(products.value, search.value, activeSubCategory.value ?? activeCategory.value))
const goldReferences = computed(() => resolveGoldReferences(gold.value))
const goldMap = computed(() => goldReferences.value.goldMap)
const goldSpot = computed(() => goldReferences.value.goldSpot)
const recycleSpot = computed(() => goldReferences.value.recycleSpot)
const silverSaleSpot = computed(() => goldReferences.value.silverSaleSpot)
const silverRecycleSpot = computed(() => goldReferences.value.silverRecycleSpot)
watch(recycleSpot, (next, previous) => {
  const current = Number(recycleForm.recyclePrice)
  const wasReference = previous == null || current === Number(previous)
  const untouchedForm = activeDialog.value !== 'recycle' && Number(recycleForm.weight || 0) <= 0
  if (Number.isFinite(next) && (wasReference || untouchedForm)) recycleForm.recyclePrice = next
})
const marketLabel = row => { const status = String(row?.marketStatus || '').toUpperCase(); const state = status === 'OPEN' ? '开市' : status === 'CLOSED' ? '休市' : status === 'ERROR' ? '行情失败·旧价' : status === 'FROZEN' ? '异常冻结' : '—'; return `${row?.source || '—'} ${row?.quoteTime || ''} ${state}`.trim() }
const pricingLabel = row => { if (!row) return '基准 — · 加价 — / 扣减 — · 手动'; const base = row.basePrice == null ? '—' : money(row.basePrice); return `基准 ${base}/g · 加价 ${money(row.markup)} / 扣减 ${money(row.recycleDeduction)} · ${row.pricingMode === 'AUTO' ? '自动' : '手动'}` }
const cartCountLabel = computed(() => {
  const grams = cart.value.filter(isGramItem).reduce((sum, item) => sum + Number(item.weight || 0), 0)
  const pieces = cart.value.filter(item => !isGramItem(item)).reduce((sum, item) => sum + Number(item.qty || 0), 0)
  if (grams > 0 && pieces > 0) return `${grams.toFixed(3)}g · ${pieces}件`
  if (grams > 0) return `${grams.toFixed(3)}g`
  return `${pieces}件`
})
const cartSubtotal = computed(() => orderDraft.value?.settlement?.subtotal ?? cart.value.reduce((sum, item) => sum + (isGramItem(item) ? Number(item.amount || 0) : Number(item.amount || 0) * Number(item.qty || 1)), 0))
const cartLabor = computed(() => orderDraft.value?.settlement?.laborFee ?? cart.value.reduce((sum, item) => sum + (isGramItem(item) ? Number(item.laborFee || 0) : Number(item.laborFee || 0) * Number(item.qty || 1)), 0))
const oldDeduct = computed(() => orderDraft.value?.settlement?.oldMaterialValue ?? oldMetals.value.reduce((sum, m) => sum + oldGoldDeduction(m.weight, m.purity, goldMap.value[m.priceType] || recycleSpot.value), 0))
const discounted = computed(() => cartSubtotal.value * discount.value)
const oldMaterialSettlement = computed(() => calculateOldMaterialSettlement(cartSubtotal.value, discount.value, cartLabor.value, oldDeduct.value))
const appliedOldDeduct = computed(() => oldMaterialSettlement.value.appliedDeduction)
const oldMaterialExcess = computed(() => oldMaterialSettlement.value.excessPayout)
const customerPayoutAmount = computed(() => Math.max(0, Number(oldMaterialPayoutAmount.value || oldMaterialExcess.value || 0)))
const payable = computed(() => oldMaterialSettlement.value.payable)
const paymentDue = computed(() => Number(orderDraft.value?.remaining ?? orderDraft.value?.remaining_due ?? payable.value))
const paidAmount = computed(() => paymentMethods.value.reduce((sum, p) => sum + Number(p.amount || 0), 0))
const paymentDifferenceCents = computed(() => Math.round(paidAmount.value * 100) - Math.round(paymentDue.value * 100))
const paymentRemaining = computed(() => Math.max(0, -paymentDifferenceCents.value) / 100)
const paymentOver = computed(() => Math.max(0, paymentDifferenceCents.value) / 100)
const paymentBalanced = computed(() => paymentDue.value <= 0 || (paidAmount.value > 0 && paymentDifferenceCents.value <= 0))
const oldMetalPurity = computed(() => parsePurity(oldMetalForm.purityChoice, oldMetalForm.customPurity))
const recyclePurity = computed(() => parsePurity(recycleForm.purityChoice, recycleForm.customPurity))
const recycleAmount = computed(() => { const base = Number(recycleForm.weight || 0) * finenessFactor(recyclePurity.value) * Number(recycleForm.recyclePrice || recycleSpot.value); return Math.max(0, base * (1 - Number(recycleForm.deductLossRate || 0) / 100)) })
const tradeDiff = computed(() => Number(tradeForm.newValue || 0) - Number(tradeForm.oldValue || 0))
const tradeOldPurity = computed(() => parsePurity(tradeOldForm.purityChoice, tradeOldForm.customPurity))
const tradeOldTotal = computed(() => tradeOldMetals.value.reduce((sum, item) => sum + oldGoldDeduction(item.weight, item.purity, recycleSpot.value), 0))
const shiftTotal = computed(() => shiftRows.value.reduce((sum, row) => sum + Number(row.amount || 0), 0))
const shiftCashSystem = computed(() => { const meta = shiftMeta.value?.cashSystem; return meta == null ? Number(shiftRows.value.find(row => String(row.pay_method || '').toUpperCase() === 'CASH')?.amount || 0) : Number(meta) })
const shiftCashDifference = computed(() => Number(shiftCash.value || 0) - shiftCashSystem.value)
const selectedProcessingItem = computed(() => processingItems.value.find(item => String(item.item_id ?? item.id) === String(processingForm.processingItemId)) || null)
const processingLaborFee = computed(() => Math.round(Number(selectedProcessingItem.value?.labor_fee || 0) * (selectedProcessingItem.value?.pricing_unit === '按克' ? Number(processingForm.billingWeight || 0) : Math.max(1, Number(processingForm.quantity || 1))) * 100) / 100)
const processingResidualDeduction = computed(() => {
  return 0
})
const processingDue = computed(() => Math.max(0, processingLaborFee.value))
// 成色因子（0.995 及以上按整克）与旧金折抵统一走 cashier.js，避免各页面自己写漏掉足金线规则
// 补金参考 = 成品实重 − 融后金重（成品超出客户融后金的部分由客户付，含店里下料；损耗由店里承担）
// 只做预填与提示，柜面确认后可手改，最终以后端登记值为准
const procFinishGold = computed(() => goldBalance({ melt: procFinish.meltWeight, finished: procFinish.finishedWeight }))
watch(procFinishGold, value => {
  const current = procFinish.goldWeight
  const untouched = current === null || current === '' || Number(current) === 0
  if (untouched) procFinish.goldWeight = value.topUp > 0 ? value.topUp : null
})
const procGoldDialogBalance = computed(() => goldBalance({ melt: procManage.value?.melt_weight, finished: procManage.value?.finished_weight }))
// 按克项目：成品实重填完后自动带出「计费总克重」（未手改时），工费 = 项目单价 × 计费总克重
const procFinishBilling = computed(() => {
  const detail = procFinish.detail
  const byGram = detail && detail.pricing_unit === '按克'
  const unitFee = Number(detail?.unit_labor_fee || 0)
  const weight = Number(procFinish.billingWeight || 0)
  return { byGram, unitFee, weight, laborFee: byGram ? Math.round(unitFee * weight * 100) / 100 : Number(detail?.labor_fee || 0) }
})
watch(() => procFinish.finishedWeight, value => {
  if (!procFinishBilling.value.byGram) return
  const current = procFinish.billingWeight
  const untouched = current === null || current === '' || Number(current) === 0
  if (untouched && Number(value) > 0) procFinish.billingWeight = Number(value)
})
watch(procGoldDialogBalance, value => {
  const current = procGoldForm.weight
  const untouched = current === null || current === '' || Number(current) === 0
  if (untouched && value.topUp > 0) procGoldForm.weight = value.topUp
})
// 回收屑 = 融后金重 − 成品实重，不足按 0（成品比融后金重还重时只有补金）；补金不参与，由后端最终结算
const procFinishResidualWeight = computed(() => {
  if (procFinish.residualGoldHandling !== 'STORE_DEDUCT') return 0
  const melt = Number(procFinish.meltWeight || 0)
  const base = melt > 0 ? melt : Number(procFinish.oldGoldWeight || 0) * finenessFactor(procFinish.oldGoldFineness)
  const finished = Number(procFinish.finishedWeight || 0)
  return Math.max(0, Math.round((base - finished) * 1000) / 1000)
})
const procFinishResidualDeduction = computed(() => {
  if (procFinish.residualGoldHandling !== 'STORE_DEDUCT') return 0
  return Math.round(procFinishResidualWeight.value * procFinishRecyclePrice.value * 100) / 100
})
// 回收价：手工填的优先，留 0 取系统设置的足金回收价
const procFinishRecyclePrice = computed(() => {
  const custom = Number(procFinish.residualRecyclePrice || 0)
  return custom > 0 ? custom : Number(recycleSpot.value || 0)
})

function money(value) { return new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY', minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(Number(value || 0)) }
function purityText(value) {
  const percentage = Number(value || 0) * 100
  return `${percentage.toFixed(2).replace(/\.00$/, '')}%`
}
function paymentLabel(value) {
  const code = String(value || '').trim()
  if (!code || code === '0') return '未标注'
  const configured = Object.fromEntries([...paymentMethods.value, ...groupPaymentMethods.value].map(method => [method.code, method.name]))
  return code.split('+').map(part => configured[part.trim().toUpperCase()] || paymentLabels[part.trim().toUpperCase()] || part.trim()).join('+')
}
function productGoldPrice(product) {
  const type = String(product?.gold_type || product?.goldType || '').trim()
  const direct = goldMap.value[type]
  if (direct != null) return Number(direct)
  const match = gold.value.find(item => String(item.code || item.type_code || '') === type)
  return Number(match?.price || goldSpot.value)
}
function isGramItem(item) { return Number(item?.priceType ?? item?.price_type) === 1 }
function productAmount(product) {
  if (Number(product.price_type) === 1) return Number(product.weight || 0) * productGoldPrice(product)
  return Number(product.sale_price || 0)
}
// 按克商品不依赖档案克重：实际克重由柜台称重后录入，这里只提示单价与录入方式。
function productDetail(product) {
  const type = Number(product.price_type ?? product.priceType)
  if (type === 1) {
    const weight = Number(product.weight || 0)
    return weight > 0 ? `${weight.toFixed(3)}g × ${money(productGoldPrice(product))}` : `按克 ${money(productGoldPrice(product))}/g · 待称重录入`
  }
  return type === 3 ? '标签价' : '按件计价'
}
function productImage(product) {
  const value = product?.image ?? product?.images
  if (!value || value === '[]' || value === 'null') return ''
  if (Array.isArray(value)) return prefixFileUrl(value[0] || '')
  if (typeof value === 'string' && value.trim().startsWith('[')) {
    try { const parsed = JSON.parse(value); return prefixFileUrl(Array.isArray(parsed) ? (parsed[0] || '') : '') } catch { return '' }
  }
  return prefixFileUrl(String(value))
}
function prefixFileUrl(url) {
  if (!url) return ''
  return String(url).startsWith('/') ? (apiBase() + url) : String(url)
}
function addProduct(product) {
  const goodsId = product.goods_id ?? product.id
  const stock = Number(product.available_stock ?? product.stock ?? 0)
  if (stock <= 0) return ElMessage.warning('该商品已售罄，请刷新商品列表')
  const gramPriced = Number(product.price_type) === 1
  const existing = cart.value.find(i => i.goodsId === goodsId)
  if (gramPriced && existing) {
    // 按克商品按"顾客买多少克"结算，档案克重不再代表单件重量：重复扫码不累加，直接让柜台在克重框汇总录入。
    ElMessage.info('该商品已在清单中，请在「克重(g)」框录入实际克重')
  } else if (existing && existing.qty >= stock) return ElMessage.warning(`该商品仅剩 ${stock} 件`)
  else if (existing) existing.qty += 1
  else cart.value.push({ goodsId, barcode: product.barcode, name: product.name, category: product.category, goldType: product.gold_type || product.goldType || '足金', weight: gramPriced ? 0 : Number(product.weight || 0), priceType: Number(product.price_type || 1), unitPrice: gramPriced ? productGoldPrice(product) : Number(product.sale_price || 0), amount: gramPriced ? 0 : productAmount(product), laborFee: 0, qty: 1, detail: productDetail(gramPriced ? { ...product, weight: 0 } : product), stock, image: productImage(product) })
  toast.value = `${product.name} 已加入清单`
  setTimeout(() => { toast.value = '' }, 1400)
}
function scanEnter(event) {
  const code = event.target.value.trim()
  const product = products.value.find(p => p.barcode === code)
  if (product) { addProduct(product); search.value = ''; event.target.value = '' }
}
function removeItem(item) { cart.value = cart.value.filter(i => i !== item) }
function setQty(item, delta) {
  if (isGramItem(item)) return
  const next = Math.max(1, item.qty + delta)
  if (delta > 0 && next > Number(item.stock || 0)) return ElMessage.warning(`该商品仅剩 ${item.stock} 件`)
  item.qty = next
}
function setLabor(item, value) { item.laborFee = Math.max(0, Number(value || 0)) }
function setDiscount(value) {
  discount.value = Number(value)
  // The order must be created first so the backend can issue the approval id.
  approval.required = false
  approval.status = ''
  approval.id = null
  approval.kind = ''
  approval.message = discount.value < config.discountThreshold ? `折扣低于${Math.round(config.discountThreshold * 100)}折，需要店长审批` : ''
}
function resetCart() { cart.value = []; oldMetals.value = []; tradeOldMetals.value = []; selectedMember.value = null; selectedSalesId.value = lastSalesId(); discount.value = 1; approval.required = false; approval.status = ''; approval.id = null; approval.message = ''; approval.kind = ''; orderDraft.value = null; settlementMode.value = 'FULL'; settlementDiscountReason.value = ''; oldMaterialPayoutMethod.value = payoutMethods.value[0]?.code || 'CASH'; oldMaterialPayoutAmount.value = 0; paymentMethods.value.forEach(p => { p.amount = 0; p.selected = false }) }
function addOldMetal() {
  if (Number(oldMetalForm.weight) <= 0) return ElMessage.warning('请输入旧金克重')
  const purity = oldMetalPurity.value
  if (!(purity > 0 && purity <= 1)) return ElMessage.warning('成色请输入 0 到 100% 之间的数值')
  oldMetals.value.push({ id: uuid(), weight: Number(oldMetalForm.weight), purity, materialType: oldMetalForm.materialType, priceType: oldMetalForm.priceType, note: oldMetalForm.note })
  oldMetalForm.weight = 0; oldMetalForm.note = ''
}
function addTradeOldMetal() {
  if (Number(tradeOldForm.weight) <= 0) return ElMessage.warning('请输入旧料克重')
  const purity = tradeOldPurity.value
  if (!(purity > 0 && purity <= 1)) return ElMessage.warning('成色请输入 0 到 100% 之间的数值')
  tradeOldMetals.value.push({ id: uuid(), weight: Number(tradeOldForm.weight), purity, materialType: tradeOldForm.materialType, price: recycleSpot.value, note: tradeOldForm.note })
  tradeOldForm.weight = 0; tradeOldForm.note = ''
}
function removeTradeOldMetal(item) { tradeOldMetals.value = tradeOldMetals.value.filter(row => row.id !== item.id) }
function removeOldMetal(metal) { oldMetals.value = oldMetals.value.filter(x => x.id !== metal.id) }
function loadHeldOrders() {
  try {
    const saved = JSON.parse(localStorage.getItem(HELD_ORDERS_KEY) || '[]')
    heldOrders.value = Array.isArray(saved) ? saved : []
  } catch { heldOrders.value = [] }
}
function persistHeldOrders() { localStorage.setItem(HELD_ORDERS_KEY, JSON.stringify(plainPayload(heldOrders.value))) }
function heldStatus(held) {
  const status = held?.approval?.status || ''
  return status === '已通过' ? '已审批' : status === '已驳回' ? '已驳回' : '待审批'
}
function holdCurrentOrder() {
  if (!orderDraft.value) return ElMessage.warning('当前没有可挂起的订单')
  const held = {
    heldId: orderDraft.value.clientRequestId || uuid(),
    createdAt: new Date().toISOString(),
    orderDraft: plainPayload(orderDraft.value),
    cart: plainPayload(cart.value),
    oldMetals: plainPayload(oldMetals.value),
    selectedMember: plainPayload(selectedMember.value),
    selectedSalesId: selectedSalesId.value,
    discount: discount.value,
    approval: plainPayload(approval),
    paymentMethods: plainPayload(paymentMethods.value),
    oldMaterialPayoutMethod: oldMaterialPayoutMethod.value
  }
  const index = heldOrders.value.findIndex(item => item.heldId === held.heldId)
  if (index >= 0) heldOrders.value.splice(index, 1, held)
  else heldOrders.value.unshift(held)
  persistHeldOrders()
  resetCart()
  closeDialog()
  ElMessage.success('订单已挂起，可继续接下一单')
}
async function restoreHeldOrder(held) {
  if (cart.value.length || orderDraft.value) return ElMessage.warning('请先完成或清空当前订单，再恢复待结算单')
  cart.value = plainPayload(held.cart || [])
  oldMetals.value = plainPayload(held.oldMetals || [])
  selectedMember.value = plainPayload(held.selectedMember || null)
  selectedSalesId.value = held.selectedSalesId ?? held.orderDraft?.sales_id ?? lastSalesId()
  discount.value = Number(held.discount || 1)
  Object.assign(approval, held.approval || { required: false, status: '', id: null, message: '' })
  paymentMethods.value = plainPayload(held.paymentMethods || paymentMethods.value).map(method => ({ ...method, selected: method.selected ?? Number(method.amount) > 0 }))
  oldMaterialPayoutMethod.value = held.oldMaterialPayoutMethod || payoutMethods.value[0]?.code || 'CASH'
  oldMaterialPayoutAmount.value = Number(held.oldMaterialPayoutAmount || oldMaterialExcess.value || 0)
  orderDraft.value = plainPayload(held.orderDraft || null)
  heldOrders.value = heldOrders.value.filter(item => item.heldId !== held.heldId)
  persistHeldOrders()
  activeDialog.value = approval.required && approval.status !== '已通过' ? 'approval' : 'payment'
}
async function refreshHeldApproval(held) {
  const approvalId = held?.approval?.id
  if (!approvalId || !online.value || !getToken()) return ElMessage.info('审批单尚未同步到云端')
  try {
    const result = await request(`/api/approval/${approvalId}`)
    const statusCode = result?.approval?.status ?? result?.status
    const status = Number(statusCode) === 3 ? '已通过' : Number(statusCode) === 4 ? '已驳回' : '待审批'
    held.approval = { ...(held.approval || {}), status, required: status !== '已通过' }
    persistHeldOrders()
    ElMessage.success(`审批状态：${status}`)
  } catch (error) { ElMessage.error(error.message) }
}
function distributePayment(method) {
  const target = paymentMethods.value.find(p => p.code === method.code)
  if (!target) return
  activatePaymentMethod(target, paymentRemaining.value)
}
function applyEvenPayment() {
  const active = paymentInputMethods(paymentMethods.value)
  if (!active.length) return
  const totalCents = Math.round((settlementMode.value === 'FULL' ? paymentDue.value : paidAmount.value) * 100)
  const eachCents = Math.floor(totalCents / active.length)
  active.forEach((p, index) => { p.amount = (index === active.length - 1 ? totalCents - eachCents * (active.length - 1) : eachCents) / 100 })
}
function paymentMethodLabel() {
  return paymentMethods.value.filter(p => p.amount > 0).map(p => `${p.name}:${money(p.amount)}`).join(' + ')
}

async function loadLocalData() {
  products.value = await localProducts()
  gold.value = await localGold()
  members.value = await localMembers()
  if (!products.value.length) {
    products.value = [
      { goods_id: 1, barcode: '697000000001', name: '古法传承手镯', category_id: 1, category: '成品黄金', weight: 32.5, sale_price: 612, price_type: 1, stock: 8 },
      { goods_id: 2, barcode: '697000000002', name: '足金素圈戒指', category_id: 1, category: '成品黄金', weight: 5.2, sale_price: 612, price_type: 1, stock: 23 },
      { goods_id: 3, barcode: '697000000003', name: '幸运转运珠', category_id: 1, category: '成品黄金', weight: 2.1, sale_price: 698, price_type: 2, stock: 15 },
      { goods_id: 4, barcode: '697000000004', name: 'S925银耳钉', category_id: 3, category: '银饰', weight: 1.8, sale_price: 268, price_type: 2, stock: 31 },
      { goods_id: 5, barcode: '697000000005', name: '手工编绳加工', category_id: 4, category: '加工', weight: 0, sale_price: 58, price_type: 2, stock: 999 },
      { goods_id: 6, barcode: '697000000006', name: '18K金项链', category_id: 2, category: 'K金', weight: 8.6, sale_price: 428, price_type: 1, stock: 6 }
    ]
  }
  if (window.dajin?.db?.seed) await window.dajin.db.seed({ products: plainPayload(products.value), gold: plainPayload(gold.value), members: plainPayload(members.value) })
}
async function refreshProducts() {
  if (!online.value || !getToken()) return
  const result = await request('/api/goods/list?page=1&size=200&status=1')
  if (!Array.isArray(result?.records)) return
  products.value = result.records.map(item => ({ ...item, id: item.goods_id ?? item.id }))
  const cachedProducts = plainPayload(products.value)
  if (window.dajin?.db?.seed) await window.dajin.db.seed({ products: cachedProducts, replaceProducts: true })
  else localStorage.setItem('dajin_products', JSON.stringify(cachedProducts))
}
async function refreshMembers() {
  if (!online.value || !getToken()) return
  const result = await request('/api/member/list?page=1&size=200')
  if (!Array.isArray(result?.records)) return
  const selectedId = selectedMember.value?.id ?? selectedMember.value?.member_id
  members.value = result.records.map(item => ({ ...item, id: item.member_id ?? item.id }))
  selectedMember.value = selectedId == null ? selectedMember.value : (members.value.find(item => String(item.id) === String(selectedId)) || null)
  memberHits.value = []
  const cachedMembers = plainPayload(members.value)
  if (window.dajin?.db?.seed) await window.dajin.db.seed({ members: cachedMembers, replaceMembers: true })
  else localStorage.setItem('dajin_members', JSON.stringify(cachedMembers))
  if (memberKeyword.value.trim() && activeDialog.value === 'memberSelect') await searchMembersRemote()
}
async function refreshOnlineData() {
  if (!online.value || !getToken()) return
  try {
    const responses = await Promise.allSettled([
      request('/api/goods/list?page=1&size=200&status=1'),
      request('/api/goods/categories'),
      request('/api/stock/old-material/types'),
      request('/api/gold-price/current'),
      request('/api/member/list?page=1&size=200'),
      request('/api/pay/methods'),
      request('/api/system/store-info'),
      request('/api/system/config'),
      request('/api/processing/salespeople')
    ])
    const [goodsResult, categoryResult, typesResult, goldResult, memberResult, payResult, storeResult, configResult, salespeopleResult] = responses
    if (goodsResult.status === 'fulfilled' && Array.isArray(goodsResult.value?.records)) products.value = goodsResult.value.records.map(item => ({ ...item, id: item.goods_id ?? item.id }))
    if (categoryResult.status === 'fulfilled' && Array.isArray(categoryResult.value)) {
      categoryRows.value = categoryResult.value
    }
    if (goldResult.status === 'fulfilled' && goldResult.value?.length) gold.value = goldResult.value
    if (memberResult.status === 'fulfilled' && memberResult.value?.records) members.value = memberResult.value.records.map(item => ({ ...item, id: item.member_id ?? item.id }))
    if (salespeopleResult.status === 'fulfilled' && Array.isArray(salespeopleResult.value)) {
      salespeople.value = salespeopleResult.value
      if (selectedSalesId.value == null) selectedSalesId.value = lastSalesId()
      processingForm.salesId = validSalesId(processingForm.salesId) || validSalesId(lastSalesId())
    }
    if (payResult.status === 'fulfilled' && Array.isArray(payResult.value)) applyPaymentChannels(payResult.value)
    if (storeResult.status === 'fulfilled' && storeResult.value?.store_name) cashier.storeName = storeResult.value.store_name
    if (configResult.status === 'fulfilled' && Array.isArray(configResult.value)) {
      const configRows = configResult.value
      const keyOf = item => item?.config_key ?? item?.configKey
      const valueOf = item => item?.config_value ?? item?.configValue
      const threshold = configRows.find(item => keyOf(item) === 'discount_threshold')
      const recycleLimit = configRows.find(item => keyOf(item) === 'recycle_approval_limit')
      if (threshold && Number(valueOf(threshold)) > 0) config.discountThreshold = Number(valueOf(threshold))
      if (recycleLimit && Number(valueOf(recycleLimit)) >= 0) config.recycleLimit = Number(valueOf(recycleLimit))
    }
    if (typesResult.status === 'fulfilled' && Array.isArray(typesResult.value)) {
      const names = typesResult.value.filter(item => Number(item.status) === 1).map(item => String(item.name || '').trim()).filter(Boolean)
      if (names.length) {
        oldMaterialTypes.value = [...new Set(names)]
        if (!oldMaterialTypes.value.includes(oldMetalForm.materialType)) oldMetalForm.materialType = oldMaterialTypes.value[0]
        if (!oldMaterialTypes.value.includes(recycleForm.materialType)) recycleForm.materialType = oldMaterialTypes.value[0]
        if (!oldMaterialTypes.value.includes(tradeOldForm.materialType)) tradeOldForm.materialType = oldMaterialTypes.value[0]
      }
    }
    const cachePayload = { products: plainPayload(products.value), gold: plainPayload(gold.value), members: plainPayload(members.value) }
    if (window.dajin?.db?.seed) await window.dajin.db.seed({ ...cachePayload, replaceProducts: true, replaceMembers: true })
    else {
      localStorage.setItem('dajin_products', JSON.stringify(cachePayload.products))
      localStorage.setItem('dajin_gold', JSON.stringify(cachePayload.gold))
      localStorage.setItem('dajin_members', JSON.stringify(cachePayload.members))
    }
    const rejected = responses.filter(item => item.status === 'rejected')
    if (rejected.length) console.warn('partial online refresh', rejected.map(item => item.reason?.message || String(item.reason)).join('; '))
  } catch (error) { console.warn('online refresh skipped', error.message) }
}
async function doSync() {
  if (!online.value || !getToken() || syncing.value) return
  syncing.value = true
  try {
    const result = await syncQueue((item, error) => { conflictCount.value += 1; toast.value = `同步冲突：${item.path}，请人工处理`; console.warn(error) }, online.value)
    await loadConflicts()
    if (result.synced) {
      toast.value = `已同步 ${result.synced} 条离线单据`
      await Promise.allSettled([refreshProducts(), refreshMembers()])
    }
  } finally { syncing.value = false; setTimeout(() => { toast.value = '' }, 2200) }
}
function parseConflictPayload(payload) {
  if (payload && typeof payload === 'object') return payload
  try { return JSON.parse(payload || '{}') } catch { return { value: String(payload || '') } }
}
function conflictFields(row) {
  if (!row) return []
  const local = parseConflictPayload(row.local_payload ?? row.localPayload)
  const cloud = parseConflictPayload(row.server_payload ?? row.serverPayload)
  const keys = [...new Set([...Object.keys(local), ...Object.keys(cloud)])]
  return keys.map(key => ({ key, local: local[key], cloud: cloud[key], changed: JSON.stringify(local[key]) !== JSON.stringify(cloud[key]) }))
}
async function loadConflicts() {
  conflictRows.value = await localConflicts()
  conflictCount.value = conflictRows.value.length
}
async function resolveConflict(row, resolution) {
  if (resolution === 'LOCAL' && row.queue_id == null && row.queueId == null) {
    const payload = parseConflictPayload(row.local_payload ?? row.localPayload)
    await enqueueWithId(row.path, payload, row.client_request_id || row.clientRequestId || uuid(), 'POST', payload.expectedVersion)
  }
  await resolveLocalConflict(row, resolution)
  await loadConflicts()
  if (resolution === 'LOCAL') { ElMessage.success('已保留本地版本，等待重新同步'); if (online.value) doSync() }
  else ElMessage.success('已保留云端版本，本地变更已丢弃')
  if (!conflictRows.value.length) closeDialog()
}
function applyApprovalDecision(approvalId, statusValue) {
  const status = Number(statusValue) === 3 ? '已通过' : Number(statusValue) === 4 ? '已驳回' : '待审批'
  let matched = false
  if (approval.id != null && String(approval.id) === String(approvalId)) {
    approval.status = status
    approval.required = status === '待审批'
    matched = true
    if (status === '已通过') activeDialog.value = 'payment'
    if (status === '已驳回' && approval.kind === 'PAYMENT_DISCOUNT') { approval.message = '优惠审批已驳回，请修改实收金额后重新提交'; activeDialog.value = 'payment' }
  }
  heldOrders.value.forEach(held => {
    if (held.approval?.id != null && String(held.approval.id) === String(approvalId)) {
      held.approval = { ...held.approval, status, required: status !== '已通过' }
      matched = true
    }
  })
  if (matched) {
    persistHeldOrders()
    noticeCount.value += 1
  }
}
function setupSocket() {
  if (!getToken() || !online.value || ws.value) return
  try {
    console.info('[dajin-ws] connecting')
    ws.value = openBackendSocket({
      onopen: () => console.info('[dajin-ws] connected'),
      onmessage: async event => {
        try {
          const message = JSON.parse(event.data)
          console.info('[dajin-ws] message:', message.type)
           if (message.type === 'GOLD_PRICE_UPDATED') { const update = message.data; gold.value = mergeGoldPriceUpdate(gold.value, update); await window.dajin?.db?.seed?.({ gold: plainPayload(gold.value) }); toast.value = `${update.priceType}金价已更新` }
          if (message.type === 'APPROVAL_DECIDED') applyApprovalDecision(message.data?.approvalId ?? message.data?.id, message.data?.status)
          if (message.type === 'APPROVAL_CREATED') noticeCount.value += 1
          if (message.type === 'REMINDER_REFRESH') loadNotifications()
          if (message.type === 'CATEGORIES_UPDATED') refreshOnlineData()
          if (shouldRefreshCatalog(message.type)) {
            refreshProducts().catch(error => console.warn('[dajin-catalog] realtime refresh skipped:', error?.message || error))
          }
          if (message.type === 'ORDER_COMPLETED') {
            refreshMembers().catch(error => console.warn('[dajin-member] order refresh skipped:', error?.message || error))
          }
          if (message.type === 'MEMBER_UPDATED') refreshMembers().catch(error => console.warn('[dajin-member] refresh skipped:', error?.message || error))
          if (message.type === 'PAY_CHANNELS_UPDATED' || message.type === 'CONFIG_UPDATED' || message.type === 'STORE_UPDATED') refreshOnlineData()
          if (shouldRefreshProcessingReferences(message.type)) loadProcessingData()
          if (message.type === 'PROCESSING_ORDER_CREATED' || message.type === 'PROCESSING_ORDER_UPDATED' || message.type === 'PRINT_JOB_UPDATED') loadFrontTodo()
          if (message.type === 'OLD_MATERIAL_TYPES_UPDATED') refreshOldMaterialTypes()
          if (message.type === 'MOBILE_ORDER_CREATED') { noticeCount.value += 1; toast.value = `收到移动开单 ${message.data?.orderNo || ''}`; if (activeMenu.value === 'bill') loadPageData(); setTimeout(() => { toast.value = '' }, 2200) }
          if (message.type === 'PROCESSING_HANDOVER' || message.type === 'ORDER_HANDOVER') { noticeCount.value += 1; toast.value = `手机端转交：${message.data?.orderNo || '新单据'}`; loadFrontTodo(); setTimeout(() => { toast.value = '' }, 2200) }
          if (message.type === 'PRINT_JOB_NEW') { noticeCount.value += 1; toast.value = `收到待打印单据 ${message.data?.orderNo || ''}`; loadFrontTodo(); setTimeout(() => { toast.value = '' }, 2200) }
        } catch { /* ignore malformed push */ }
      },
      onclose: () => {
        console.warn('[dajin-ws] disconnected')
        ws.value = null
        if (online.value && getToken() && !wsRetryTimer) {
          wsRetryTimer = setTimeout(() => { wsRetryTimer = null; refreshOnlineData(); setupSocket() }, 2000)
        }
      }
    })
  } catch (error) { console.warn('[dajin-ws] connect error:', error?.message || error); ws.value = null }
}
function setBackendReachable(value) {
  const wasOnline = online.value
  backendReachable.value = value
  if (wasOnline !== online.value) console.info(`[dajin-health] backend ${online.value ? 'online' : 'offline'} (${apiBase()})`)
  if (!value && ws.value) { ws.value.close(); ws.value = null }
  if (!wasOnline && online.value) { refreshOnlineData(); doSync(); setupSocket() }
}
async function refreshOldMaterialTypes() {
  if (!online.value || !getToken()) return
  try {
    const rows = await request('/api/stock/old-material/types')
    const types = (Array.isArray(rows) ? rows : []).filter(item => Number(item.status) === 1).map(item => String(item.name || '').trim()).filter(Boolean)
    if (!types.length) return
    oldMaterialTypes.value = [...new Set(types)]
    if (!oldMaterialTypes.value.includes(oldMetalForm.materialType)) oldMetalForm.materialType = oldMaterialTypes.value[0]
    if (!oldMaterialTypes.value.includes(recycleForm.materialType)) recycleForm.materialType = oldMaterialTypes.value[0]
    if (!oldMaterialTypes.value.includes(tradeOldForm.materialType)) tradeOldForm.materialType = oldMaterialTypes.value[0]
  } catch (error) { console.warn('[dajin-config] old material types refresh skipped:', error?.message || error) }
}
async function probeBackend() {
  // Chromium's navigator.onLine is only a network-interface hint. A reachable
  // store backend is the source of truth, including localhost and LAN servers.
  browserOnline.value = navigator.onLine
  if (!backendConfigured.value) return false
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 2200)
  try {
    const response = await fetchBackend('/actuator/health', { cache: 'no-store', signal: controller.signal, timeoutMs: 2200 })
    const healthy = response.ok && (await response.json().catch(() => null))?.status === 'UP'
    setBackendReachable(healthy)
    return healthy
  } catch (error) {
    console.warn('[dajin-health] backend unreachable:', error?.message || error)
    setBackendReachable(false)
    return false
  } finally { clearTimeout(timeout) }
}
function networkChanged() { browserOnline.value = navigator.onLine; probeBackend() }
function windowFocused() { probeBackend() }
function sessionExpired() {
  ws.value?.close()
  ws.value = null
  loginForm.password = ''
  activeDialog.value = 'login'
  ElMessage.warning('登录已过期，请重新登录')
}

async function submitOrder() {
  if (!cart.value.length) return ElMessage.warning('请先选择商品')
  if (checkoutSubmitting.value) return
  const gramMissing = cart.value.find(item => isGramItem(item) && !(Number(item.weight) > 0))
  if (gramMissing) return ElMessage.warning(`请先录入「${gramMissing.name}」的实际克重后再结算`)
  const gramOver = cart.value.find(item => isGramItem(item) && Number(item.weight) > Number(item.stock || 0))
  if (gramOver) return ElMessage.warning(`「${gramOver.name}」克重超过可售库存（${Number(gramOver.stock || 0).toFixed(3)}g）`)
  if (selectedSalesId.value == null || selectedSalesId.value === '') {
    try { await ElMessageBox.confirm('本单未选择导购，将不计销售提成，是否继续？', '未选择导购', { confirmButtonText: '继续提交', cancelButtonText: '返回选择', type: 'warning' }) } catch { return }
  }
  if (shouldReusePendingSale(orderDraft.value)) {
    activeDialog.value = approval.required && approval.status !== '已通过' ? 'approval' : 'payment'
    return
  }
  const body = {
    memberId: selectedMember.value?.id || null,
    discount: discount.value,
    oldMaterialDeduct: appliedOldDeduct.value,
    oldMaterialPayoutMethod: oldMaterialExcess.value > 0 ? oldMaterialPayoutMethod.value : null,
    oldMaterialPayoutAmount: oldMaterialExcess.value > 0 ? customerPayoutAmount.value : 0,
    laborFee: cartLabor.value,
    payAmount: 0,
    payMethod: null,
    oldMaterials: oldMetals.value.map(m => ({ materialType: m.materialType || '足金旧料', weight: m.weight, purity: m.purity, priceType: m.priceType, note: m.note })),
    salesId: selectedSalesId.value || null,
    remark: oldMetals.value.length ? `旧金抵扣${oldMetals.value.length}件` : '',
    items: cart.value.map(item => ({ goodsId: item.goodsId, itemName: item.name, weight: Number(item.weight) > 0 ? item.weight : null, unitPrice: item.unitPrice, laborFee: item.laborFee, qty: isGramItem(item) ? 1 : item.qty, subtotal: isGramItem(item) ? item.amount : item.amount * item.qty, goldType: item.goldType, priceType: item.priceType })),
    clientRequestId: uuid()
  }
  checkoutSubmitting.value = true
  try {
    const result = await requestOrQueue('/api/order/create', body, { backendAvailable: online.value })
    if (body.salesId != null) { selectedSalesId.value = body.salesId; rememberSales(body.salesId) }
    const orderId = result?.orderId ?? result?.order_id ?? result?.id ?? null
    orderDraft.value = { ...body, ...result, id: orderId, orderId, clientRequestId: body.clientRequestId, oldMaterialExcess: result?.oldMaterialExcess ?? oldMaterialExcess.value, billNo: result.orderNo || result.order_no || `LOCAL-${Date.now()}` }
    oldMaterialPayoutAmount.value = Number(result?.oldMaterialPayoutAmount ?? result?.old_material_payout_amount ?? oldMaterialExcess.value ?? 0)
    if (result?.queued) { approval.required = discount.value < config.discountThreshold; approval.status = approval.required ? '待联网同步后审批' : ''; activeDialog.value = approval.required ? 'approval' : 'payment' }
    else if (result.approvalRequired) { approval.required = true; approval.status = '待审批'; approval.id = result.approvalId || null; approval.kind = 'ORDER_DISCOUNT'; activeDialog.value = 'approval' }
    else { approval.required = false; activeDialog.value = 'payment' }
  } catch (error) {
    if (error.conflict) {
      activeDialog.value = 'conflict'
    } else {
      // Older Electron/native network bridges may drop the ApiResponse message
      // while preserving its business code. Keep inventory failures actionable.
      const code = Number(error?.body?.code || error?.code || 0)
      const message = error?.body?.message || error?.message
      const detail = code === 409103
        ? '商品可售库存不足，已有待收款订单占用，请先收款或取消原订单后再开单'
        : (message || '开单失败，请检查后端服务和商品库存')
      ElMessage.error(detail)
    }
  }
  finally { checkoutSubmitting.value = false }
}
async function refreshApproval() {
  if (!approval.id || !online.value || !getToken()) return ElMessage.info('审批单尚未同步到云端')
  try {
    const result = await request(`/api/approval/${approval.id}`)
    const statusCode = result?.approval?.status ?? result?.status
    approval.status = Number(statusCode) === 3 ? '已通过' : Number(statusCode) === 4 ? '已驳回' : '待审批'
    approval.kind = result?.approval?.type && String(result.approval.type).includes('PAYMENT_DISCOUNT') ? 'PAYMENT_DISCOUNT' : approval.kind
    approval.required = approval.status === '待审批'
    if (approval.status === '已通过') activeDialog.value = 'payment'
    if (approval.status === '已驳回' && approval.kind === 'PAYMENT_DISCOUNT') { approval.message = '优惠审批已驳回，请修改实收金额后重新提交'; activeDialog.value = 'payment' }
  } catch (error) { ElMessage.error(error.message) }
}
async function confirmPayment() {
  if (!orderDraft.value) return
  if (paymentSubmitting.value) return
  if (approval.required && approval.status !== '已通过') return ElMessage.warning('折扣单待店长审批通过后才能结算')
  if (paymentDue.value > 0 && !paymentMethods.value.length) return ElMessage.warning('当前没有可用的支付方式，请联系管理员启用')
  if (!Number.isFinite(paidAmount.value) || paymentMethods.value.some(p => !Number.isFinite(Number(p.amount)) || Number(p.amount) < 0 || Math.round(Number(p.amount) * 100) !== Number(p.amount) * 100)) return ElMessage.warning('实收金额须为非负数且最多两位小数')
  if (!paymentBalanced.value) return ElMessage.warning(paymentDifferenceCents.value < 0 ? `还差 ${money(paymentRemaining.value)}` : `超收 ${money(paymentOver.value)}，请调整组合支付`)
  if (paidAmount.value <= 0 && paymentDue.value > 0) return ElMessage.warning('请输入本次实收金额')
  if (paidAmount.value > paymentDue.value) return ElMessage.warning('实收金额不能超过待收金额')
  if (paidAmount.value < paymentDue.value && (!online.value || orderDraft.value.queued)) return ElMessage.warning('优惠收款需要联网提交审批或记账，请连接后重试')
  const body = { orderId: orderDraft.value.orderId ?? orderDraft.value.id ?? null, orderClientRequestId: orderDraft.value.clientRequestId, amount: paidAmount.value, settlementMode: 'FULL', payMethod: paymentMethodLabel(), oldMaterialPayoutMethod: oldMaterialExcess.value > 0 ? oldMaterialPayoutMethod.value : null, oldMaterialPayoutAmount: oldMaterialExcess.value > 0 ? customerPayoutAmount.value : 0, paymentDetails: paymentMethods.value.filter(p => Number(p.amount) > 0).map(p => ({ method: p.code, amount: Number(p.amount).toFixed(2) })), clientRequestId: uuid() }
  paymentSubmitting.value = true
  try {
    const result = orderDraft.value.queued ? (await enqueueWithId('/api/pay/pay', body, body.clientRequestId), { queued: true }) : await requestOrQueue('/api/pay/pay', body, { backendAvailable: online.value })
    if (result?.approvalRequired) {
      approval.required = true
      approval.status = '待店长/管理员审批'
      approval.id = result.approvalId || null
      approval.kind = 'PAYMENT_DISCOUNT'
      approval.message = `本次原应收 ${money(result.originalDue ?? paymentDue.value)}，实收 ${money(result.actualPaid ?? paidAmount.value)}，优惠 ${money(result.settlementDiscount ?? Math.max(0, paymentDue.value - paidAmount.value))}，低于${Math.round(config.discountThreshold * 100)}折，审批通过后请再次确认收款`
      activeDialog.value = 'approval'
      ElMessage.warning(`优惠低于${Math.round(config.discountThreshold * 100)}折，已提交审批；审批通过后请再次确认收款`)
      return
    }
    const previousPaid = Number(orderDraft.value.actualPaid ?? orderDraft.value.actual_paid ?? orderDraft.value.payAmount ?? 0)
    const newPayments = result?.idempotentReplay ? [] : body.paymentDetails.map(line => ({ pay_method: line.method, amount: line.amount }))
    orderDraft.value = { ...orderDraft.value, ...result, status: result?.status ?? orderDraft.value.status, payAmount: result?.actualPaid ?? previousPaid + paidAmount.value, actualPaid: result?.actualPaid ?? previousPaid + paidAmount.value, payMethod: paymentMethodLabel(), remaining: result?.remaining ?? Math.max(0, paymentDue.value - paidAmount.value), paymentHistory: [...(orderDraft.value.paymentHistory || []), ...newPayments] }
    if (!result?.queued) await refreshProducts()
    await window.dajin?.print?.log?.({ billNo: orderDraft.value.billNo, printType: 'receipt', copies: 1, isReprint: 0 })
    printPreview.value = { type: 'receipt', text: buildReceiptPreview(receiptModel()), html: '' }
    activeDialog.value = 'print'
    ElMessage.success(result?.queued ? '网络离线，已保存到本地队列' : Number(result?.settlementDiscount || 0) > 0 ? `优惠结清，实收 ${money(result.actualPaid)}` : '结算完成')
  } catch (error) { if (error.conflict) activeDialog.value = 'conflict'; else ElMessage.error(error.message) }
  finally { paymentSubmitting.value = false }
}
function receiptModel() {
  const draft = orderDraft.value || {}
  const originalDue = Number(draft.original_due ?? draft.originalDue ?? payable.value)
  const settlementDiscount = Number(resultDiscount(draft) || 0)
  const paymentHistory = draft.paymentHistory || []
  const payMethod = paymentHistory.length
    ? paymentHistory.map(line => `${Number(line.amount) < 0 ? '退款 ' : ''}${paymentLabel(line.pay_method)} ¥${Number(line.amount).toFixed(2)}`).join(' + ')
    : draft.pay_method ? paymentLabel(draft.pay_method) : (originalDue > 0 ? paymentMethodLabel() : '无需收款')
  return {
    storeName: cashier.storeName, billNo: draft.billNo,
    items: cart.value.map(i => ({ name: i.name, qty: isGramItem(i) ? 1 : i.qty, detail: `${i.weight ? `${Number(i.weight).toFixed(3)}g` : '按件'}${isGramItem(i) ? ' · 按克' : ''} + 工费${money(i.laborFee)}`, amount: (isGramItem(i) ? i.amount : i.amount * i.qty) + (isGramItem(i) ? i.laborFee : i.laborFee * i.qty) })),
    total: cartSubtotal.value + cartLabor.value, oldMaterialValue: oldDeduct.value,
    deduct: appliedOldDeduct.value, excessPayout: oldMaterialExcess.value,
    payoutMethod: paymentLabel(oldMaterialPayoutMethod.value), originalDue, settlementDiscount,
    payable: Number(draft.discounted_due ?? Math.max(0, originalDue - settlementDiscount)),
    actualPaid: Number(draft.actualPaid ?? draft.actual_paid ?? draft.payAmount ?? paidAmount.value),
    remaining: Number(draft.remaining ?? draft.remaining_due ?? 0), payMethod,
    paperWidth: printSettings.paperWidth
  }
}
function resultDiscount(order) { return order?.settlementDiscount ?? order?.settlement_discount ?? 0 }
async function showReceiptPreview() { printPreview.value = { type: 'receipt', text: buildReceiptPreview(receiptModel()), html: '' }; activeDialog.value = 'print' }
function currentShiftPrintModel(overrides = {}) {
  return {
    storeName: cashier.storeName,
    cashierName: cashier.name,
    shiftNo: shiftMeta.value.shiftNo || '-',
    confirmedAt: formatPrintTime(),
    rows: plainPayload(shiftRows.value),
    cashExpected: shiftCashSystem.value,
    cashActual: Number(shiftCash.value || 0),
    difference: shiftCashDifference.value,
    remark: shiftRemark.value.trim(),
    total: shiftTotal.value,
    ...overrides
  }
}
function showShiftPreview(model = currentShiftPrintModel()) {
  shiftPrintModel.value = plainPayload(model)
  shiftPaper.value = '58'
  printPreview.value = { type: 'shift', text: buildShiftPreview(shiftPrintModel.value), html: '' }
  activeDialog.value = 'print'
}
async function printReceipt() {
  const model = receiptModel()
  let native = null
  try {
    native = await window.dajin?.print?.receipt?.({ ...model, silent: printSettings.silent, deviceName: printSettings.deviceName })
    if (native?.print?.success === false) return ElMessage.warning(native.print.reason || '打印机未响应，请检查打印机设置')
  } catch (error) {
    return ElMessage.error(error?.message || '小票打印失败')
  }
  if (!native) {
    const popup = window.open('', '_blank', 'width=420,height=800')
    if (!popup) return ElMessage.warning('浏览器拦截了打印窗口，请允许弹出窗口后重试')
    const lines = printPreview.value.text || buildReceiptPreview(model)
    const html = `<html><head><meta charset="utf-8"><style>@page{size:${printSettings.paperWidth}mm auto;margin:0}body{margin:0;padding:12px;font-family:monospace;white-space:pre-wrap}</style></head><body>${lines.replace(/[&<>]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]))}</body></html>`
    let printed = false
    const printPopup = () => {
      if (printed || popup.closed) return
      printed = true
      popup.focus()
      popup.print()
      setTimeout(() => { if (!popup.closed) popup.close() }, 250)
    }
    popup.addEventListener('load', printPopup, { once: true })
    popup.document.open(); popup.document.write(html); popup.document.close()
    setTimeout(printPopup, 100)
  }
  await window.dajin?.print?.log?.({ billNo: model.billNo, printType: 'receipt', copies: 1, isReprint: 0 })
  ElMessage.success('小票打印任务已发送')
}
function warrantyHtml() {
  const esc = v => String(v ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  const billNo = esc(orderDraft.value?.billNo || '-')
  const date = new Date().toLocaleDateString('zh-CN')
  const customerName = esc(selectedMember.value?.name || '散客')
  const customerPhone = esc(selectedMember.value?.phone || '')
  const rows = cart.value.map(i => `<tr><td>${esc(i.name)}</td><td>${esc(i.goldType || '—')}</td><td>${i.weight ? esc(Number(i.weight).toFixed(3)) + 'g' : '—'}</td><td>${isGramItem(i) ? '—' : esc(i.qty)}</td><td class="num">${esc(money((isGramItem(i) ? i.amount : i.amount * i.qty) + (isGramItem(i) ? i.laborFee : i.laborFee * i.qty)))}</td></tr>`).join('')
  const copy = tag => `<div class="half${tag === 'red' ? ' red' : ''}">
  <span class="copy-tag">${tag === 'red' ? '第二联 客户（红）' : '第一联 存根（白）'}</span>
  <h1>商品质保单</h1>
  <p class="shop">${esc(cashier.storeName)} · 黄金业务工作台</p>
  <p class="biz">主营业务：金银加工 ｜ 零损耗 ｜ 黄金回收 ｜ 私人定制 ｜ 珠宝零售</p>
  <div class="meta">
    <span>单据编号：<b>${billNo}</b></span>
    <span>日期：<b>${date}</b></span>
    <span>客户姓名：<b>${customerName}</b></span>
    <span>客户电话：<b>${customerPhone}</b></span>
  </div>
  <table>
    <thead><tr><th>货品</th><th>成色</th><th>克重</th><th>数量</th><th class="num">金额</th></tr></thead>
    <tbody>${rows || '<tr><td colspan="5">无货品明细</td></tr>'}</tbody>
  </table>
  <p class="rights">客户权益：<span>☑ 终身免费清洗、焊接保养</span><span>□ 正品可复检 假赔十</span><span>△ 人为损坏维修收取工本费</span></p>
  <p class="note">备注：来料加工货品当面验收，离店概不负责。</p>
  <div class="sign"><span>客户签字：<span class="line"></span></span><span>门店盖章：</span></div>
  <p class="addr">地址：河南省郑州市中原区绿东村街道华山路155-3号阳光商务二楼鑫铖金匠</p>
</div>`
  return `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>商品质保单 ${billNo}</title><style>
  @page{size:A4 landscape;margin:8mm}
  body{font:11px/1.5 "Microsoft YaHei",sans-serif;color:#222;margin:0;padding:10px;background:#fff}
  .pair{width:281mm;margin:0 auto;background:#fff;display:flex;box-sizing:border-box}
  .half{width:50%;padding:7mm 6mm;box-sizing:border-box;position:relative}
  .half + .half{border-left:1px dashed #999}
  .half.red{background:#fdf1f0;color:#7a1f1a}
  .copy-tag{position:absolute;top:4mm;right:5mm;font-size:10px;font-weight:700;letter-spacing:1px;border:1px solid currentColor;border-radius:4px;padding:0 6px;color:#666}
  .half.red .copy-tag{color:#c0392b}
  h1{font-size:16px;text-align:center;margin:0 0 2px;letter-spacing:4px}
  .shop{text-align:center;font-size:10.5px;margin:0 0 4px}
  .biz{text-align:center;font-size:9.5px;margin:0 0 6px;color:#8a6a2f}
  .half.red .biz{color:#a04a44}
  .meta{display:grid;grid-template-columns:1fr 1fr;gap:2px 10px;font-size:10.5px;border-top:1px solid #999;border-bottom:1px solid #999;padding:4px 0;margin-bottom:6px}
  table{width:100%;border-collapse:collapse;font-size:10.5px;margin-bottom:6px}
  th,td{border:1px solid #999;padding:3px 4px;text-align:left}
  th{background:#f4efe6;font-weight:600}
  .half.red th{background:#f7e3e1}
  td.num,th.num{text-align:right}
  .rights{font-size:10px;margin:0 0 4px}
  .rights span{display:block}
  .note{font-size:10px;margin:0 0 8px}
  .sign{display:flex;justify-content:space-between;align-items:flex-end;font-size:10.5px;margin-top:10px}
  .sign .line{display:inline-block;width:34mm;border-bottom:1px solid #555;height:18px}
  .addr{margin-top:8px;font-size:9px;color:#777;text-align:center}
  .half.red .addr{color:#9a5a54}
  @media print{body{padding:0}.pair{margin:0;width:auto}}
  </style></head><body><section class="pair">${copy('white')}${copy('red')}</section></body></html>`
}
async function printWarranty() {
  const html = warrantyHtml()
  if (window.dajin?.print?.system) {
    try {
      const result = await window.dajin.print.system(html, { silent: printSettings.silent, deviceName: printSettings.deviceName, pageSize: 'A4' })
      if (result?.success === false) ElMessage.warning(result.reason || '打印机未响应')
      else ElMessage.success('A4质保单打印任务已发送')
    } catch (error) { return ElMessage.error(error?.message || '质保单打印失败') }
  } else {
    const popup = window.open('', '_blank', 'width=900,height=1100')
    if (!popup) return ElMessage.warning('浏览器拦截了打印窗口，请允许弹出窗口后重试')
    // Register the load handler before writing the document. Some browsers fire
    // the data-document load event synchronously when document.close() is called.
    let printed = false
    const printPopup = () => {
      if (printed || popup.closed) return
      printed = true
      popup.focus()
      popup.print()
      // Keep the preview available long enough for the native print dialog to
      // capture the document, then close it in browsers that support close().
      setTimeout(() => { if (!popup.closed) popup.close() }, 250)
    }
    popup.addEventListener('load', printPopup, { once: true })
    popup.document.open(); popup.document.write(html); popup.document.close()
    // A popup opened with document.write may already be complete by the time
    // the load event is dispatched, so provide a deterministic fallback.
    setTimeout(printPopup, 100)
    ElMessage.success('已打开A4质保单打印窗口')
  }
  await window.dajin?.print?.log?.({ billNo: orderDraft.value?.billNo, printType: 'warranty-a4', copies: 1, isReprint: Boolean(orderDraft.value?.isReprint) })
}
async function printShift() {
  if (!shiftPrintModel.value) return ElMessage.warning('交班单数据不存在，请重新预览')
  const html = buildShiftPrintHtml(shiftPrintModel.value, shiftPaper.value)
  if (window.dajin?.print?.system) {
    try {
      const result = await window.dajin.print.system(html, { silent: printSettings.silent, deviceName: printSettings.deviceName, pageSize: shiftPaper.value === 'a4' ? 'A4' : undefined })
      if (result?.success === false) return ElMessage.warning(result.reason || '打印机未响应')
      ElMessage.success('交班单打印任务已发送')
    } catch (error) { return ElMessage.error(error?.message || '交班单打印失败') }
  } else {
    const popup = window.open('', '_blank', shiftPaper.value === 'a4' ? 'width=900,height=1100' : 'width=420,height=800')
    if (!popup) return ElMessage.warning('浏览器拦截了打印窗口，请允许弹出窗口后重试')
    let printed = false
    const printPopup = () => {
      if (printed || popup.closed) return
      printed = true
      popup.focus()
      popup.print()
      setTimeout(() => { if (!popup.closed) popup.close() }, 250)
    }
    popup.addEventListener('load', printPopup, { once: true })
    popup.document.open(); popup.document.write(html); popup.document.close()
    setTimeout(printPopup, 100)
    ElMessage.success('已打开交班单打印窗口')
  }
  await window.dajin?.print?.log?.({ billNo: shiftPrintModel.value.shiftNo, printType: `shift-${shiftPaper.value}`, copies: 1, isReprint: 0 })
}
async function printProcessingOrder() {
  if (!processingPrintModel.value) return ElMessage.warning('加工工单数据不存在，请重新创建工单后打印')
  const html = printPreview.value.html
  if (!html) return ElMessage.warning('工单预览内容不存在，请重新打开预览')
  if (window.dajin?.print?.system) {
    try {
      const result = await window.dajin.print.system(html, { silent: printSettings.silent, deviceName: printSettings.deviceName, pageSize: 'A4' })
      if (result?.success === false) return ElMessage.warning(result.reason || '打印机未响应')
      ElMessage.success('加工工单打印任务已发送')
    } catch (error) { return ElMessage.error(error?.message || '加工工单打印失败') }
  } else {
    const popup = window.open('', '_blank', 'width=640,height=900')
    if (!popup) return ElMessage.warning('浏览器拦截了打印窗口，请允许弹出窗口后重试')
    let printed = false
    const printPopup = () => {
      if (printed || popup.closed) return
      printed = true
      popup.focus()
      popup.print()
      setTimeout(() => { if (!popup.closed) popup.close() }, 250)
    }
    popup.addEventListener('load', printPopup, { once: true })
    popup.document.open(); popup.document.write(html); popup.document.close()
    setTimeout(printPopup, 100)
    ElMessage.success('已打开加工工单打印窗口')
  }
  await window.dajin?.print?.log?.({ billNo: processingPrintModel.value.orderNo, printType: 'processing-a4', copies: 1, isReprint: 0 })
  const pid = processingPrintModel.value?.processingOrderId
  if (pid && processingPrintAdvance.value) {
    try {
      await request(`/api/processing/orders/${pid}/status`, { method: 'PATCH', body: JSON.stringify({ status: 'PROCESSING' }) })
      ElMessage.success('工单已打印，加工单开始加工')
      activeDialog.value = ''
      await loadFrontTodo()
    } catch (error) { ElMessage.error(error?.message || '工单已打印，但状态更新失败，可用「确认加工」重试') }
  } else if (pid) {
    ElMessage.success('工单已补打')
    activeDialog.value = ''
    await loadFrontTodo()
  }
  processingPrintAdvance.value = false
}
async function reprint(row) {
  const orderId = row.order_id ?? row.orderId ?? row.id ?? null
  if (!orderId || !online.value || !getToken()) return ElMessage.warning('需要联网后才能获取完整单据')
  try {
    const detail = await request(`/api/order/${orderId}`)
    const order = detail?.order || row
    const goodsResult = await request('/api/goods/list?page=1&size=200').catch(() => ({ records: [] }))
    const goodsById = Object.fromEntries((goodsResult?.records || []).map(item => [item.goods_id ?? item.id, item]))
    cart.value = (detail?.items || []).map(item => {
      const goods = goodsById[item.goods_id]
      const weight = Number(item.weight || 0)
      const priceType = Number(goods?.price_type || 1)
      const unitPrice = Number(item.unit_price || 0)
      const laborFee = Number(item.labor_fee || 0)
      return {
        goodsId: item.goods_id,
        barcode: goods?.barcode,
        name: item.item_name || goods?.name || '商品',
        category: goods?.category,
        weight,
        priceType,
        unitPrice,
        amount: unitPrice,
        laborFee,
        qty: Number(item.qty || 1),
        detail: priceType === 1
          ? (weight > 0 ? `${weight.toFixed(3)}g × ${money(unitPrice)}` : `按克 ${money(unitPrice)}/g · 待称重录入`)
          : '按件计价',
        stock: Number(goods?.stock || 0),
        image: productImage(goods)
      }
    })
    oldMetals.value = (detail?.oldMaterials || []).map(item => ({ id: uuid(), weight: Number(item.weight || 0), purity: Number(item.purity || 0), materialType: item.material_type || '足金旧料', priceType: item.price_type || '回收金价', note: item.note || '' }))
    paymentMethods.value.forEach(method => { method.amount = 0; method.selected = false })
    for (const payment of detail?.payments || []) {
      const method = paymentMethods.value.find(item => item.code === payment.pay_method)
      if (method) { method.amount += Number(payment.amount || 0); method.selected = true }
    }
    discount.value = Number(order.discount || 1)
    selectedMember.value = order.member_id ? members.value.find(member => member.id === order.member_id) || null : null
    selectedSalesId.value = order.sales_id ?? null
    orderDraft.value = { ...order, id: orderId, orderId, billNo: order.order_no, payAmount: order.actual_paid ?? order.pay_amount, paymentHistory: detail?.payments || [], isReprint: true }
    printPreview.value = { type: 'receipt', text: buildReceiptPreview(receiptModel()), html: '' }
    activeDialog.value = 'print'
  } catch (error) { ElMessage.error(error.message || '获取单据详情失败') }
}

async function saveRecycle() {
  if (Number(recycleForm.weight) <= 0) return ElMessage.warning('请输入旧料克重')
  if (!(recyclePurity.value > 0 && recyclePurity.value <= 1)) return ElMessage.warning('成色请输入 0 到 100% 之间的数值')
  if (!recycleForm.payMethod) return ElMessage.warning('当前没有可用的返款方式，请联系管理员启用')
  try { const result = await requestOrQueue('/api/recycle/create', { ...recycleForm, purity: recyclePurity.value, recyclePrice: recycleForm.recyclePrice || recycleSpot.value, clientRequestId: uuid() }, { backendAvailable: online.value }); ElMessage.success(result?.approvalRequired ? '已提交大额回收审批' : `回收单已保存，应付 ${money(recycleAmount.value)}`); activeDialog.value = '' } catch (error) { ElMessage.error(error.message) }
}
async function saveTradeIn() {
  if (orderDraft.value) return ElMessage.warning('请先完成或取消当前待收款订单')
  if (!tradeOldMetals.value.length || tradeOldTotal.value <= 0) return ElMessage.warning('请录入有效的旧料明细')
  oldMetals.value.push(...tradeOldMetals.value.map(m => ({ ...m, priceType: '回收金价' })))
  tradeOldMetals.value = []
  activeMenu.value = 'order'
  activeDialog.value = ''
}
function chooseProcessingMember() {
  const member = members.value.find(item => String(item.id ?? item.member_id) === String(processingForm.memberId))
  if (!member) { applyProcessingMemberSales(lastSalesId()); return }
  processingForm.customerName = member.name || processingForm.customerName
  processingForm.customerPhone = member.phone || processingForm.customerPhone
  applyProcessingMemberSales(member.sales_id)
}
function resetProcessingForm() {
  Object.assign(processingForm, {
    memberId: '', customerName: '', customerPhone: '', processingItemId: '', quantity: 1, billingWeight: '',
    pickupDate: '', craftsmanId: '', salesId: validSalesId(lastSalesId()), oldGoldWeight: '', oldGoldFineness: 0.999,
    deposit: 0, depositMethod: 'CASH', remark: ''
  })
  processingMemberSalesNotice.value = ''
  processingDraftId.value = null
  processingDraftVersion.value = null
  processingDraftOrderNo.value = ''
}
async function resumeProcessingDraft(row) {
  try {
    const id = row.processing_order_id ?? row.processingOrderId ?? row.id
    const detail = await request(`/api/processing/orders/${id}`)
    const order = detail?.order || detail
    Object.assign(processingForm, {
      memberId: order.member_id ?? '', customerName: order.customer_name || '', customerPhone: order.customer_phone || '',
      processingItemId: order.processing_item_id ?? '', quantity: Number(order.quantity || 1), billingWeight: order.billing_weight ?? '',
      pickupDate: order.pickup_date ? String(order.pickup_date).slice(0, 10) : '', craftsmanId: order.craftsman_id ?? '', salesId: validSalesId(order.sales_id ?? ''),
      oldGoldWeight: order.old_gold_weight ?? '', oldGoldFineness: order.old_gold_fineness ?? 0.999, deposit: 0,
      depositMethod: paymentMethods.value[0]?.code || 'CASH', remark: order.remark || ''
    })
    processingDraftId.value = id
    processingDraftVersion.value = order.version ?? row.version ?? null
    processingDraftOrderNo.value = order.order_no || row.order_no || ''
    activeMenu.value = 'processing'
    ElMessage.success(`已恢复加工单 ${processingDraftOrderNo.value}，继续保存不会生成新单号`)
  } catch (error) { ElMessage.error(error?.message || '调取加工草稿失败') }
}
async function loadProcessingData() {
  if (!online.value || !getToken()) return
  try {
    const [items, craftsmen, sales, drafts] = await Promise.all([request('/api/processing/items?status=1'), request('/api/processing/craftsmen'), request('/api/processing/salespeople'), request('/api/processing/orders?status=PENDING')])
    processingItems.value = Array.isArray(items) ? items : []
    processingCraftsmen.value = Array.isArray(craftsmen) ? craftsmen : []
    processingSalespeople.value = Array.isArray(sales) ? sales : salespeople.value
    processingDrafts.value = Array.isArray(drafts) ? drafts : drafts?.records || []
  } catch (error) { ElMessage.error(error.message || '加工项目加载失败') }
}
async function submitProcessingOrder() {
  if (!online.value || !getToken()) return ElMessage.warning('加工开单需连接后端服务，请恢复网络后重试')
  if (!processingForm.customerName.trim() || !processingForm.customerPhone.trim()) return ElMessage.warning('请输入客户姓名和联系电话')
  if (!processingForm.processingItemId) return ElMessage.warning('请选择加工项目')
  if (!(Number(processingForm.quantity) > 0)) return ElMessage.warning('加工数量必须大于0')
  // 按克项目的计费总克重要等完工、知道成品实际克重后才填，开单阶段允许留空（工费先记 0）
  if (Number(processingForm.deposit || 0) < 0 || Number(processingForm.deposit || 0) > processingDue.value) return ElMessage.warning('定金应在 0 到应收金额之间')
  if (Number(processingForm.deposit || 0) > 0 && !processingForm.depositMethod) return ElMessage.warning('当前没有可用的支付方式，请联系管理员启用')
  processingSubmitting.value = true
  try {
    const payload = {
        memberId: processingForm.memberId || null, customerName: processingForm.customerName.trim(), customerPhone: processingForm.customerPhone.trim(),
        processingItemId: Number(processingForm.processingItemId), quantity: Number(processingForm.quantity), pickupDate: processingForm.pickupDate || null, craftsmanId: processingForm.craftsmanId || null, salesId: validSalesId(processingForm.salesId) || null,
        billingWeight: null,
        oldGoldWeight: processingForm.oldGoldWeight || null, oldGoldFineness: processingForm.oldGoldFineness || null,
        remark: processingForm.remark || null
      }
    const result = processingDraftId.value
      ? await request(`/api/processing/orders/${processingDraftId.value}`, { method: 'PUT', body: JSON.stringify({ ...payload, version: processingDraftVersion.value }) })
      : await request('/api/processing/orders', { method: 'POST', body: JSON.stringify(payload) })
    const orderId = result?.processing_order_id ?? result?.processingOrderId ?? result?.id
    if (validSalesId(processingForm.salesId)) rememberSales(validSalesId(processingForm.salesId))
    const due = Number(result?.due_amount ?? result?.dueAmount ?? processingDue.value)
    const deposit = Number(processingForm.deposit || 0)
    if (deposit > 0) await request(`/api/processing/orders/${orderId}/payments`, { method: 'POST', body: JSON.stringify({ paymentType: 'DEPOSIT', amount: deposit, payMethod: processingForm.depositMethod, clientRequestId: uuid(), remark: '前台加工开单定金' }) })
    const craftsman = processingCraftsmen.value.find(item => String(item.user_id ?? item.id) === String(processingForm.craftsmanId))
    ElMessage.success(`加工单 ${result?.order_no || result?.orderNo || processingDraftOrderNo.value || ''} 已保存${deposit > 0 ? `，已收定金 ${money(deposit)}` : ''}，待收 ${money(due - deposit)}`)
    resetProcessingForm()
    await loadProcessingData()
    // 工单统一用后端模板渲染，避免收银端/服务端两套样式不一致
    await openProcessingPrint(orderId, result?.order_no || result?.orderNo || processingDraftOrderNo.value || '-', { advanceStatus: false })
  } catch (error) { ElMessage.error(error.message || '加工开单失败') }
  finally { processingSubmitting.value = false }
}
async function loadPageData() {
  if (activeMenu.value === 'bill' && online.value && getToken()) { try { const result = await request('/api/order/list?page=1&size=100'); billRows.value = (result?.records || result || []).map(row => ({ ...row, id: row.order_id ?? row.orderId ?? row.id })) } catch (error) { ElMessage.error(error.message) } }
  if (activeMenu.value === 'shift' && online.value && getToken()) { try { const summary = await request('/api/shift/info') || {}; shiftRows.value = summary.lines || []; shiftMeta.value = summary } catch (error) { shiftRows.value = []; shiftMeta.value = {} } }
  if (activeMenu.value === 'member') {
    if (online.value && getToken()) {
      try { await refreshMembers() } catch (error) { console.warn('[dajin-member] page refresh skipped:', error?.message || error); members.value = await localMembers(memberKeyword.value) }
    } else members.value = await localMembers(memberKeyword.value)
  }
  if (activeMenu.value === 'processing') await loadProcessingData()
}
function createMember() {
  Object.assign(memberCreateForm, { name: '', phone: '', birthday: '' })
  activeDialog.value = 'memberCreate'
}
async function submitMemberCreate() {
  const name = memberCreateForm.name.trim()
  const phone = memberCreateForm.phone.trim()
  if (!name) return ElMessage.warning('请输入会员姓名')
  if (!/^1[3-9]\d{9}$/.test(phone)) return ElMessage.warning('请输入正确的手机号')
  const payload = { name, phone, birthday: memberCreateForm.birthday || null, source: '前台快速登记', tags: '[]', clientRequestId: uuid() }
  try {
    const result = await requestOrQueue('/api/member', payload, { backendAvailable: online.value })
    if (result?.queued) {
      const localMember = { id: Date.now(), balance: 0, points: 0, total_consume: 0, ...payload }
      members.value = [localMember, ...members.value.filter(item => item.phone !== phone)]
      const cachedMembers = plainPayload(members.value)
      if (window.dajin?.db?.seed) await window.dajin.db.seed({ members: [localMember] })
      else localStorage.setItem('dajin_members', JSON.stringify(cachedMembers))
    } else {
      await refreshMembers()
    }
    ElMessage.success('会员已登记')
    activeDialog.value = 'memberSelect'
    memberKeyword.value = ''
    await loadPageData()
  } catch (error) { ElMessage.error(error.message || '会员登记失败') }
}
async function submitStockCheck() {
  const diff = checkRows.value.reduce((sum, row) => sum + Number(row.actual || 0) - Number(row.stock || 0), 0)
  try { await requestOrQueue('/api/stock/check', { billNo: `PD${Date.now()}`, totalDiff: diff, rows: checkRows.value, clientRequestId: uuid() }, { backendAvailable: online.value }); ElMessage.success('盘点单已提交审批'); checkRows.value = [] } catch (error) { ElMessage.error(error.message) }
}
async function confirmShift() {
  const difference = shiftCashDifference.value
  if (difference !== 0 && !shiftRemark.value.trim()) {
    try {
      const { value } = await ElMessageBox.prompt(`系统现金 ${money(shiftCashSystem.value)}，实点现金 ${money(shiftCash.value)}，差异 ${money(difference)}。请填写差异原因后继续交班。`, '现金差异说明', {
        inputPlaceholder: '例如：备用金、找零差异、录入遗漏',
        inputValidator: value => value?.trim() ? true : '现金有差异时必须填写原因',
        confirmButtonText: '继续交班',
        cancelButtonText: '取消'
      })
      shiftRemark.value = value.trim()
    } catch { return }
  }
  if (difference !== 0) {
    try {
      await ElMessageBox.confirm(`现金差异 ${money(difference)}，确认结束本班次并开始下一班次吗？`, '确认交班', {
        type: 'warning',
        confirmButtonText: '确认交班',
        cancelButtonText: '返回核对'
      })
    } catch { return }
  }
  try {
    const previewSnapshot = currentShiftPrintModel()
    const result = await requestOrQueue('/api/shift/confirm', { cashActual: shiftCash.value, remark: shiftRemark.value, clientRequestId: uuid() }, { backendAvailable: online.value })
    if (result?.queued) return ElMessage.warning('当前离线，交班不能确认，请恢复网络后重试')
    const confirmedSnapshot = currentShiftPrintModel({
      ...previewSnapshot,
      shiftNo: result?.previousShiftNo || previewSnapshot.shiftNo,
      confirmedAt: formatPrintTime(result?.shiftEndedAt || new Date()),
      cashExpected: Number(result?.cashSystem ?? previewSnapshot.cashExpected),
      cashActual: Number(result?.cashActual ?? previewSnapshot.cashActual),
      difference: Number(result?.cashDifference ?? previewSnapshot.difference)
    })
    shiftCash.value = 0
    shiftRemark.value = ''
    await loadPageData()
    ElMessage.success(`交班已确认，当前班次：${result?.nextShiftNo || shiftMeta.value.shiftNo || '-'}`)
    showShiftPreview(confirmedSnapshot)
  } catch (error) { ElMessage.error(error.message) }
}
function checkUnit(row) { return Number(row?.price_type ?? row?.priceType) === 1 ? 'g' : '件' }
function checkStep(row) { return checkUnit(row) === 'g' ? 0.001 : 1 }
function checkDisplay(value, row) { return `${Number(value || 0).toFixed(checkUnit(row) === 'g' ? 3 : 0)}${checkUnit(row)}` }
// 按克商品的实盘克重由柜台现场称重录入，不再用档案克重当"一件的重量"累加。
function addCheckRow(product) {
  const found = checkRows.value.find(x => x.id === product.id)
  if (checkUnit(product) === 'g') {
    if (found) ElMessage.info('该商品已在盘点明细中，请直接录入实盘克重')
    else checkRows.value.push({ ...product, actual: 0 })
    return
  }
  if (found) found.actual = Number((Number(found.actual || 0) + 1).toFixed(3))
  else checkRows.value.push({ ...product, actual: 1 })
}
function loginDialog() {
  activeDialog.value = getToken() ? 'account' : 'login'
  if (activeDialog.value === 'login') loginForm.password = ''
}
function switchAccount() {
  loginForm.password = ''
  activeDialog.value = 'login'
}
async function submitLogin() {
  if (!loginForm.username || !loginForm.password) return ElMessage.warning('请输入账号和密码')
  try {
    const result = await login(loginForm.username, loginForm.password)
    let storeName = '默认门店'
    try { const store = await request('/api/system/store-info'); storeName = store?.store_name || storeName } catch { /* store metadata is optional during offline login */ }
    Object.assign(cashier, { name: result.user?.real_name || loginForm.username, role: result.user?.role_code || 'FRONT', storeName })
    activeDialog.value = ''
    ElMessage.success('登录成功')
    await refreshOnlineData(); setupSocket(); await doSync()
  } catch (error) { ElMessage.error(error.message) }
}
function openDialog(name) {
  activeDialog.value = name
  if (name === 'memberSelect' && online.value && getToken()) refreshMembers().catch(error => console.warn('[dajin-member] selector refresh skipped:', error?.message || error))
}
async function cancelPendingSaleOrder() {
  const draft = orderDraft.value
  const orderId = pendingSaleOrderId(draft)
  if (!orderId) {
    if (draft?.queued) {
      try {
        await cancelQueuedOrder(draft.clientRequestId)
        resetCart()
        if (online.value) await doSync()
        ElMessage.success('本地订单已取消，联网后同步释放库存')
        return true
      } catch (error) {
        ElMessage.error(error?.message || '取消本地订单失败')
        return false
      }
    }
    return false
  }
  if (!online.value || !getToken()) {
    ElMessage.warning('当前订单尚未联网，无法取消云端订单')
    return false
  }
  try {
    await request(`/api/order/${orderId}/cancel`, { method: 'POST', body: JSON.stringify({ reason: '收银端取消待结算单' }) })
    await refreshProducts()
    resetCart()
    ElMessage.success('订单已取消，库存占用已释放')
    return true
  } catch (error) {
    ElMessage.error(error?.message || '取消订单失败')
    return false
  }
}
async function closeDialog() {
  if (paymentSubmitting.value) return
  if (activeDialog.value === 'payment' && (shouldReusePendingSale(orderDraft.value) || orderDraft.value?.queued)) {
    if (Number(orderDraft.value?.actualPaid ?? orderDraft.value?.actual_paid ?? orderDraft.value?.payAmount ?? 0) > 0) {
      activeDialog.value = ''
      resetCart()
      await loadFrontTodo()
      return
    }
    if (!await cancelPendingSaleOrder()) return
    activeDialog.value = ''
    return
  }
  const completedSale = activeDialog.value === 'print' && Number(orderDraft.value?.status) === 1 && ['receipt', 'warranty'].includes(printPreview.value.type)
  const partiallyPaidSale = activeDialog.value === 'print' && Number(orderDraft.value?.status) === 0 && Number(orderDraft.value?.actualPaid ?? orderDraft.value?.actual_paid ?? 0) > 0 && ['receipt', 'warranty'].includes(printPreview.value.type)
  if (printPreview.value.type === 'queued') activePrintJob.value = null
  activeDialog.value = ''
  if (completedSale || partiallyPaidSale) resetCart()
  if (partiallyPaidSale) await loadFrontTodo()
}
async function saveSettings() {
  if (!apiEndpoint.value.trim()) return ElMessage.warning('请输入后端 API 地址')
  let endpoint
  try {
    endpoint = new URL(apiEndpoint.value.trim())
    if (!['http:', 'https:'].includes(endpoint.protocol) || endpoint.username || endpoint.password || endpoint.pathname !== '/' || endpoint.search || endpoint.hash) throw new Error('invalid URL')
  } catch { return ElMessage.warning('请输入完整的后端站点根地址，如 https://admin.xinchengjinjiang.com') }
  try {
    await window.dajin?.config?.set?.({ apiBase: endpoint.origin, apiBaseUrl: endpoint.origin, paperWidth: printSettings.paperWidth, deviceName: printSettings.deviceName, silent: printSettings.silent })
    backendConfigured.value = true
    ws.value?.close()
    ws.value = null
    setApiBase(endpoint.origin)
    apiEndpoint.value = apiBase()
    if (await probeBackend()) {
      ElMessage.success('后端已连接，设置已保存')
      activeDialog.value = getToken() ? '' : 'login'
    } else ElMessage.warning('设置已保存，但后端暂未连接，请核对地址或网络')
  } catch (error) { ElMessage.error(error?.message || '保存设置失败') }
}
function nav(id) { activeMenu.value = id; loadPageData() }

onMounted(async () => {
  clockTimer = setInterval(() => { currentTime.value = new Date() }, 1000)
  syncTimer = setInterval(doSync, 15000)
  healthTimer = setInterval(probeBackend, 3000)
  configTimer = setInterval(refreshOldMaterialTypes, 15000)
  startTodoPolling()
  window.addEventListener('online', networkChanged); window.addEventListener('offline', networkChanged); window.addEventListener('focus', windowFocused); window.addEventListener('dajin:unauthorized', sessionExpired)
  const savedConfig = await window.dajin?.config?.get?.().catch?.(() => ({})) || {}
  const savedApiBase = savedConfig.apiBase || savedConfig.apiBaseUrl
  if (savedApiBase) { backendConfigured.value = true; setApiBase(savedApiBase); apiEndpoint.value = apiBase() }
  else if (window.dajin?.config) { apiEndpoint.value = 'https://admin.xinchengjinjiang.com'; activeDialog.value = 'settings' }
  if (savedConfig.paperWidth) printSettings.paperWidth = Number(savedConfig.paperWidth)
  if (savedConfig.deviceName != null) printSettings.deviceName = savedConfig.deviceName
  if (savedConfig.silent != null) printSettings.silent = Boolean(savedConfig.silent)
  printers.value = await window.dajin?.print?.printers?.().catch?.(() => []) || []
  await loadLocalData()
  loadHeldOrders()
  try { const saved = JSON.parse(localStorage.getItem('dajin_user') || '{}'); if (saved.real_name) Object.assign(cashier, { name: saved.real_name, role: saved.role_code || cashier.role }) } catch { /* ignore malformed local session */ }
  await probeBackend(); await refreshOnlineData(); setupSocket(); await doSync()
  await loadConflicts()
  if (!getToken() && activeDialog.value !== 'settings') activeDialog.value = 'login'
})
onBeforeUnmount(() => { clearInterval(clockTimer); clearInterval(syncTimer); clearInterval(healthTimer); clearInterval(configTimer); clearTimeout(wsRetryTimer); window.removeEventListener('online', networkChanged); window.removeEventListener('offline', networkChanged); window.removeEventListener('focus', windowFocused); window.removeEventListener('dajin:unauthorized', sessionExpired); ws.value?.close() })
watch(activeMenu, loadPageData)
watch(activeDialog, value => { if (value === 'conflict') loadConflicts() })
</script>

<template>
  <el-config-provider :locale="zhCn">
    <div class="app-shell">
    <header class="topbar">
      <div class="brand"><div class="brand-mark"><Gem :size="18" /></div><div><strong>{{ cashier.storeName }}</strong><span>黄金业务工作台</span></div></div>
      <div class="top-metrics"><div class="metric"><span>足金卖价</span><b>{{ money(goldSpot) }}<small>/g</small></b></div><div class="metric"><span>黄金回收价</span><b>{{ money(recycleSpot) }}<small>/g</small></b></div><div class="metric silver"><span>银卖价</span><b>{{ money(silverSaleSpot) }}<small>/g</small></b></div><div class="metric silver"><span>银回收价</span><b>{{ money(silverRecycleSpot) }}<small>/g</small></b></div></div>
      <div class="top-actions"><span class="online-state" :class="{ offline: !online }"><span class="status-dot"></span>{{ online ? (syncing ? '同步中' : '在线') : '离线' }}</span><button class="icon-button" title="消息通知" @click="openNotifications"><Bell :size="18" /><i v-if="noticeBadge">{{ noticeBadge }}</i></button><span class="top-time">{{ currentTime.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) }}</span><button class="user-chip" @click="loginDialog"><span class="avatar">{{ cashier.name.slice(0, 1) }}</span>{{ cashier.name }}<ChevronRight :size="14" /></button></div>
    </header>
    <main class="workspace">
      <aside class="sidebar"><div class="sidebar-label">工作台</div><nav><button v-for="item in menus" :key="item.id" :class="{ active: activeMenu === item.id }" @click="nav(item.id)"><component :is="item.icon" :size="19" /><span>{{ item.label }}</span><em v-if="item.badge && item.badge()" class="nav-badge">{{ item.badge() }}</em><ChevronRight v-if="activeMenu === item.id" :size="14" class="nav-arrow" /></button></nav><div class="sidebar-foot"><div class="sync-summary"><span><Wifi :size="14" />{{ online ? '云端已连接' : '本地模式' }}</span><button v-if="conflictCount" class="conflict-link danger-text" @click="openDialog('conflict')">{{ conflictCount }} 个冲突</button><small>数据每15秒自动同步</small></div><button class="settings-link" @click="openDialog('settings')"><Settings2 :size="15" />打印与设备设置</button></div></aside>
      <section class="content-area">
        <template v-if="activeMenu === 'order'">
          <div class="page-heading"><div><div class="eyebrow">销售开单</div><h1>选择商品，快速完成一单</h1></div><div class="heading-actions"><button class="secondary-button" @click="openDialog('recycle')"><Archive :size="16" />旧料回收</button><button class="secondary-button" @click="openDialog('tradein')"><ArrowRightLeft :size="16" />以旧换新</button></div></div>
          <div class="order-layout">
            <section class="catalog-panel panel"><div class="catalog-toolbar"><label class="search-box"><Search :size="17" /><input v-model="search" placeholder="扫码或搜索商品名称 / 条码" @keyup.enter="scanEnter" autofocus /><kbd>Enter</kbd></label><button class="filter-button"><Grid2X2 :size="16" />筛选</button></div><div class="category-tabs"><button v-for="category in categoryTabs" :key="category.id ?? 'all'" :class="{ active: (activeCategory ?? null) === category.id }" @click="activeCategory = category.id; activeSubCategory = null">{{ category.name }}</button></div><div class="category-tabs sub-tabs" v-if="activeCategory !== null && subCategoryOptions.length"><button :class="{ active: activeSubCategory === null }" @click="activeSubCategory = null">全部</button><button v-for="sub in subCategoryOptions" :key="sub.id" :class="{ active: activeSubCategory === sub.id }" @click="activeSubCategory = sub.id">{{ sub.name }}</button></div><div class="catalog-meta"><span>{{ filteredProducts.length }} 件商品</span><span><ScanLineIcon /> 扫码枪可直接录入</span></div><div class="product-grid"><button v-for="product in filteredProducts" :key="product.id" class="product-card" @click="addProduct(product)"><div class="product-row"><img v-if="productImage(product)" :src="productImage(product)" class="product-thumb" alt="" /><span v-else class="product-thumb empty">无图</span><div class="product-text"><div class="product-name">{{ product.name }}</div><div class="product-detail">{{ productDetail(product) }}</div></div></div><div class="product-footer"><b>{{ money(productAmount(product)) }}</b><span>可售 {{ product.available_stock ?? product.stock }}{{ Number(product.price_type) === 1 ? 'g' : '件' }}</span></div></button><div v-if="!filteredProducts.length" class="empty-state"><PackageCheck :size="34" /><p>没有找到匹配商品</p><button class="text-button" @click="search = ''">清除搜索</button></div></div></section>
              <aside class="cart-panel panel">
                <div class="cart-header"><div><span class="eyebrow">当前订单</span><h2>购物清单 <em>{{ cartCountLabel }}</em></h2></div><button class="icon-button quiet" title="清空清单" @click="resetCart"><Trash2 :size="17" /></button></div>
                <div class="member-selector" @click="openDialog('memberSelect')"><UserRound :size="16" /><span v-if="selectedMember">{{ selectedMember.name }} · {{ selectedMember.phone }}</span><span v-else>选择会员（可选）</span><ChevronRight :size="14" /></div>
                <label class="sales-selector"><span>导购（销售）</span><select v-model="selectedSalesId"><option value="">无导购（散客）</option><option v-for="person in salespeople" :key="person.user_id" :value="String(person.user_id)">{{ person.real_name }}</option></select></label>
                <div class="cart-list"><div v-for="item in cart" :key="item.goodsId" class="cart-item"><div class="cart-item-main"><div><b>{{ item.name }}</b><small>{{ item.detail }}</small></div><button class="remove-button" @click="removeItem(item)"><X :size="14" /></button></div><div class="cart-item-controls"><template v-if="isGramItem(item)"><label class="weight-input">克重(g) <input v-model.number="item.weight" type="number" min="0.001" step="0.001" placeholder="称重录入" @input="item.amount = Number(item.weight || 0) * Number(item.unitPrice || 0); item.detail = productDetail({ ...item, price_type: 1 })" /></label></template><template v-else><div class="qty-stepper"><button @click="setQty(item, -1)"><Minus :size="13" /></button><b>{{ item.qty }}</b><button @click="setQty(item, 1)"><Plus :size="13" /></button></div></template><label class="labor-input">工费 <input :value="item.laborFee" type="number" min="0" step="1" @input="setLabor(item, $event.target.value)" /></label><strong>{{ money((isGramItem(item) ? item.amount : item.amount * item.qty) + (isGramItem(item) ? item.laborFee : item.laborFee * item.qty)) }}</strong></div></div><div v-if="!cart.length" class="cart-empty"><ShoppingCart :size="32" /><p>清单还是空的</p><small>从左侧选择商品开始开单</small></div></div>
                <div class="cart-bottom">
                  <button class="deduct-button" @click="openDialog('oldMetal')"><span><RefreshCw :size="17" />旧金估值</span><b v-if="oldDeduct">{{ money(oldDeduct) }}</b><ChevronRight :size="15" /></button>
                  <div class="amount-breakdown">
                    <div><span>商品小计</span><b>{{ money(cartSubtotal) }}</b></div>
                    <div><span>折扣</span><label class="discount-select"><select :value="discount" @change="setDiscount($event.target.value)"><option :value="1">不打折</option><option :value="0.95">95折</option><option :value="0.9">9折</option><option :value="0.85">85折</option><option :value="0.8">8折</option><option :value="0.75">75折</option></select></label></div>
                    <div v-if="oldDeduct"><span>旧金估值</span><b>{{ money(oldDeduct) }}</b></div>
                    <div v-if="appliedOldDeduct"><span>本单实际抵扣</span><b class="deduct-text">-{{ money(appliedOldDeduct) }}</b></div>
                    <div v-if="oldMaterialExcess" class="excess-row"><span>超额旧金回收款</span><b>{{ money(oldMaterialExcess) }}</b></div>
                  </div>
                  <div class="payable-row"><span>应收金额</span><strong>{{ money(payable) }}</strong></div>
                  <div v-if="approval.required" class="approval-banner"><ShieldAlert :size="16" /><span>{{ approval.message || '待店长审批' }}</span><button @click="refreshApproval">刷新</button></div>
                  <button class="checkout-button" :disabled="!cart.length || checkoutSubmitting || (approval.required && approval.status !== '已通过')" @click="submitOrder"><span>{{ checkoutSubmitting ? '正在创建订单' : approval.required && approval.status !== '已通过' ? '等待审批' : oldMaterialExcess ? '确认回收款并结算' : '进入结算' }}</span><ArrowRight :size="18" /></button>
                </div>
              </aside>
           </div>
           <section v-if="heldOrders.length" class="panel held-orders"><div class="held-orders-heading"><div><span class="eyebrow">待结算</span><h2>已挂起订单 <em>{{ heldOrders.length }}</em></h2></div><span class="muted">审批通过后恢复订单继续收款</span></div><div class="held-order-list"><article v-for="held in heldOrders" :key="held.heldId" class="held-order"><div><b>{{ held.orderDraft?.billNo || '本地订单' }}</b><small>{{ held.cart?.map(item => item.name).join('、') || '商品明细' }} · {{ money(held.orderDraft?.payAmount || 0) }}</small></div><span class="status-tag" :class="heldStatus(held) === '已审批' ? 'success' : heldStatus(held) === '已驳回' ? 'danger' : 'warning'">{{ heldStatus(held) }}</span><button v-if="held.approval?.id && heldStatus(held) === '待审批'" class="text-button" @click="refreshHeldApproval(held)"><RefreshCw :size="14" />刷新</button><button class="primary-button compact" @click="restoreHeldOrder(held)"><ArrowRight :size="14" />恢复</button></article></div></section>
         </template>
        <template v-else-if="activeMenu === 'processing'"><div class="page-heading"><div><div class="eyebrow">加工业务</div><h1>{{ processingDraftId ? `继续加工 ${processingDraftOrderNo}` : '加工开单与定金收款' }}</h1></div><div class="heading-actions"><button v-if="processingDraftId" class="secondary-button" @click="resetProcessingForm">取消继续加工</button><button class="secondary-button" @click="loadProcessingData"><RefreshCw :size="16" />刷新项目</button></div></div><section v-if="processingDrafts.length" class="panel table-panel" style="margin-bottom:16px"><div class="summary-title"><div><b>待加工工单</b><small>编辑待加工工单，保存后保留原单号</small></div><span class="status-tag">{{ processingDrafts.length }} 条待处理</span></div><div class="table-wrap"><table><thead><tr><th>单号</th><th>客户</th><th>项目</th><th>原状态</th><th>操作</th></tr></thead><tbody><tr v-for="draft in processingDrafts" :key="draft.id || draft.processing_order_id"><td>{{ draft.order_no || draft.orderNo || ('#' + (draft.id || draft.processing_order_id)) }}</td><td>{{ draft.customer_name || draft.customerName || '-' }}</td><td>{{ draft.processing_item_name || draft.item_name || '-' }}</td><td><span class="status-tag warning">待继续</span></td><td><button class="secondary-button" @click="resumeProcessingDraft(draft)">编辑工单</button></td></tr></tbody></table></div></section><div v-if="!online" class="warning-note"><WifiOff :size="16" />加工开单需连接后端服务，旧料在完成加工时登记。</div><div class="processing-layout"><section class="panel processing-form"><div class="summary-title"><div><b>客户与加工项目</b><small>开单只登记工费、补金和定金；完成加工后再确认回收屑。</small></div><span class="status-tag warning">在线业务</span></div><div class="form-grid processing-fields"><label>关联会员<select v-model="processingForm.memberId" @change="chooseProcessingMember"><option value="">不关联会员</option><option v-for="member in members" :key="member.id || member.member_id" :value="member.id || member.member_id">{{ member.name }} · {{ member.phone }}</option></select></label><label>预计取货日<input v-model="processingForm.pickupDate" type="date" /></label><label>客户姓名<input v-model.trim="processingForm.customerName" placeholder="必填" /></label><label>联系电话<input v-model.trim="processingForm.customerPhone" placeholder="必填" /></label><label>加工项目<select v-model="processingForm.processingItemId"><option value="">请选择项目</option><option v-for="item in processingItems" :key="item.item_id || item.id" :value="item.item_id || item.id">{{ item.name }} · {{ money(item.labor_fee) }}/{{ item.pricing_unit === '按克' ? 'g' : '件' }} · {{ item.duration_text || (item.processing_days + '天') }}</option></select></label><label>加工数量<input v-model.number="processingForm.quantity" type="number" min="1" step="1" /></label><label v-if="selectedProcessingItem?.pricing_unit === '按克'">计费总克重 (g)<input v-model.number="processingForm.billingWeight" type="number" min="0.001" step="0.001" /></label><label>加工师傅<select v-model="processingForm.craftsmanId"><option value="">暂不分配</option><option v-for="user in processingCraftsmen" :key="user.user_id" :value="user.user_id">{{ user.real_name || user.username }}</option></select></label><label>导购（销售）<select v-model="processingForm.salesId"><option value="">无导购（散客）</option><option v-for="person in processingSalespeople" :key="person.user_id" :value="String(person.user_id)">{{ person.real_name }}</option></select><small v-if="processingMemberSalesNotice" class="muted">{{ processingMemberSalesNotice }}</small></label><label>客户带来旧金 (g)<input v-model.number="processingForm.oldGoldWeight" type="number" min="0" step="0.001" placeholder="仅做登记" /></label><label>旧金成色<input v-model.number="processingForm.oldGoldFineness" type="number" min="0" max="1" step="0.001" placeholder="如 0.999" /></label><label class="field-span-2">备注<input v-model.trim="processingForm.remark" placeholder="加工要求、款式说明等" /></label></div></section><aside class="panel processing-summary"><div class="summary-title"><div><b>收款确认</b><small>{{ selectedProcessingItem ? `${selectedProcessingItem.name}，${selectedProcessingItem.pricing_unit || '按件'} · 工期约 ${selectedProcessingItem.duration_text || (selectedProcessingItem.processing_days + '天')}` : '选择加工项目后计算应收' }}</small></div><Receipt :size="20" /></div><div class="processing-money"><span>加工工费</span><strong>{{ money(processingLaborFee) }}</strong></div><div class="processing-total"><span>本单应收</span><strong>{{ money(processingDue) }}</strong></div><label>收取定金<input v-model.number="processingForm.deposit" type="number" min="0" :max="processingDue" step="0.01" /></label><label>定金支付方式<select v-model="processingForm.depositMethod"><option v-for="method in paymentMethods" :key="method.code" :value="method.code">{{ method.name }}</option></select></label><div class="processing-balance"><span>尾款待收</span><b>{{ money(Math.max(0, processingDue - Number(processingForm.deposit || 0))) }}</b></div><button class="checkout-button" :disabled="processingSubmitting || !online" @click="submitProcessingOrder"><Check :size="18" />{{ processingSubmitting ? '正在保存...' : (processingDraftId ? '保存加工单并收定金' : '创建加工单并收定金') }}</button></aside></div></template>
        <template v-else-if="activeMenu === 'process'"><div class="page-heading"><div><div class="eyebrow">项目管理</div><h1>加工服务项目</h1></div><button class="primary-button" @click="nav('processing')"><Plus :size="16" />加工开单</button></div><div class="panel table-panel"><div class="table-toolbar"><div><b>{{ processRows.length }}</b> 个已启用项目</div><span class="muted">项目配置请在管理端维护</span></div><table><thead><tr><th>项目名称</th><th>计价方式</th><th>基础工费</th><th>预计时长</th><th>工序</th><th></th></tr></thead><tbody><tr v-for="row in processRows" :key="row.id"><td><b>{{ row.name }}</b></td><td><span class="tag">{{ row.type }}</span></td><td>{{ money(row.base_fee) }}</td><td>{{ row.duration }}</td><td class="muted">{{ row.process_steps }}</td><td><button class="text-button" @click="nav('processing')">去开单</button></td></tr></tbody></table></div></template>
        <template v-else-if="activeMenu === 'member'"><div class="page-heading"><div><div class="eyebrow">会员中心</div><h1>会员快速登记与选择</h1></div><button class="primary-button" @click="createMember"><Plus :size="16" />快速登记</button></div><div class="panel table-panel"><div class="table-toolbar"><label class="inline-search"><Search :size="16" /><input v-model="memberKeyword" placeholder="姓名 / 手机号" /></label><span class="muted">{{ members.length }} 位本地会员</span></div><table><thead><tr><th>会员</th><th>手机号</th><th>生日</th><th>储值余额</th><th>积分</th><th>累计消费</th><th></th></tr></thead><tbody><tr v-for="member in members.filter(m => `${m.name}${m.phone}`.includes(memberKeyword))" :key="member.id"><td><div class="person-cell"><span class="avatar warm">{{ member.name?.slice(0, 1) }}</span><b>{{ member.name }}</b></div></td><td>{{ member.phone }}</td><td>{{ member.birthday ? String(member.birthday).slice(0, 10) : '-' }}</td><td>{{ money(member.balance) }}</td><td>{{ member.points || 0 }}</td><td>{{ money(member.total_consume) }}</td><td><button class="text-button" @click="selectedMember = member; nav('order')">用于本单</button></td></tr><tr v-if="!members.length"><td colspan="7" class="empty-table">暂无会员，点击右上角快速登记</td></tr></tbody></table></div></template>
        <template v-else-if="activeMenu === 'stock'"><div class="page-heading"><div><div class="eyebrow">库存盘点</div><h1>扫码盘点与差异提交</h1></div><button class="primary-button" :disabled="!checkRows.length" @click="submitStockCheck"><ClipboardCheck :size="16" />提交盘点审批</button></div><div class="stock-grid"><section class="panel stock-scan"><div class="scan-heading"><div><b>扫码录入</b><small>扫码枪扫描后回车确认</small></div><Search :size="18" /></div><input class="scan-input" placeholder="扫描条码" @keyup.enter="e => { const p = products.find(x => x.barcode === e.target.value.trim()); if (p) { addCheckRow(p); e.target.value = '' } }" /><div class="scan-shortcuts"><button v-for="product in products.slice(0, 5)" :key="product.id" @click="addCheckRow(product)"><Plus :size="14" />{{ product.name }}</button></div></section><section class="panel table-panel"><div class="table-toolbar"><b>盘点明细</b><span :class="checkRows.some(x => Number(x.actual) !== Number(x.stock)) ? 'danger-text' : 'muted'">差异 {{ checkRows.reduce((s, x) => s + Number(x.actual || 0) - Number(x.stock || 0), 0) }}</span></div><table><thead><tr><th>商品</th><th>系统库存</th><th>实盘数量</th><th>差异</th></tr></thead><tbody><tr v-for="row in checkRows" :key="row.id"><td><b>{{ row.name }}</b><small class="block-muted">{{ row.barcode }}</small></td><td>{{ checkDisplay(row.stock, row) }}</td><td><input v-model.number="row.actual" class="number-input" type="number" min="0" :step="checkStep(row)" /></td><td :class="Number(row.actual) - Number(row.stock) === 0 ? 'muted' : 'danger-text'">{{ checkDisplay(Number(row.actual || 0) - Number(row.stock || 0), row) }}</td></tr><tr v-if="!checkRows.length"><td colspan="4" class="empty-table">扫描商品后会出现在这里</td></tr></tbody></table></section></div></template>
        <template v-else-if="activeMenu === 'bill'"><div class="page-heading"><div><div class="eyebrow">单据查询</div><h1>历史销售单据</h1></div><button class="secondary-button" @click="loadPageData"><RefreshCw :size="16" />刷新</button></div><div class="panel table-panel"><div class="table-toolbar"><label class="inline-search"><Search :size="16" /><input v-model="billKeyword" placeholder="订单号 / 会员" /></label><span class="muted">支持小票补打与质保单补打</span></div><table><thead><tr><th>单号</th><th>时间</th><th>商品金额</th><th>净实收</th><th>状态</th><th></th></tr></thead><tbody><tr v-for="row in billRows.filter(r => `${r.order_no}${r.member_id}`.includes(billKeyword))" :key="row.id"><td><b>{{ row.order_no }}</b></td><td>{{ row.create_time || '-' }}</td><td>{{ money(row.total_amount) }}</td><td>{{ money(row.actual_paid ?? row.pay_amount) }}</td><td><span class="status-tag" :class="row.status === 1 ? 'success' : 'warning'">{{ row.status === 1 ? '已完成' : row.status === 3 ? '待审批' : row.status === 5 ? '已退款' : '草稿' }}</span></td><td><div class="inline-actions"><button class="text-button" @click="reprint(row)"><Printer :size="14" />补打</button><button v-if="Number(row.status) === 0" class="text-button" @click="resumeSalesDraft(row)"><ArrowRight :size="14" />继续开单</button><button v-if="![4, 5].includes(Number(row.status))" class="text-button danger-text" @click="withdrawSalesOrder(row)"><ArrowRightLeft :size="14" />{{ Number(row.status) === 1 ? '撤回成品单' : '撤回草稿' }}</button></div></td></tr><tr v-if="!billRows.length"><td colspan="6" class="empty-table">登录后可加载云端历史单据，离线单据保存在本地队列</td></tr></tbody></table></div></template>
         <template v-else-if="activeMenu === 'todo'"><div class="page-heading"><div><div class="eyebrow">前台待办</div><h1>前台待办</h1></div><button class="secondary-button" @click="loadFrontTodo"><RefreshCw :size="16" />刷新</button></div><div class="print-tabs todo-tabs"><button v-for="t in todoTabs" :key="t.key" :class="{ active: todoTab === t.key }" @click="todoTab = t.key">{{ t.label }} {{ t.count() }}</button></div><template v-if="todoTab === 'print'"><div class="panel table-panel"><div class="table-toolbar"><span class="muted">移动端送来的加工工单和销售小票；预览确认后由本机打印。</span></div><table><thead><tr><th>单号</th><th>类型</th><th>客户</th><th>内容</th><th>申请时间</th><th>操作</th></tr></thead><tbody><tr v-for="row in printJobs" :key="'j'+row.job_id"><td><b>{{ row.order_no }}</b></td><td><span class="status-tag" :class="row.job_type === 'PROCESSING' ? 'warning' : 'success'">{{ row.job_type === 'PROCESSING' ? '加工工单' : '销售小票' }}</span></td><td>{{ row.customer_name || '-' }}<small class="block-muted">{{ row.customer_phone || '' }}</small></td><td>{{ row.item_name_snapshot || (row.job_type === 'SALES' ? '销售单据' : '-') }}<small v-if="row.quantity" class="block-muted">数量 {{ row.quantity }}</small></td><td>{{ String(row.create_time || '-').replace('T', ' ').slice(0, 16) }}</td><td><div class="inline-actions"><button class="primary-button compact" @click="openPrintJob(row)"><Printer :size="14" />预览打印</button><button class="text-button danger-text" @click="ignorePrintJob(row)">忽略</button></div></td></tr><tr v-if="!printJobs.length"><td colspan="6" class="empty-table">暂无移动端待打印单据</td></tr></tbody></table></div></template><template v-else-if="todoTab === 'handover'"><div class="panel table-panel"><div class="table-toolbar"><span class="muted">手机端转交的加工单：确认加工即开始加工（可不打印）；需要纸质工单时点「打印工单」。</span></div><table><thead><tr><th>工单号</th><th>项目</th><th>客户</th><th>师傅</th><th>整单应收</th><th>已收</th><th>尾款</th><th>转交时间</th><th>操作</th></tr></thead><tbody><tr v-for="row in todoHandovers" :key="'h'+row.processing_order_id"><td><b>{{ row.order_no }}</b></td><td>{{ row.item_name_snapshot || '-' }} × {{ row.quantity || 1 }}</td><td>{{ row.customer_name || '-' }}<small class="block-muted">{{ row.customer_phone || '' }}</small></td><td>{{ row.craftsman_name || '未分配' }}</td><td>{{ money(row.settlement_due_amount ?? row.due_amount) }}</td><td>{{ money(row.actual_paid_amount ?? row.paid_amount) }}</td><td>{{ money(row.tail_due_amount ?? processingOutstanding(row)) }}</td><td>{{ (row.handover_time || '-').replace('T', ' ').slice(0, 16) }}</td><td><div class="inline-actions"><button v-if="processingOutstanding(row) > 0" class="secondary-button compact" @click="openProcPay(row, 'DEPOSIT')"><Receipt :size="14" />收定金</button><button class="secondary-button compact" @click="confirmProcessingHandover(row)"><Printer :size="14" />打印工单</button><button class="primary-button compact" @click="confirmProcessingStart(row)"><Check :size="14" />确认加工</button></div></td></tr><tr v-if="!todoHandovers.length"><td colspan="9" class="empty-table">暂无待确认加工单</td></tr></tbody></table></div></template><template v-else-if="todoTab === 'pay'"><div class="panel table-panel"><div class="table-toolbar"><span class="muted">收银端和移动端的待收销售单，可按待收金额继续收款</span></div><table><thead><tr><th>单号</th><th>应收</th><th>已收</th><th>待收</th><th>开单时间</th><th></th></tr></thead><tbody><tr v-for="row in todoOrders" :key="'o'+row.order_id"><td><b>{{ row.order_no }}</b></td><td>{{ money(row.discounted_due ?? row.total_amount) }}</td><td>{{ money(row.actual_paid ?? row.pay_amount ?? 0) }}</td><td>{{ money(row.remaining_due ?? row.total_amount) }}</td><td>{{ (row.create_time || '-').replace('T', ' ').slice(0, 16) }}</td><td><div class="inline-actions"><button v-if="Number(row.status) === 0" class="text-button" @click="resumeSalesDraft(row)"><ArrowRight :size="14" />继续开单</button><button class="primary-button compact" @click="payHandoverOrder(row)"><Receipt :size="14" />收款</button><button class="text-button danger-text" @click="withdrawSalesOrder(row)"><ArrowRightLeft :size="14" />撤回草稿</button><button v-if="Number(row.actual_paid ?? row.pay_amount ?? 0) === 0" class="text-button danger-text" @click="cancelHandoverOrder(row)">取消</button></div></td></tr><tr v-if="!todoOrders.length"><td colspan="6" class="empty-table">暂无待收款单据</td></tr></tbody></table></div></template><template v-else-if="todoTab === 'processing'"><div class="panel table-panel"><div class="table-toolbar"><span class="muted">加工中的单子：补金登记、损耗登记，完成后点「完成加工」进入待取货（尾款在待取货收取）</span></div><table><thead><tr><th>工单号</th><th>项目</th><th>客户</th><th>师傅</th><th>整单应收</th><th>已收</th><th>尾款</th><th>成品克重</th><th>操作</th></tr></thead><tbody><tr v-for="row in todoProcessings" :key="'g'+row.processing_order_id"><td><b>{{ row.order_no }}</b></td><td>{{ row.item_name_snapshot || '-' }} × {{ row.quantity || 1 }}</td><td>{{ row.customer_name || '-' }}<small class="block-muted">{{ row.customer_phone || '' }}</small></td><td>{{ row.craftsman_name || '未分配' }}</td><td>{{ money(row.settlement_due_amount ?? row.due_amount) }}</td><td>{{ money(row.actual_paid_amount ?? row.paid_amount) }}</td><td>{{ money(row.tail_due_amount ?? processingOutstanding(row)) }}</td><td>{{ row.finished_weight ? row.finished_weight + 'g' : '未称重' }}</td><td><div class="inline-actions"><button class="secondary-button compact" @click="confirmProcessingHandover(row, { advanceStatus: false })"><Printer :size="14" />打印工单</button><button class="primary-button compact" @click="openProcFinish(row)"><Check :size="14" />完成加工</button></div></td></tr><tr v-if="!todoProcessings.length"><td colspan="9" class="empty-table">暂无加工中的单子</td></tr></tbody></table></div></template><template v-else><div class="panel table-panel"><div class="table-toolbar"><span class="muted">加工完成待取货的单子：先收尾款，再预览质保单确认无误打印给客户</span></div><table><thead><tr><th>工单号</th><th>项目</th><th>客户</th><th>整单应收</th><th>已收</th><th>尾款</th><th>待返款</th><th>成品克重</th><th></th></tr></thead><tbody><tr v-for="row in todoPickups" :key="'p'+row.processing_order_id"><td><b>{{ row.order_no }}</b></td><td>{{ row.item_name_snapshot || '-' }} × {{ row.quantity || 1 }}</td><td>{{ row.customer_name || '-' }}<small class="block-muted">{{ row.customer_phone || '' }}</small></td><td>{{ money(row.settlement_due_amount ?? row.due_amount) }}</td><td>{{ money(row.actual_paid_amount ?? row.paid_amount) }}</td><td>{{ money(row.tail_due_amount ?? processingOutstanding(row)) }}</td><td>{{ money(row.refund_outstanding ?? processingRefundOutstanding(row)) }}</td><td>{{ row.finished_weight ? row.finished_weight + 'g' : '未称重' }}</td><td><div class="inline-actions"><button class="secondary-button compact" @click="confirmProcessingHandover(row, { advanceStatus: false })"><Printer :size="14" />打印工单</button><button v-if="processingOutstanding(row) > 0" class="primary-button compact" @click="openProcPay(row)"><Receipt :size="14" />收尾款</button><button v-if="processingRefundOutstanding(row) > 0" class="primary-button compact" @click="openProcPay(row, 'REFUND')"><Receipt :size="14" />返款</button><button v-if="processingRefundOutstanding(row) > 0" class="secondary-button compact" @click="pickupWithWarranty(row)"><BookOpen :size="14" />查看质保单</button><button v-else :class="processingOutstanding(row) > 0 ? 'secondary-button compact' : 'primary-button compact'" @click="pickupWithWarranty(row)"><BookOpen :size="14" />预览并打质保单</button><button v-if="processingOutstanding(row) === 0 && processingRefundOutstanding(row) === 0" class="secondary-button compact" @click="confirmProcessingPickup(row)"><PackageCheck :size="14" />确认取货</button><button v-if="row.status === 'COMPLETED'" class="text-button danger-text" @click="withdrawProcessingOrder(row)"><ArrowRightLeft :size="14" />撤回加工单</button></div></td></tr><tr v-if="!todoPickups.length"><td colspan="9" class="empty-table">暂无待取货加工单</td></tr></tbody></table></div></template></template>
                <template v-else-if="activeMenu === 'shift'"><div class="page-heading"><div><div class="eyebrow">交班对账</div><h1>本班收款核对</h1></div><button class="primary-button" @click="confirmShift"><Check :size="16" />确认交班</button></div><div class="shift-grid"><section class="panel shift-summary"><div class="summary-title"><b>按支付方式统计</b><span class="status-tag success">本班</span></div><div v-for="row in shiftRows" :key="row.pay_method" class="shift-row"><span>{{ paymentLabel(row.pay_method) }}</span><b>{{ money(row.amount) }}</b></div><div class="shift-total"><span>系统应收合计</span><strong>{{ money(shiftTotal) }}</strong></div></section><section class="panel cash-check"><div class="summary-title"><b>现金实点</b><Landmark :size="18" /></div><div class="cash-amount"><span>系统现金</span><strong>{{ money(shiftCashSystem) }}</strong></div><label>实点现金<input v-model.number="shiftCash" type="number" min="0" step="0.01" /></label><div class="cash-diff" :class="shiftCashDifference === 0 ? 'ok' : 'warn'"><span>差额</span><b>{{ money(shiftCashDifference) }}</b></div><label>交班备注<textarea v-model="shiftRemark" :placeholder="shiftCashDifference === 0 ? '交班备注（可选）' : '现金有差异时必须填写原因' "></textarea></label><button class="secondary-button full" @click="showShiftPreview"><Printer :size="16" />预览交班单</button></section></div></template>
      </section>
    </main>

    <div v-if="toast" class="toast"><Check :size="15" />{{ toast }}</div>

    <el-dialog v-model="dialogModel" :width="activeDialog === 'procFinish' ? '780px' : '520px'" :show-close="false" class="workflow-dialog">
      <template #header><div class="dialog-title"><div><span class="eyebrow">{{ dialogTitle }}</span><h2>{{ dialogHeading }}</h2></div><button class="icon-button quiet" @click="closeDialog"><X :size="18" /></button></div></template>
      <div v-if="activeDialog === 'account'" class="dialog-body"><div class="account-identity"><span class="avatar">{{ cashier.name.slice(0, 1) }}</span><div><strong>{{ cashier.name }}</strong><small>{{ cashierRoleName }} · {{ cashier.storeName }}</small></div></div><p class="settings-note"><Monitor :size="17" /><span>后端：{{ apiBase() }} · {{ online ? '已连接' : '未连接' }}</span></p><div class="dialog-actions"><button class="secondary-button" @click="closeDialog">关闭</button><button class="primary-button" @click="switchAccount"><UserRound :size="16" />切换账号</button></div></div>
      <form v-else-if="activeDialog === 'login'" class="dialog-body" @submit.prevent="submitLogin"><div class="form-grid"><label>账号<input v-model.trim="loginForm.username" autocomplete="username" autofocus /></label><label>密码<input v-model="loginForm.password" type="password" autocomplete="current-password" /></label></div><p class="settings-note"><Monitor :size="17" /><span>后端：{{ apiBase() }} · {{ online ? '已连接' : '未连接' }}</span></p><div class="dialog-actions"><button class="secondary-button" type="button" @click="openDialog('settings')">连接设置</button><button class="primary-button" type="submit"><Check :size="16" />登录收银台</button></div></form>
      <form v-else-if="activeDialog === 'memberCreate'" class="dialog-body" @submit.prevent="submitMemberCreate"><div class="form-grid"><label>会员姓名<input v-model.trim="memberCreateForm.name" maxlength="100" autofocus placeholder="请输入姓名" /></label><label>手机号<input v-model.trim="memberCreateForm.phone" inputmode="numeric" maxlength="11" placeholder="请输入11位手机号" /></label><label>生日<el-date-picker v-model="memberCreateForm.birthday" type="date" value-format="YYYY-MM-DD" placeholder="选择生日" clearable style="width:100%" /></label></div><p class="settings-note"><UserRound :size="17" /><span>生日为可选信息，保存后管理端和收银端会员档案会同步显示。</span></p><div class="dialog-actions"><button class="secondary-button" type="button" @click="closeDialog">取消</button><button class="primary-button" type="submit"><Check :size="16" />保存会员</button></div></form>
      <div v-else-if="activeDialog === 'oldMetal'" class="dialog-body">
        <div class="form-grid"><label>旧料类型<select v-model="oldMetalForm.materialType"><option v-for="type in oldMaterialTypes" :key="type" :value="type">{{ type }}</option></select></label><label>旧金克重 (g)<input v-model.number="oldMetalForm.weight" type="number" step="0.001" min="0" autofocus /></label><label>成色<select v-model="oldMetalForm.purityChoice"><option v-for="option in PURITY_OPTIONS" :key="option.value" :value="option.value">{{ option.label }}</option></select></label><label v-if="oldMetalForm.purityChoice === 'other'">自定义成色 (%)<input v-model.number="oldMetalForm.customPurity" type="number" min="0.1" max="100" step="0.1" placeholder="例如 96.5" /></label><label>计价金价<select v-model="oldMetalForm.priceType"><option v-for="item in gold" :key="item.price_type" :value="item.price_type">{{ item.price_type }} · {{ money(item.price) }}/g</option></select></label><label>备注<input v-model="oldMetalForm.note" placeholder="如：手镯、项链" /></label></div>
        <button class="secondary-button full" @click="addOldMetal"><Plus :size="16" />加入旧金明细</button>
        <div class="old-metal-list"><div v-for="metal in oldMetals" :key="metal.id" class="old-metal-row"><div><b>{{ metal.weight.toFixed(3) }}g · {{ purityText(metal.purity) }}</b><small>{{ metal.materialType || '旧料' }} · {{ metal.priceType }}{{ metal.note ? ` · ${metal.note}` : '' }}</small></div><strong>{{ money(metal.weight * metal.purity * (goldMap[metal.priceType] || recycleSpot)) }}</strong><button class="icon-button quiet" @click="removeOldMetal(metal)"><Trash2 :size="15" /></button></div><div v-if="!oldMetals.length" class="empty-dialog">支持多件旧金，逐件录入后统一抵扣</div></div>
        <div class="dialog-total"><span>旧金估值</span><strong>{{ money(oldDeduct) }}</strong></div>
        <div v-if="oldMaterialExcess" class="excess-payout-card">
          <div><span>本单实际抵扣</span><b>-{{ money(appliedOldDeduct) }}</b></div>
          <div><span>超额旧金回收款</span><strong>{{ money(oldMaterialExcess) }}</strong></div>
          <label>实际付款给客户<input v-model.number="oldMaterialPayoutAmount" type="number" min="0" step="0.01" /><small>默认按超额旧金回收款，可因议价上调；不得低于超额金额</small></label>
          <label>返款方式<select v-model="oldMaterialPayoutMethod"><option v-for="method in payoutMethods" :key="method.code" :value="method.code">{{ method.name }}</option></select></label>
          <small>结算完成后自动生成一条回收支出流水，旧料库存只入账一次。</small>
        </div>
        <div class="dialog-actions"><button class="secondary-button" @click="closeDialog">取消</button><button class="primary-button" @click="closeDialog"><Check :size="16" />确认旧金处理</button></div>
      </div>
      <div v-else-if="activeDialog === 'memberSelect'" class="dialog-body"><label class="search-box dialog-search"><Search :size="16" /><input v-model="memberKeyword" placeholder="搜索会员姓名或手机号" @input="searchMembersRemote" /></label><div class="member-pick-list"><button v-for="member in memberPickList" :key="member.id" class="member-pick" @click="selectOrderMember(member)"><span class="avatar warm">{{ member.name?.slice(0, 1) }}</span><span><b>{{ member.name }}</b><small>{{ member.phone }} · 归属导购 {{ salesName(member.sales_id) }} · 储值 {{ money(member.balance) }}</small></span><Check v-if="selectedMember?.id === member.id" :size="16" /></button><div v-if="!members.length" class="empty-dialog">暂无本地会员，联网后可同步会员档案</div></div><div class="dialog-actions"><button class="secondary-button" @click="selectOrderMember(null)">暂不选择</button><button class="text-button" @click="closeDialog(); createMember()"><Plus :size="14" />快速登记新会员</button></div></div>
      <div v-else-if="activeDialog === 'payment'" class="dialog-body">
         <div class="payment-total"><span>本单应收</span><strong>{{ money(payable) }}</strong><small>本次实收可直接输入，低于{{ Math.round(config.discountThreshold * 100) }}折的优惠需要店长/管理员审批</small><small v-if="oldDeduct">旧金估值 {{ money(oldDeduct) }}，本单抵扣 {{ money(appliedOldDeduct) }}</small></div>
        <div v-if="oldMaterialExcess" class="excess-payout-card compact">
          <div><span>需向客户支付回收款</span><strong>{{ money(customerPayoutAmount) }}</strong></div>
          <label>实际付款给客户<input v-model.number="oldMaterialPayoutAmount" type="number" min="0" step="0.01" /><small>默认等于超额旧金回收款，允许议价调整</small></label>
          <label>返款方式<select v-model="oldMaterialPayoutMethod"><option v-for="method in payoutMethods" :key="method.code" :value="method.code">{{ method.name }}</option></select></label>
        </div>
        <template v-if="paymentDue > 0 && paymentMethods.length">
          <div class="payment-methods"><button v-for="method in paymentMethods" :key="method.code" class="payment-method" :class="{ selected: method.selected }" @click="togglePaymentMethod(method, paymentRemaining)"><component :is="method.icon" :size="19" /><span>{{ method.name }}</span><b v-if="method.selected">{{ money(method.amount) }}</b></button></div>
           <div class="combination-tools"><span>组合支付</span><button class="text-button" @click="applyEvenPayment">均分应收</button><button v-for="method in paymentMethods.filter(p => !p.selected)" :key="method.code" class="text-button" @click="distributePayment(method)"><Plus :size="13" />{{ method.name }}</button></div>
          <div v-for="method in paymentInputMethods(paymentMethods)" :key="method.code" class="payment-input"><span>{{ method.name }}</span><input v-model.number="method.amount" type="number" min="0" :max="paymentDue" step="0.01" /><button class="icon-button quiet" @click="togglePaymentMethod(method, 0)"><X :size="14" /></button></div>
          <div class="payment-balance" :class="{ done: paymentBalanced, over: paymentDifferenceCents > 0 }"><span>本次实收</span><strong>{{ money(paidAmount) }}</strong></div>
           <div v-if="paidAmount > 0 && paidAmount < paymentDue" class="payment-adjustment"><span>优惠</span><strong>{{ money(Math.max(0, paymentDue - paidAmount)) }}</strong></div>
           <small v-if="paymentDifferenceCents < 0">还差 {{ money(paymentRemaining) }}</small>
           <small v-else-if="paidAmount > 0 && paidAmount < paymentDue">本次实收后直接结清；低于{{ Math.round(config.discountThreshold * 100) }}折会转入审批</small>
        </template>
        <div v-else-if="paymentDue > 0" class="warning-note"><ShieldAlert :size="16" />当前没有可用的支付方式，请联系管理员在系统设置中启用。</div>
        <div v-else class="settings-note"><Check :size="16" /><span>本单无需向客户收款，确认后完成销售出库并登记旧金回收返款。</span></div>
         <div class="dialog-actions"><button class="secondary-button" :disabled="paymentSubmitting" @click="closeDialog">返回订单</button><button class="primary-button" :disabled="!paymentBalanced || paymentSubmitting || (paymentDue > 0 && paidAmount <= 0)" @click="confirmPayment"><Check :size="16" />{{ paymentSubmitting ? '正在结算' : oldMaterialExcess ? '确认返款并结算' : '确认收款' }}</button></div>
      </div>
      <div v-else-if="activeDialog === 'print'" class="dialog-body print-dialog">
        <template v-if="printPreview.type === 'shift'">
          <div class="print-tabs"><button :class="{ active: shiftPaper === '58' }" @click="shiftPaper = '58'"><Receipt :size="16" />58mm交班单</button><button :class="{ active: shiftPaper === 'a4' }" @click="shiftPaper = 'a4'"><BookOpen :size="16" />A4交班单</button></div>
          <div class="receipt-preview" :class="{ 'a4-shift-preview': shiftPaper === 'a4' }"><pre>{{ printPreview.text }}</pre></div>
          <div class="print-options"><label>打印格式<select v-model="shiftPaper"><option value="58">58mm小票</option><option value="a4">A4交班单</option></select></label><label>打印机<select v-model="printSettings.deviceName"><option value="">系统默认打印机</option><option v-for="printer in printers" :key="printer.name || printer.deviceName" :value="printer.name || printer.deviceName">{{ printer.displayName || printer.name || printer.deviceName }}</option></select></label><label class="checkbox-label"><input v-model="printSettings.silent" type="checkbox" />静默打印</label></div>
          <div class="dialog-actions"><button class="secondary-button" @click="closeDialog">稍后打印</button><button class="primary-button" @click="printShift"><Printer :size="16" />打印交班单</button></div>
        </template>
        <template v-else-if="printPreview.type === 'queued'">
          <div class="print-tabs"><button class="active" type="button"><Printer :size="16" />{{ activePrintJob?.job_type === 'PROCESSING' ? '加工工单（A4）' : '销售小票' }}</button></div>
          <iframe class="a5-preview-frame" :srcdoc="printPreview.html" title="待打印单据预览"></iframe>
          <div class="print-options"><label>打印机<select v-model="printSettings.deviceName"><option value="">系统默认打印机</option><option v-for="printer in printers" :key="printer.name || printer.deviceName" :value="printer.name || printer.deviceName">{{ printer.displayName || printer.name || printer.deviceName }}</option></select></label><label class="checkbox-label"><input v-model="printSettings.silent" type="checkbox" />静默打印</label></div>
          <div class="dialog-actions"><button class="secondary-button" @click="closeDialog">保留待打印</button><button class="primary-button" @click="printQueuedJob"><Printer :size="16" />确认打印</button></div>
        </template>
        <template v-else-if="printPreview.type === 'processing'">
          <div class="print-tabs"><button class="active" type="button"><BookOpen :size="16" />加工工单（A4）</button></div>
          <iframe class="a5-preview-frame" :srcdoc="printPreview.html" title="加工工单预览"></iframe>
          <div class="print-options"><label>打印机<select v-model="printSettings.deviceName"><option value="">系统默认打印机</option><option v-for="printer in printers" :key="printer.name || printer.deviceName" :value="printer.name || printer.deviceName">{{ printer.displayName || printer.name || printer.deviceName }}</option></select></label><label class="checkbox-label"><input v-model="printSettings.silent" type="checkbox" />静默打印</label></div>
          <div class="dialog-actions"><button class="secondary-button" @click="closeDialog">关闭（不打印，不影响订单状态）</button><button class="primary-button" @click="printProcessingOrder"><Printer :size="16" />打印工单</button></div>
        </template>
        <template v-else-if="printPreview.type === 'pwarranty'">
          <div class="print-tabs"><button class="active" type="button"><BookOpen :size="16" />加工质保单（A4 两联）</button></div>
          <iframe class="a5-preview-frame" style="height:52vh" :srcdoc="printPreview.html" title="加工质保单预览"></iframe>
          <div class="print-options"><label class="paper-hint">纸张：A4 横版（一张双联，沿虚线裁切）</label><label>打印机<select v-model="printSettings.deviceName"><option value="">系统默认打印机</option><option v-for="printer in printers" :key="printer.name || printer.deviceName" :value="printer.name || printer.deviceName">{{ printer.displayName || printer.name || printer.deviceName }}</option></select></label></div>
          <div class="dialog-actions"><button class="secondary-button" @click="closeDialog">先不打印</button><button class="primary-button" @click="printProcessingWarranty"><Printer :size="16" />确认打印并完成取货</button></div>
        </template>
        <template v-else>
          <div class="print-tabs"><button :class="{ active: printPreview.type === 'receipt' }" @click="printPreview.type = 'receipt'; printPreview.text = buildReceiptPreview(receiptModel())"><Receipt :size="16" />热敏小票</button><button :class="{ active: printPreview.type === 'warranty' }" @click="printPreview.type = 'warranty'"><BookOpen :size="16" />质保单（两联）</button></div><div v-if="printPreview.type === 'receipt'" class="receipt-preview"><pre>{{ printPreview.text || buildReceiptPreview(receiptModel()) }}</pre></div><div v-else class="warranty-frame-wrap"><iframe class="warranty-preview-frame" :srcdoc="warrantyHtml()" title="质保单预览"></iframe></div><div class="print-options"><label v-if="printPreview.type === 'receipt'">小票规格<select v-model.number="printSettings.paperWidth"><option :value="58">58mm</option><option :value="80">80mm</option></select></label><label v-else class="paper-hint">纸张：A4 横版（一张双联，沿虚线裁切）</label><label>打印机<select v-model="printSettings.deviceName"><option value="">系统默认打印机</option><option v-for="printer in printers" :key="printer.name || printer.deviceName" :value="printer.name || printer.deviceName">{{ printer.displayName || printer.name || printer.deviceName }}</option></select></label><label class="checkbox-label"><input v-model="printSettings.silent" type="checkbox" />静默打印</label></div><div class="dialog-actions"><button class="secondary-button" @click="closeDialog">稍后处理</button><button class="secondary-button" @click="printPreview.type === 'receipt' ? printReceipt() : printWarranty()"><Printer :size="16" />{{ printPreview.type === 'receipt' ? '打印小票' : '打印质保单' }}</button><button v-if="printPreview.type === 'receipt'" class="primary-button" @click="printPreview.type = 'warranty'"><BookOpen :size="16" />查看质保单</button></div>
        </template>
      </div>
      <div v-else-if="activeDialog === 'recycle'" class="dialog-body"><div class="form-grid"><label>旧料类型<select v-model="recycleForm.materialType"><option v-for="type in oldMaterialTypes" :key="type" :value="type">{{ type }}</option></select></label><label>克重 (g)<input v-model.number="recycleForm.weight" type="number" step="0.001" min="0" /></label><label>成色<select v-model="recycleForm.purityChoice"><option v-for="option in PURITY_OPTIONS" :key="option.value" :value="option.value">{{ option.label }}</option></select></label><label v-if="recycleForm.purityChoice === 'other'">自定义成色 (%)<input v-model.number="recycleForm.customPurity" type="number" min="0.1" max="100" step="0.1" placeholder="例如 96.5" /></label><label>扣损比例 (%)<input v-model.number="recycleForm.deductLossRate" type="number" min="0" max="99" step="0.01" /></label><label>回收金价<input v-model.number="recycleForm.recyclePrice" type="number" min="0" step="0.01" /></label><label>支付方式<select v-model="recycleForm.payMethod"><option v-if="!payoutMethods.length" value="" disabled>暂无可用支付方式</option><option v-for="method in payoutMethods" :key="method.code" :value="method.code">{{ method.name }}</option></select></label></div><div class="calculation-card"><span>回收金额</span><strong>{{ money(recycleAmount) }}</strong><small>{{ recycleForm.weight || 0 }}g × {{ purityText(recyclePurity) }} × {{ money(recycleForm.recyclePrice || recycleSpot) }} × (1 - {{ recycleForm.deductLossRate || 0 }}%)</small></div><div v-if="recycleAmount > config.recycleLimit" class="warning-note"><ShieldAlert :size="16" />超过 {{ money(config.recycleLimit) }}，提交后需店长审批</div><div class="dialog-actions"><button class="secondary-button" @click="closeDialog">取消</button><button class="primary-button" @click="saveRecycle"><Check :size="16" />保存回收单</button></div></div>
      <div v-else-if="activeDialog === 'tradein'" class="dialog-body"><div class="form-grid"><label>旧金信息<input v-model="tradeForm.oldMaterialInfo" placeholder="如：足金手镯 20g" /></label><label v-if="!tradeOldMetals.length">旧金估值<input v-model.number="tradeForm.oldValue" type="number" min="0" step="0.01" /></label><label>新商品信息<input v-model="tradeForm.newGoodsInfo" placeholder="商品名称 / 条码" /></label><label>新商品金额<input v-model.number="tradeForm.newValue" type="number" min="0" step="0.01" /></label></div><div class="subsection-title">旧料明细（可添加多件）</div><div class="form-grid"><label>旧料类型<select v-model="tradeOldForm.materialType"><option v-for="type in oldMaterialTypes" :key="type" :value="type">{{ type }}</option></select></label><label>克重 (g)<input v-model.number="tradeOldForm.weight" type="number" min="0.001" step="0.001" /></label><label>成色<select v-model="tradeOldForm.purityChoice"><option v-for="option in PURITY_OPTIONS" :key="option.value" :value="option.value">{{ option.label }}</option></select></label><label v-if="tradeOldForm.purityChoice === 'other'">自定义成色 (%)<input v-model.number="tradeOldForm.customPurity" type="number" min="0.1" max="100" step="0.1" placeholder="例如 96.5" /></label></div><button class="secondary-button full" @click="addTradeOldMetal"><Plus :size="16" />加入旧料</button><div class="old-metal-list"><div v-for="metal in tradeOldMetals" :key="metal.id" class="old-metal-row"><div><b>{{ Number(metal.weight).toFixed(3) }}g · {{ purityText(metal.purity) }}</b><small>{{ metal.materialType }}</small></div><strong>{{ money(Number(metal.weight) * Number(metal.purity) * recycleSpot) }}</strong><button class="icon-button quiet" @click="removeTradeOldMetal(metal)"><Trash2 :size="15" /></button></div><div v-if="!tradeOldMetals.length" class="empty-dialog">暂无旧料明细</div></div><div class="dialog-total"><span>旧金估值</span><strong>{{ money(tradeOldMetals.length ? tradeOldTotal : tradeForm.oldValue) }}</strong></div><div class="calculation-card" :class="(Number(tradeForm.newValue) - (tradeOldMetals.length ? tradeOldTotal : Number(tradeForm.oldValue || 0))) < 0 ? 'negative' : ''"><span>{{ (Number(tradeForm.newValue) - (tradeOldMetals.length ? tradeOldTotal : Number(tradeForm.oldValue || 0))) >= 0 ? '预计补差价' : '预计回收款' }}</span><strong>{{ money(Math.abs(Number(tradeForm.newValue) - (tradeOldMetals.length ? tradeOldTotal : Number(tradeForm.oldValue || 0)))) }}</strong><small>新商品金额 {{ money(tradeForm.newValue) }} - 旧金估值 {{ money(tradeOldMetals.length ? tradeOldTotal : tradeForm.oldValue) }}</small></div><div class="dialog-actions"><button class="secondary-button" @click="closeDialog">取消</button><button class="primary-button" @click="saveTradeIn"><Check :size="16" />选择商品开单</button></div></div>
      <div v-else-if="activeDialog === 'approval'" class="dialog-body"><div class="approval-detail"><div class="approval-icon"><ShieldAlert :size="26" /></div><h3>优惠审批已提交</h3><p>{{ approval.message || `本单折扣低于配置阈值${Math.round(config.discountThreshold * 100)}折，店长/管理员通过审批后才能继续结算。` }}</p><div class="approval-state"><Clock3 :size="16" />{{ approval.status || '待审批' }}</div></div><div class="dialog-actions"><button class="secondary-button" @click="holdCurrentOrder"><Archive :size="16" />挂起并开新单</button><button class="text-button" @click="closeDialog">稍后处理</button><button class="primary-button" @click="refreshApproval"><RefreshCw :size="16" />刷新审批状态</button></div></div>
      <div v-else-if="activeDialog === 'notifications'" class="dialog-body"><div v-if="!notifications.length" class="empty-dialog">暂无通知，每天 8:30/12:30/16:30/19:30 自动生成超期与库存提醒</div><div v-for="n in notifications" :class="['old-metal-row', { 'danger-text': n.action === 'REMIND' }]" :key="n.notification_id"><div><b>{{ n.content }}</b><small>{{ String(n.create_time || '').replace('T', ' ').slice(0, 16) }} · {{ n.action === 'REMIND' ? '未读' : '已读' }}</small></div></div></div>
      <div v-else-if="activeDialog === 'conflict'" class="dialog-body conflict-dialog"><div class="approval-detail"><div class="approval-icon danger"><ShieldAlert :size="26" /></div><h3>发现数据冲突</h3><p>请选择每条冲突保留的版本。系统不会静默覆盖库存或金额数据。</p></div><div v-if="!conflictRows.length" class="empty-dialog">当前没有待处理冲突</div><div v-for="row in conflictRows" :key="row.id || row.client_request_id" class="conflict-card"><div class="conflict-card-head"><b>{{ row.path }}</b><small>{{ row.reason || '版本校验失败' }}</small></div><table class="conflict-table"><thead><tr><th>字段</th><th>本地版本</th><th>云端版本</th></tr></thead><tbody><tr v-for="field in conflictFields(row)" :key="field.key" :class="{ changed: field.changed }"><td>{{ field.key }}</td><td>{{ typeof field.local === 'object' ? JSON.stringify(field.local) : (field.local ?? '-') }}</td><td>{{ typeof field.cloud === 'object' ? JSON.stringify(field.cloud) : (field.cloud ?? '-') }}</td></tr></tbody></table><div class="conflict-actions"><button class="secondary-button" @click="resolveConflict(row, 'CLOUD')"><Cloud :size="15" />保留云端</button><button class="primary-button" @click="resolveConflict(row, 'LOCAL')"><Upload :size="15" />保留本地并重试</button></div></div><div class="dialog-actions"><button class="secondary-button" @click="closeDialog">稍后处理</button><button class="text-button" @click="loadConflicts"><RefreshCw :size="14" />刷新冲突</button></div></div>
      <div v-else-if="activeDialog === 'settings'" class="dialog-body"><div class="form-grid"><label>后端 API 地址<input v-model.trim="apiEndpoint" placeholder="https://admin.xinchengjinjiang.com" /></label><label>热敏纸宽度<select v-model.number="printSettings.paperWidth"><option :value="58">58mm</option><option :value="80">80mm</option></select></label><label>打印机名称<select v-model="printSettings.deviceName"><option value="">系统默认打印机</option><option v-for="printer in printers" :key="printer.name || printer.deviceName" :value="printer.name || printer.deviceName">{{ printer.displayName || printer.name || printer.deviceName }}</option></select></label><label class="checkbox-label"><input v-model="printSettings.silent" type="checkbox" />静默打印（系统打印队列）</label></div><div class="settings-note"><Monitor :size="17" /><span>云端地址：<button class="text-button" @click="apiEndpoint = 'https://admin.xinchengjinjiang.com'">admin.xinchengjinjiang.com</button></span></div><div class="dialog-actions"><button class="primary-button" @click="saveSettings"><Check :size="16" />保存设置</button></div></div>
      <div v-else-if="activeDialog === 'procFinish'" class="dialog-body">
        <h3 style="margin:0 0 6px">完成加工登记 · {{ procFinish.row?.order_no || '' }}</h3>
        <p class="muted" style="margin:0 0 10px">来料为必填项（可现场补录）；提交后本单进入「待取货」，尾款在待取货环节收取。</p>
        <h2 class="finish-sec">① 来料（必填，可现场补录）</h2>
        <div class="form-grid"><label>来料克重 (g)<input v-model.number="procFinish.oldGoldWeight" type="number" min="0" step="0.001" /></label><label>来料成色（0~1）<input v-model.number="procFinish.oldGoldFineness" type="number" min="0" max="1" step="0.001" /></label><label>融后金重 (g)<input v-model.number="procFinish.meltWeight" type="number" min="0" step="0.001" /><small class="muted">选填：来料熔化后的金重</small></label><small class="muted">客户没有旧金请填 0；成色 0.995 及以上按整克计价，低于才按含金量折算。</small></div>
        <h2 class="finish-sec">② 补金登记（选填）</h2>
        <div class="form-grid"><label>补金克重 (g)<input v-model.number="procFinish.goldWeight" type="number" min="0" step="0.001" /></label><label>成色<input v-model.number="procFinish.goldFineness" type="number" min="0" max="1" step="0.001" /></label><label>计价金价（留 0 取当日价）<input v-model.number="procFinish.goldPrice" type="number" min="0" step="0.01" /></label><label>下料 (g)<input v-model.number="procFinish.downMaterialWeight" type="number" min="0" step="0.001" /><small class="muted">选填：为客户好做额外加的金料（不单独计费、不扣库存；已含在成品里，计入损耗率分母）</small></label></div>
        <p v-if="procFinishGold.hint" class="muted" style="margin:4px 0 0">补金参考（成品实重 − 融后金重）：{{ procFinishGold.hint }}</p>
        <h2 class="finish-sec">③ 称重与损耗（选填）</h2>
        <div class="form-grid"><label>成品实重 (g)<input v-model.number="procFinish.finishedWeight" type="number" min="0" step="0.001" /></label><label>成品成色（可选 0~1）<input v-model.number="procFinish.finishedFineness" type="number" min="0" max="1" step="0.001" /></label><label>损耗 (g)<input v-model.number="procFinish.recoveredWeight" type="number" min="0" step="0.001" /><small class="muted">选填：打磨收集的屑重，用于师傅考核</small></label><label v-if="procFinishBilling.byGram">计费总克重 (g)<input v-model.number="procFinish.billingWeight" type="number" min="0" step="0.001" /><small class="muted">按克项目：工费 = ¥{{ money(procFinishBilling.unitFee) }}/g × 计费总克重 = <b>¥{{ money(procFinishBilling.laborFee) }}</b>（默认带出成品实重）</small></label><label>备注<input v-model.trim="procFinish.note" /></label></div>
        <h2 class="finish-sec">④ 回收屑（自动计算）</h2>
        <div class="processing-handling"><label><input v-model="procFinish.residualGoldHandling" type="radio" value="TAKE_AWAY" />客户带走，不计回收屑</label><label><input v-model="procFinish.residualGoldHandling" type="radio" value="STORE_DEDUCT" />留店抵扣工费，入旧料库存</label></div>
        <div v-if="procFinish.residualGoldHandling === 'STORE_DEDUCT'" class="form-grid"><label>回收屑类型<select v-model="procFinish.residualMaterialType"><option v-for="type in oldMaterialTypes" :key="type" :value="type">{{ type }}</option></select></label><label>回收价格 (元/g)<input v-model.number="procFinish.residualRecyclePrice" type="number" min="0" step="0.01" /><small class="muted">留 0 取系统回收价 ¥{{ money(recycleSpot) }}/g</small></label><div class="calculation-card compact"><span>回收屑</span><strong>{{ procFinishResidualWeight.toFixed(3) }}g</strong><small>融后金重（未填时按来料克重）− 成品实重，不足按 0；按 ¥{{ money(procFinishRecyclePrice) }}/g 抵扣，抵扣可超过工费，超额转为客户返款</small></div><div class="calculation-card compact"><span>预计回收屑抵扣</span><strong>-{{ money(procFinishResidualDeduction) }}</strong><small>以完成加工时后端结算结果为准</small></div></div>
        <h2 class="finish-sec">⑤ 来料照片</h2>
        <div class="finish-photos" @dragover.prevent @drop.prevent="dropFinishPhotos('incoming', $event)"><span v-for="(u,i) in procFinish.incoming" :key="'fi'+i" class="finish-photo"><img :src="absFileUrl(u)" /><i @click="removeFinishPhoto('incoming', i)">×</i></span><label class="finish-add">＋<input type="file" accept="image/*" multiple hidden @change="pickFinishPhotos('incoming', $event.target)" /><small>拍照/选图/拖图</small></label></div>
        <h2 class="finish-sec">⑥ 称重照片</h2>
        <div class="finish-photos" @dragover.prevent @drop.prevent="dropFinishPhotos('weighPhotos', $event)"><span v-for="(u,i) in procFinish.weighPhotos" :key="'fw'+i" class="finish-photo"><img :src="absFileUrl(u)" /><i @click="removeFinishPhoto('weighPhotos', i)">×</i></span><label class="finish-add">＋<input type="file" accept="image/*" multiple hidden @change="pickFinishPhotos('weighPhotos', $event.target)" /><small>拍照/选图/拖图</small></label></div>
        <div class="dialog-actions"><button class="secondary-button" @click="closeDialog">取消</button><button class="primary-button" :disabled="procFinish.busy" @click="submitProcFinish"><Check :size="16" />{{ procFinish.busy ? '正在提交...' : '确认完成加工' }}</button></div>
      </div>
      <div v-else-if="activeDialog === 'procPickup'" class="dialog-body">
        <h3 style="margin:0 0 6px">确认取货 · {{ procPickup.row?.order_no || '' }}</h3>
        <p class="muted" style="margin:0 0 10px">请上传顾客取货现场照片。确认后本单将完成取货，照片最多 6 张。</p>
        <div class="finish-photos" @dragover.prevent @drop.prevent="dropPickupPhotos($event)">
          <span v-for="(u, i) in procPickup.photos" :key="'fp' + i" class="finish-photo"><img :src="absFileUrl(u)" alt="取货照片" /><i @click="removePickupPhoto(i)">×</i></span>
          <label v-if="procPickup.photos.length < 6" class="finish-add">＋<input type="file" accept="image/*" multiple hidden @change="pickPickupPhotos($event.target)" /><small>拍照/选图/拖图</small></label>
        </div>
        <div class="dialog-actions"><button class="secondary-button" @click="closeDialog">取消</button><button class="primary-button" :disabled="procPickup.busy || !procPickup.photos.length" @click="submitProcPickup"><Check :size="16" />{{ procPickup.busy ? '正在提交...' : '确认取货' }}</button></div>
      </div>
      <div v-else-if="activeDialog === 'procPay'" class="dialog-body">
        <h3 style="margin:0 0 6px">{{ procPayType === 'DEPOSIT' ? '收定金' : procPayType === 'REFUND' ? '向客户返款' : '收尾款' }} · {{ procManage?.order_no || '' }}</h3>
        <p class="muted" style="margin:0 0 10px">应收 {{ money(procManage?.settlement_due_amount ?? procManage?.due_amount) }} · 已收 {{ money(procManage?.actual_paid_amount ?? procManage?.paid_amount) }} · 尾款 <b>{{ money(processingOutstanding(procManage)) }}</b><span v-if="processingRefundOutstanding(procManage) > 0"> · 待返款 <b>{{ money(processingRefundOutstanding(procManage)) }}</b></span></p>
        <p v-if="procPayType === 'REFUND'" class="settings-note"><Receipt :size="16" />返款金额默认等于待返款，返款审批通过后才能完成取货。</p>
         <p v-if="procPayType === 'BALANCE' && !procGroupPayment" class="settings-note"><Receipt :size="16" />直接输入本次实收；低于{{ Math.round(config.discountThreshold * 100) }}折的优惠需要店长/管理员审批。</p>
        <label v-if="procPayType === 'DEPOSIT'">本次定金<input v-model.number="procPayAmount" type="number" min="0.01" :max="processingOutstanding(procManage)" step="0.01" /></label>
        <label v-else-if="procPayType === 'REFUND'">本次返款<input v-model.number="procPayAmount" type="number" min="0.01" :max="processingRefundOutstanding(procManage)" step="0.01" /></label>
         <label v-else-if="!procGroupPayment">本次实收<input v-model.number="procPayAmount" type="number" min="0.01" :max="processingOutstanding(procManage)" step="0.01" /></label>
         <p v-if="procPayType === 'BALANCE' && !procGroupPayment && Number(procPayAmount) > 0 && Number(procPayAmount) < processingOutstanding(procManage)" class="muted" style="margin:0">优惠 {{ money(procPayDiscount) }}</p>
        <label>支付方式<select v-model="procPayMethod" @change="changeProcPayMethod"><option v-if="!procPayMethods.length" value="" disabled>暂无可用支付方式</option><option v-for="method in procPayMethods" :key="method.code" :value="method.code">{{ method.name }}</option></select></label>
        <template v-if="procGroupPayment"><label>本次实收<input v-model.number="procPayAmount" type="number" min="0.01" :max="processingOutstanding(procManage)" step="0.01" /></label><label>团购核销单号<input v-model.trim="procVoucherNo" maxlength="100" placeholder="输入平台核销单号" /></label><p class="muted" style="margin:0">团购优惠 {{ money(procPayDiscount) }} · 本次收款后结清</p></template>
        <div class="dialog-actions"><button class="secondary-button" :disabled="procPayBusy" @click="closeDialog">取消</button><button class="primary-button" :disabled="procPayBusy || !Number(procPayAmount) || (procPayType === 'REFUND' ? procPayAmount > processingRefundOutstanding(procManage) : procPayAmount > processingOutstanding(procManage))" @click="confirmProcPay"><Check :size="16" />{{ procPayBusy ? '处理中...' : procPayType === 'DEPOSIT' ? '确认收定金' : procPayType === 'REFUND' ? '确认返款' : '确认实收' }}</button></div>
      </div>
      <div v-else-if="activeDialog === 'procGold'" class="dialog-body"><h3 style="margin:0 0 6px">补金登记（成品反推） · {{ procManage?.order_no || '' }}</h3><p class="muted" style="margin:0 0 10px">金额并入应收，「足金用料」库存按差额自动扣减；可重复登记修正。下料只登记克重，不计费也不扣库存。</p><label>补金克重 (g)<input v-model.number="procGoldForm.weight" type="number" min="0.001" step="0.001" /></label><label>成色<input v-model.number="procGoldForm.fineness" type="number" min="0" max="1" step="0.001" /></label><label>计价金价（留 0 取当日足金价）<input v-model.number="procGoldForm.price" type="number" min="0" step="0.01" /></label><label>下料 (g)<input v-model.number="procGoldForm.downMaterial" type="number" min="0" step="0.001" /><small class="muted">选填：额外加的金料（不单独计费、不扣库存）</small></label><p v-if="procGoldDialogBalance.hint" class="muted" style="margin:4px 0 0">补金参考（成品实重 − 融后金重）：{{ procGoldDialogBalance.hint }}</p><div class="dialog-actions"><button class="secondary-button" @click="closeDialog">取消</button><button class="primary-button" @click="confirmProcGold"><Check :size="16" />确认登记</button></div></div>
      <div v-else-if="activeDialog === 'procWeigh'" class="dialog-body"><h3 style="margin:0 0 6px">称重与损耗登记 · {{ procManage?.order_no || '' }}</h3><p class="muted" style="margin:0 0 10px">损耗指打磨/锉修收集的屑重，用于师傅考核（损耗率 = 损耗 ÷（融后金重 + 下料）），超约定值自动预警。</p><label>成品实重 (g)<input v-model.number="procWeighForm.finishedWeight" type="number" min="0.001" step="0.001" /></label><label>成品成色（可选，0~1）<input v-model.number="procWeighForm.finishedFineness" type="number" min="0" max="1" step="0.001" /></label><label v-if="procManage?.pricing_unit === '按克'">计费总克重 (g)<input v-model.number="procWeighForm.billingWeight" type="number" min="0.001" step="0.001" /><small class="muted">按克项目：工费 = ¥{{ money(Number(procManage?.unit_labor_fee || 0)) }}/g × 计费总克重，默认带出成品实重</small></label><label>损耗 (g)<input v-model.number="procWeighForm.recoveredWeight" type="number" min="0" step="0.001" /><small class="muted">选填：打磨收集的屑重，用于师傅考核</small></label><label>融后金重 (g)<input v-model.number="procWeighForm.meltedWeight" type="number" min="0" step="0.001" /><small class="muted">选填：来料熔化后的金重</small></label><label>备注<textarea v-model.trim="procWeighForm.note" rows="2" /></label><div class="dialog-actions"><button class="secondary-button" @click="closeDialog">取消</button><button class="primary-button" @click="confirmProcWeigh"><Check :size="16" />确认登记</button></div></div>
    </el-dialog>
    </div>
  </el-config-provider>
</template>


