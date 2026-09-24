<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { memberApi, processingApi } from '../api/modules'
import { formatMoney, formatPaymentMethod as formatConfiguredPaymentMethod, formatTime } from '../utils/format'
import { useAppStore } from '../stores/app'

const route = useRoute()
const router = useRouter()
const app = useAppStore()
const tab = computed(() => route.meta.processingTab || 'dashboard')
const categories = ref([])
const items = ref([])
const orders = ref([])
const craftsmen = ref([])
const members = ref([])
const lossRows = ref([])
const lossPermille = ref(3)
const lossAnomalyCount = ref(0)
const lossFrom = ref('')
const lossTo = ref('')
const lossSaving = ref(false)
const lossDetailDialog = ref(false)
const lossDetailLoading = ref(false)
const lossDetailRows = ref([])
const lossDetailFilter = reactive({ craftsmanId: null, name: '', onlyOver: false, anomalies: false })
async function loadLoss() {
  try {
    const params = { ...(lossFrom.value ? { from: lossFrom.value } : {}), ...(lossTo.value ? { to: lossTo.value } : {}) }
    const data = await processingApi.lossSummary(params) || {}
    lossRows.value = data.rows || []
    lossPermille.value = Number(data.permille || 3)
    lossAnomalyCount.value = Number(data.anomalyCount || 0)
  } catch (error) { ElMessage.error(error?.message || '损耗考核加载失败') }
}
async function loadLossDetails() {
  lossDetailLoading.value = true
  try {
    lossDetailRows.value = await processingApi.lossOrders({
      ...(lossDetailFilter.craftsmanId != null ? { craftsmanId: lossDetailFilter.craftsmanId } : {}),
      ...(lossFrom.value ? { from: lossFrom.value } : {}), ...(lossTo.value ? { to: lossTo.value } : {}),
      onlyOver: lossDetailFilter.onlyOver, anomalies: lossDetailFilter.anomalies
    }) || []
  } catch (error) { ElMessage.error(error?.message || '损耗明细加载失败') } finally { lossDetailLoading.value = false }
}
async function openLossDetails(row = null, anomalies = false) {
  Object.assign(lossDetailFilter, { craftsmanId: row ? Number(row.craftsman_id || 0) : null, name: row?.name || '全部师傅', onlyOver: false, anomalies })
  lossDetailDialog.value = true
  await loadLossDetails()
}
async function saveLossConfig() {
  lossSaving.value = true
  try { const r = await processingApi.lossConfigPut({ permille: Number(lossPermille.value) }); lossPermille.value = Number(r.permille); ElMessage.success('约定损耗已保存') } catch (error) { ElMessage.error(error?.message || '保存失败') } finally { lossSaving.value = false }
}
const commissions = ref([])
const commissionSummary = ref({})
const statistics = ref({})
const loading = ref(false)
const orderFilters = reactive({ keyword: '', status: '', craftsmanId: null, dates: [] })
const dashboardFilters = reactive({ dates: [], craftsmanId: null })
const categoryDialog = ref(false)
const categoryFormDialog = ref(false)
const itemDialog = ref(false)
const orderDialog = ref(false)
const detailDialog = ref(false)
const paymentDialog = ref(false)
const assignDialog = ref(false)
const storeGoldDialog = ref(false)
const weighDialog = ref(false)
const weighSaving = ref(false)
const weighForm = reactive({ finishedWeight: null, finishedFineness: null, recoveredWeight: null, note: '' })
const storeGoldSaving = ref(false)
const storeGoldForm = reactive({ weight: null, fineness: 0.999, price: 0 })
const categoryEditing = ref(null)
const itemEditing = ref(null)
const selectedOrder = ref(null)
const categoryForm = reactive({ name: '', categoryCode: '', sort: 0, status: 1 })
const itemForm = reactive({ categoryId: null, name: '', itemCode: '', laborFee: 0, pricingUnit: '按件', processingDays: 1, durationText: '', processSteps: '', commissionRate: 0, status: 1, remark: '' })
const orderForm = reactive({ memberId: null, customerName: '', customerPhone: '', processingItemId: null, quantity: 1, billingWeight: null, oldGoldWeight: null, oldGoldFineness: 0.999, residualGoldHandling: 'TAKE_AWAY', residualMaterialType: '足金999', residualGoldWeight: null, residualGoldFineness: 0.999, pickupDate: '', craftsmanId: null, remark: '' })
const paymentForm = reactive({ paymentType: 'DEPOSIT', payMethod: 'CASH', amount: 0, remark: '' })
const assignForm = reactive({ craftsmanId: null })
const materialTypes = ['足金999', '足金990', '22K金', '18K金', '14K金', '铂金950', '铂金900', '纯银', '其他']
const paymentChannels = computed(() => app.paymentChannels.filter(channel => Number(channel.status) === 1 && !['COMBINATION', 'DOUYIN_GROUP', 'MEITUAN_GROUP'].includes(String(channel.channel_code).toUpperCase())))
const formatPaymentMethod = value => formatConfiguredPaymentMethod(value, app.paymentChannels)

const currentItem = computed(() => items.value.find(item => Number(item.item_id) === Number(orderForm.processingItemId)))
const orderStatus = value => ({ PENDING: ['待加工', 'info'], PROCESSING: ['加工中', 'warning'], COMPLETED: ['已完成待取货', 'success'], PICKED_UP: ['已取货', 'primary'] }[value] || ['-', 'info'])
const paymentType = value => value === 'DEPOSIT' ? '定金' : value === 'BALANCE' ? '尾款' : '-'
const handlingText = value => value === 'STORE_DEDUCT' ? '留店抵扣工费' : '客户带走'
const parsePhotos = v => { if (Array.isArray(v)) return v; try { const a = JSON.parse(v || '[]'); return Array.isArray(a) ? a : [] } catch { return [] } }
const dateRange = dates => ({ from: dates?.[0] || undefined, to: dates?.[1] || undefined })

async function loadShared() {
  const results = await Promise.all([
    processingApi.categories(), processingApi.items(), processingApi.craftsmen(), memberApi.list({ page: 1, size: 500 })
  ])
  categories.value = results[0] || []
  items.value = results[1] || []
  craftsmen.value = results[2] || []
  members.value = results[3]?.records || []
}
async function loadOrders() {
  orders.value = await processingApi.orders({ keyword: orderFilters.keyword || undefined, status: orderFilters.status || undefined, craftsmanId: orderFilters.craftsmanId || undefined, ...dateRange(orderFilters.dates) }) || []
}
async function loadDashboard() {
  statistics.value = await processingApi.statistics({ craftsmanId: dashboardFilters.craftsmanId || undefined, ...dateRange(dashboardFilters.dates) }) || {}
}
async function loadCommissions() {
  const [rows, summary] = await Promise.all([processingApi.commissions({}), processingApi.commissionSummary()])
  commissions.value = rows || []
  commissionSummary.value = summary || {}
}
async function loadCurrent() {
  loading.value = true
  try {
    if (!categories.value.length || !items.value.length || !craftsmen.value.length) await loadShared()
    if (tab.value === 'dashboard') await loadDashboard()
    if (tab.value === 'orders') await loadOrders()
    if (tab.value === 'commissions') await loadCommissions()
    if (tab.value === 'loss') await loadLoss()
  } catch (error) {
    ElMessage.error(error?.message || '加工数据加载失败')
  } finally { loading.value = false }
}
function go(name) { router.push(`/processing-${name}`) }
function openCategoryManager() { categoryDialog.value = true }
function openCategoryForm(row = null) {
  categoryEditing.value = row
  Object.assign(categoryForm, { name: row?.name || '', categoryCode: row?.category_code || '', sort: Number(row?.sort || 0), status: Number(row?.status ?? 1) })
  categoryFormDialog.value = true
}
async function saveCategory() {
  if (!categoryForm.name.trim() || !categoryForm.categoryCode.trim()) return ElMessage.warning('请填写分类名称和分类编码')
  try {
    const data = { ...categoryForm, name: categoryForm.name.trim(), categoryCode: categoryForm.categoryCode.trim() }
    if (categoryEditing.value) await processingApi.updateCategory(categoryEditing.value.category_id, data)
    else await processingApi.createCategory(data)
    categoryFormDialog.value = false
    await loadShared()
    ElMessage.success('加工分类已保存')
  } catch (error) { ElMessage.error(error?.message || '加工分类保存失败') }
}
async function toggleCategory(row) {
  try { await processingApi.categoryStatus(row.category_id, Number(row.status) === 1 ? 0 : 1); await loadShared(); ElMessage.success(Number(row.status) === 1 ? '加工分类已禁用' : '加工分类已启用') } catch (error) { ElMessage.error(error?.message || '状态更新失败') }
}
async function deleteCategory(row) {
  try {
    await ElMessageBox.confirm(`确定删除加工分类“${row.name}”吗？删除后不可恢复。`, '二次确认', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
    await processingApi.deleteCategory(row.category_id); await loadShared(); ElMessage.success('加工分类已删除')
  } catch (error) { if (!['cancel', 'close'].includes(error) && error?.message !== 'cancel') ElMessage.error(error?.message || '加工分类删除失败') }
}
function openItem(row = null) {
  itemEditing.value = row
  Object.assign(itemForm, { categoryId: row?.category_id || categories.value.find(item => Number(item.status) === 1)?.category_id || null, name: row?.name || '', itemCode: row?.item_code || '', laborFee: Number(row?.labor_fee || 0), pricingUnit: row?.pricing_unit || '按件', processingDays: Number(row?.processing_days || 1), durationText: row?.duration_text || '', processSteps: row?.process_steps || '', commissionRate: Number(row?.commission_rate || 0), status: Number(row?.status ?? 1), remark: row?.remark || '' })
  itemDialog.value = true
}
async function saveItem() {
  if (!itemForm.categoryId || !itemForm.name.trim() || !itemForm.itemCode.trim()) return ElMessage.warning('请填写分类、项目名称和项目编码')
  if (Number(itemForm.laborFee) < 0 || Number(itemForm.processingDays) < 0 || Number(itemForm.commissionRate) < 0 || Number(itemForm.commissionRate) > 100) return ElMessage.warning('工费、周期和提成比例填写不正确')
  if (!['按件', '按克'].includes(itemForm.pricingUnit)) return ElMessage.warning('计价方式只能是按件或按克')
  try {
    const data = { ...itemForm, name: itemForm.name.trim(), itemCode: itemForm.itemCode.trim(), laborFee: Number(itemForm.laborFee), processingDays: Number(itemForm.processingDays), commissionRate: Number(itemForm.commissionRate), durationText: String(itemForm.durationText || '').trim(), processSteps: String(itemForm.processSteps || '').trim() }
    if (itemEditing.value) await processingApi.updateItem(itemEditing.value.item_id, data)
    else await processingApi.createItem(data)
    itemDialog.value = false; await loadShared(); ElMessage.success('加工项目已保存')
  } catch (error) { ElMessage.error(error?.message || '加工项目保存失败') }
}
async function toggleItem(row) {
  try { await processingApi.itemStatus(row.item_id, Number(row.status) === 1 ? 0 : 1); await loadShared(); ElMessage.success(Number(row.status) === 1 ? '加工项目已禁用' : '加工项目已启用') } catch (error) { ElMessage.error(error?.message || '状态更新失败') }
}
async function deleteItem(row) {
  try {
    await ElMessageBox.confirm(`确定删除加工项目“${row.name}”吗？删除后不可恢复。`, '二次确认', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
    await processingApi.deleteItem(row.item_id); await loadShared(); ElMessage.success('加工项目已删除')
  } catch (error) { if (!['cancel', 'close'].includes(error) && error?.message !== 'cancel') ElMessage.error(error?.message || '加工项目删除失败') }
}
function selectMember(id) {
  const member = members.value.find(row => Number(row.member_id) === Number(id))
  if (member) { orderForm.customerName = member.name || ''; orderForm.customerPhone = member.phone || '' }
}
function openOrder() {
  Object.assign(orderForm, { memberId: null, customerName: '', customerPhone: '', processingItemId: items.value.find(row => Number(row.status) === 1)?.item_id || null, quantity: 1, billingWeight: null, oldGoldWeight: null, oldGoldFineness: 0.999, residualGoldHandling: 'TAKE_AWAY', residualMaterialType: '足金999', residualGoldWeight: null, residualGoldFineness: 0.999, pickupDate: '', craftsmanId: null, remark: '' })
  orderDialog.value = true
}
async function saveOrder() {
  if (currentItem.value?.pricing_unit === '按克' && !(Number(orderForm.billingWeight) > 0)) return ElMessage.warning('请填写大于0的计费总克重')
  if (!orderForm.customerName.trim() || !orderForm.customerPhone.trim() || !orderForm.processingItemId || Number(orderForm.quantity) <= 0) return ElMessage.warning('请完整填写客户、加工项目和数量')
  if (orderForm.residualGoldHandling === 'STORE_DEDUCT' && (!(Number(orderForm.residualGoldWeight) > 0) || !(Number(orderForm.residualGoldFineness) > 0))) return ElMessage.warning('留店抵扣需填写旧料金重和成色')
  try {
    const result = await processingApi.createOrder({ ...orderForm, quantity: Number(orderForm.quantity), oldGoldWeight: orderForm.oldGoldWeight == null ? null : Number(orderForm.oldGoldWeight), oldGoldFineness: Number(orderForm.oldGoldFineness), residualGoldWeight: orderForm.residualGoldWeight == null ? null : Number(orderForm.residualGoldWeight), residualGoldFineness: Number(orderForm.residualGoldFineness) })
    orderDialog.value = false; ElMessage.success(`加工单 ${result.order_no} 已创建，应收 ${formatMoney(result.due_amount)}`)
    await loadOrders(); await showDetail(result)
  } catch (error) { ElMessage.error(error?.message || '加工开单失败') }
}
function openStoreGold() {
  const order = selectedOrder.value
  if (!order) return
  storeGoldForm.weight = Number(order.store_gold_weight) > 0 ? Number(order.store_gold_weight) : null
  storeGoldForm.fineness = Number(order.store_gold_fineness) > 0 ? Number(order.store_gold_fineness) : 0.999
  storeGoldForm.price = Number(order.store_gold_price) > 0 ? Number(order.store_gold_price) : 0
  storeGoldDialog.value = true
}
async function submitStoreGold() {
  if (!(Number(storeGoldForm.weight) > 0)) return ElMessage.warning('请填写大于 0 的补金克重')
  storeGoldSaving.value = true
  try {
    const result = await processingApi.storeGold(selectedOrder.value.processing_order_id, { weight: Number(storeGoldForm.weight), fineness: Number(storeGoldForm.fineness || 0.999), price: Number(storeGoldForm.price || 0) })
    storeGoldDialog.value = false
    selectedOrder.value = result
    ElMessage.success(`补金已登记，本单应收更新为 ${formatMoney(result.due_amount)}`)
    await loadOrders()
  } catch (error) { ElMessage.error(error?.message || '补金登记失败') } finally { storeGoldSaving.value = false }
}
function openWeigh() {
  const order = selectedOrder.value
  if (!order) return
  weighForm.finishedWeight = order.finished_weight != null ? Number(order.finished_weight) : null
  weighForm.finishedFineness = order.finished_fineness != null ? Number(order.finished_fineness) : null
  weighForm.recoveredWeight = order.recovered_weight != null ? Number(order.recovered_weight) : null
  weighForm.note = order.loss_note || ''
  weighDialog.value = true
}
async function submitWeigh() {
  if (!(Number(weighForm.finishedWeight) > 0)) return ElMessage.warning('成品实重必须大于 0')
  weighSaving.value = true
  try {
    const result = await processingApi.weighing(selectedOrder.value.processing_order_id, { finishedWeight: Number(weighForm.finishedWeight), finishedFineness: weighForm.finishedFineness != null ? Number(weighForm.finishedFineness) : null, recoveredWeight: weighForm.recoveredWeight != null && weighForm.recoveredWeight !== '' ? Number(weighForm.recoveredWeight) : null, note: weighForm.note || '' })
    weighDialog.value = false
    selectedOrder.value = result
    if (result?.loss_over) ElMessage.warning(`已登记：损耗 ${result.loss_weight}g（${result.loss_permille}‰）超过约定值，已标预警`)
    else ElMessage.success('称重损耗已登记')
    await loadOrders()
  } catch (error) { ElMessage.error(error?.message || '损耗登记失败') } finally { weighSaving.value = false }
}
async function showDetail(row) {
  try { selectedOrder.value = await processingApi.detail(row.processing_order_id); detailDialog.value = true } catch (error) { ElMessage.error(error?.message || '加载加工单详情失败') }
}
async function refreshDetail() {
  if (selectedOrder.value) selectedOrder.value = await processingApi.detail(selectedOrder.value.processing_order_id)
  await Promise.all([loadOrders(), loadDashboard(), loadCommissions()])
}
function openPayment() {
  const due = Number(selectedOrder.value?.due_amount || 0); const paid = Number(selectedOrder.value?.paid_amount || 0); const remaining = Math.max(0, due - paid)
  Object.assign(paymentForm, { paymentType: paid <= 0 ? 'DEPOSIT' : 'BALANCE', payMethod: paymentChannels.value[0]?.channel_code || '', amount: remaining, remark: '' })
  paymentDialog.value = true
}
async function submitPayment() {
  if (!(Number(paymentForm.amount) > 0)) return ElMessage.warning('收款金额必须大于0')
  if (!paymentForm.payMethod) return ElMessage.warning('当前没有可用的支付方式，请先在系统设置中启用')
  try {
    await processingApi.pay(selectedOrder.value.processing_order_id, { ...paymentForm, amount: Number(paymentForm.amount), clientRequestId: crypto.randomUUID?.() || `${Date.now()}-${Math.random()}` })
    paymentDialog.value = false; ElMessage.success('加工收款已登记'); await refreshDetail()
  } catch (error) { ElMessage.error(error?.message || '加工收款失败') }
}
function openAssign() { assignForm.craftsmanId = selectedOrder.value?.craftsman_id || null; assignDialog.value = true }
async function submitAssign() {
  try { await processingApi.assign(selectedOrder.value.processing_order_id, { craftsmanId: assignForm.craftsmanId }); assignDialog.value = false; ElMessage.success('加工师傅已分配'); await refreshDetail() } catch (error) { ElMessage.error(error?.message || '分配加工师傅失败') }
}
async function moveStatus() {
  const transitions = { PENDING: ['PROCESSING', '开始加工'], PROCESSING: ['COMPLETED', '确认加工完成'], COMPLETED: ['PICKED_UP', '确认客户取货'] }
  const [next, text] = transitions[selectedOrder.value?.status] || []
  if (!next) return
  try {
    await ElMessageBox.confirm(`确定${text}吗？`, '确认操作', { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' })
    await processingApi.updateStatus(selectedOrder.value.processing_order_id, { status: next }); ElMessage.success('订单状态已更新'); await refreshDetail()
  } catch (error) { if (!['cancel', 'close'].includes(error) && error?.message !== 'cancel') ElMessage.error(error?.message || '状态更新失败') }
}
async function generateCommissions() { try { const result = await processingApi.generateCommissions(); ElMessage.success(`已生成 ${result.generated || 0} 条加工提成`); await loadCommissions() } catch (error) { ElMessage.error(error?.message || '提成生成失败') } }
async function markCommissionPaid(row) {
  try { await ElMessageBox.confirm(`确认发放 ${row.employee_name || '该员工'} 的加工提成 ${formatMoney(row.commission_amount)} 吗？`, '确认发放', { type: 'warning' }); await processingApi.payCommission(row.commission_id); ElMessage.success('提成已标记为已发放'); await loadCommissions() } catch (error) { if (!['cancel', 'close'].includes(error) && error?.message !== 'cancel') ElMessage.error(error?.message || '提成发放失败') }
}

watch(() => route.fullPath, loadCurrent)
watch(()=>app.eventVersion,async()=>{if(['PROCESSING_ORDER_CREATED','PROCESSING_ORDER_UPDATED','PROCESSING_LOSS_OVER','PROCESSING_LOSS_CONFIG_UPDATED','COMMISSION_UPDATED','MEMBER_UPDATED'].includes(app.lastEventType))await loadCurrent();if(['PROCESSING_CATALOG_UPDATED','STAFF_UPDATED'].includes(app.lastEventType)){await loadShared();await loadCurrent()}})
onMounted(loadCurrent)
</script>

<template>
  <section v-loading="loading" class="processing-page">
    <template v-if="tab === 'dashboard'">
      <div class="page-toolbar"><div><span class="muted">按加工单创建时间统计</span></div><div class="inline-actions"><el-date-picker v-model="dashboardFilters.dates" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期"/><el-select v-model="dashboardFilters.craftsmanId" clearable placeholder="全部师傅" style="width:150px"><el-option v-for="staff in craftsmen" :key="staff.user_id" :label="staff.real_name" :value="staff.user_id"/></el-select><el-button @click="loadDashboard">查询</el-button></div></div>
      <div class="stat-grid"><div class="stat-card"><span>加工单数</span><strong>{{ statistics.order_count || 0 }}</strong></div><div class="stat-card"><span>工费应收</span><strong>{{ formatMoney(statistics.due_amount) }}</strong></div><div class="stat-card"><span>实际收款</span><strong>{{ formatMoney(statistics.paid_amount) }}</strong></div><div class="stat-card"><span>待收尾款</span><strong>{{ formatMoney(statistics.outstanding) }}</strong></div><div class="stat-card"><span>加工中</span><strong>{{ statistics.processing_count || 0 }}</strong></div><div class="stat-card"><span>提成支出</span><strong>{{ formatMoney(statistics.commission_expense) }}</strong></div></div>
      <section class="panel"><div class="panel-title">加工项目排行 <el-button link type="primary" @click="go('orders')">查看订单</el-button></div><el-table :data="statistics.item_ranking || []"><el-table-column prop="item_name" label="加工项目"/><el-table-column prop="order_count" label="订单数"/><el-table-column label="工费应收" align="right"><template #default="s">{{ formatMoney(s.row.labor_fee) }}</template></el-table-column><el-table-column label="实际收款" align="right"><template #default="s">{{ formatMoney(s.row.paid_amount) }}</template></el-table-column></el-table></section>
    </template>

    <template v-else-if="tab === 'orders'">
      <div class="page-toolbar"><div class="inline-actions"><el-input v-model="orderFilters.keyword" clearable placeholder="订单号 / 客户 / 手机" style="width:210px" @keyup.enter="loadOrders"/><el-select v-model="orderFilters.status" clearable placeholder="全部状态" style="width:130px"><el-option label="待加工" value="PENDING"/><el-option label="加工中" value="PROCESSING"/><el-option label="已完成待取货" value="COMPLETED"/><el-option label="已取货" value="PICKED_UP"/></el-select><el-select v-model="orderFilters.craftsmanId" clearable placeholder="全部师傅" style="width:135px"><el-option v-for="staff in craftsmen" :key="staff.user_id" :label="staff.real_name" :value="staff.user_id"/></el-select><el-date-picker v-model="orderFilters.dates" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始" end-placeholder="结束"/><el-button @click="loadOrders">查询</el-button></div><el-button type="primary" @click="openOrder">新增加工单</el-button></div>
      <section class="panel"><el-table :data="orders" row-key="processing_order_id"><el-table-column prop="order_no" label="加工单号" min-width="178"/><el-table-column label="客户" min-width="125"><template #default="s"><div>{{ s.row.customer_name }}</div><small class="muted">{{ s.row.customer_phone }}</small></template></el-table-column><el-table-column prop="item_name_snapshot" label="加工项目"/><el-table-column label="原应收" align="right"><template #default="s">{{ formatMoney(s.row.original_due_amount ?? s.row.due_amount) }}</template></el-table-column><el-table-column label="团购优惠" align="right"><template #default="s">{{ formatMoney(s.row.promotion_discount) }}</template></el-table-column><el-table-column label="优惠后应收" align="right"><template #default="s">{{ formatMoney(s.row.due_amount) }}</template></el-table-column><el-table-column label="已收" align="right"><template #default="s">{{ formatMoney(s.row.paid_amount) }}</template></el-table-column><el-table-column prop="craftsman_name" label="加工师傅"/><el-table-column label="状态" width="120"><template #default="s"><el-tag :type="orderStatus(s.row.status)[1]">{{ orderStatus(s.row.status)[0] }}</el-tag></template></el-table-column><el-table-column label="预计取货" width="115"><template #default="s">{{ s.row.pickup_date || '-' }}</template></el-table-column><el-table-column label="创建时间" min-width="168"><template #default="s">{{ formatTime(s.row.create_time) }}</template></el-table-column><el-table-column label="操作" width="80" fixed="right"><template #default="s"><el-button link type="primary" @click="showDetail(s.row)">详情</el-button></template></el-table-column></el-table></section>
    </template>

    <template v-else-if="tab === 'items'">
      <div class="page-toolbar"><span class="muted">加工项目的价格、周期与师傅提成比例在这里统一维护</span><div class="inline-actions"><el-button @click="openCategoryManager()">管理分类</el-button><el-button type="primary" @click="openItem()">新增项目</el-button></div></div>
      <section class="panel"><el-table :data="items"><el-table-column prop="name" label="项目名称"/><el-table-column prop="item_code" label="项目编码"/><el-table-column prop="category_name" label="加工分类"/><el-table-column label="工费" align="right"><template #default="s">{{ formatMoney(s.row.labor_fee) }}</template></el-table-column><el-table-column label="计价方式" width="90"><template #default="s">{{ s.row.pricing_unit || '按件' }}</template></el-table-column><el-table-column label="预计时长" width="110"><template #default="s">{{ s.row.duration_text || `${s.row.processing_days} 天` }}</template></el-table-column><el-table-column label="工序" min-width="150"><template #default="s"><span class="muted">{{ s.row.process_steps || '-' }}</span></template></el-table-column><el-table-column label="加工周期" width="100"><template #default="s">{{ s.row.processing_days }} 天</template></el-table-column><el-table-column label="提成比例" width="100"><template #default="s">{{ Number(s.row.commission_rate || 0).toFixed(2) }}%</template></el-table-column><el-table-column label="状态" width="85"><template #default="s"><el-tag :type="Number(s.row.status) === 1 ? 'success' : 'info'">{{ Number(s.row.status) === 1 ? '启用' : '禁用' }}</el-tag></template></el-table-column><el-table-column label="操作" width="210"><template #default="s"><el-button link type="primary" @click="openItem(s.row)">编辑</el-button><el-button link :type="Number(s.row.status) === 1 ? 'warning' : 'success'" @click="toggleItem(s.row)">{{ Number(s.row.status) === 1 ? '禁用' : '启用' }}</el-button><el-button link type="danger" @click="deleteItem(s.row)">删除</el-button></template></el-table-column></el-table></section>
    </template>

    <template v-else-if="tab === 'loss'">
      <div class="page-toolbar"><span class="muted">按加工单称重核算损耗（来料折重＋补金−成品折重−回收屑），千分比超过约定值自动标红预警，可按师傅考核。</span><div class="inline-actions">
        <el-date-picker v-model="lossFrom" type="date" value-format="YYYY-MM-DD" placeholder="开始日期" style="width:130px"/><el-date-picker v-model="lossTo" type="date" value-format="YYYY-MM-DD" placeholder="结束日期" style="width:130px"/>
        <el-button @click="loadLoss">查询</el-button>
        <span class="muted">约定损耗</span><el-input-number v-model="lossPermille" :min="0" :max="100" :precision="2" size="small" style="width:110px"/><span class="muted">‰</span>
        <el-button type="primary" :loading="lossSaving" @click="saveLossConfig">保存约定</el-button>
      </div></div>
      <el-alert v-if="lossAnomalyCount > 0" type="warning" :closable="false" show-icon style="margin-bottom:12px"><template #title>发现 {{ lossAnomalyCount }} 条历史负损耗记录，已从正常考核汇总排除。<el-button link type="warning" @click="openLossDetails(null, true)">查看异常</el-button></template></el-alert>
      <section class="panel"><el-table :data="lossRows" stripe><el-table-column prop="name" label="加工师傅"/><el-table-column label="有效称重" width="100" align="right"><template #default="s">{{ s.row.weighed_count }}</template></el-table-column><el-table-column label="总损耗" align="right"><template #default="s">{{ Number(s.row.loss_weight).toFixed(3) }} g</template></el-table-column><el-table-column label="超标次数" width="100" align="right"><template #default="s"><span :class="Number(s.row.over_count) > 0 ? 'loss-over' : ''">{{ s.row.over_count }}</span></template></el-table-column><el-table-column label="历史异常" width="100" align="right"><template #default="s"><span :class="Number(s.row.anomaly_count) > 0 ? 'loss-over' : ''">{{ s.row.anomaly_count || 0 }}</span></template></el-table-column><el-table-column label="平均损耗（g/单）" width="130" align="right"><template #default="s">{{ s.row.weighed_count ? (Number(s.row.loss_weight) / Number(s.row.weighed_count)).toFixed(3) : '0.000' }}</template></el-table-column><el-table-column label="操作" width="90"><template #default="s"><el-button link type="primary" @click="openLossDetails(s.row)">查看明细</el-button></template></el-table-column></el-table><p class="muted" style="font-size:12px;margin:8px 0 0">当前约定损耗：{{ lossPermille }}‰。单笔损耗 = 来料折重 + 店供补金 − 成品折重 − 回收屑；每千来料损耗超过约定值即预警。历史负数记录作为异常待复核，不计入正常汇总。</p></section>
    </template>
    <template v-else-if="tab === 'commissions'">
      <div class="page-toolbar"><span class="muted">加工单完成后按项目快照比例生成提成，发放后保留发放记录。</span><div class="inline-actions"><el-button @click="loadCommissions">刷新</el-button><el-button type="primary" @click="generateCommissions">生成待发提成</el-button></div></div>
      <div class="stat-grid compact"><div class="stat-card"><span>提成总额</span><strong>{{ formatMoney(commissionSummary.total) }}</strong></div><div class="stat-card"><span>待发放</span><strong>{{ formatMoney(commissionSummary.pending) }}</strong></div><div class="stat-card"><span>已发放</span><strong>{{ formatMoney(commissionSummary.paid) }}</strong></div></div>
      <section class="panel"><el-table :data="commissions"><el-table-column prop="order_no" label="加工单号"/><el-table-column prop="item_name_snapshot" label="加工项目"/><el-table-column prop="employee_name" label="加工师傅"/><el-table-column label="计提基数" align="right"><template #default="s">{{ formatMoney(s.row.commission_base) }}</template></el-table-column><el-table-column label="提成比例"><template #default="s">{{ Number(s.row.commission_rate || 0).toFixed(2) }}%</template></el-table-column><el-table-column label="提成金额" align="right"><template #default="s">{{ formatMoney(s.row.commission_amount) }}</template></el-table-column><el-table-column label="状态"><template #default="s"><el-tag :type="s.row.status === 'PAID' ? 'success' : 'warning'">{{ s.row.status === 'PAID' ? '已发放' : '待发放' }}</el-tag></template></el-table-column><el-table-column label="发放时间" min-width="170"><template #default="s">{{ formatTime(s.row.paid_at) }}</template></el-table-column><el-table-column label="操作" width="90"><template #default="s"><el-button v-if="s.row.status === 'PENDING'" link type="primary" @click="markCommissionPaid(s.row)">确认发放</el-button></template></el-table-column></el-table></section>
    </template>
  </section>

  <el-dialog v-model="lossDetailDialog" :title="`损耗明细 · ${lossDetailFilter.anomalies ? '历史异常' : lossDetailFilter.name}`" width="1120px" top="6vh">
    <div class="page-toolbar" style="margin-bottom:12px"><span class="muted">负损耗为历史异常，只供复核，不计入正常考核。</span><div class="inline-actions"><el-checkbox v-model="lossDetailFilter.onlyOver" :disabled="lossDetailFilter.anomalies" @change="loadLossDetails">只看超标</el-checkbox><el-checkbox v-model="lossDetailFilter.anomalies" @change="lossDetailFilter.onlyOver=false; loadLossDetails()">只看异常</el-checkbox><el-button @click="loadLossDetails">刷新</el-button></div></div>
    <el-table v-loading="lossDetailLoading" :data="lossDetailRows" max-height="560" stripe><el-table-column prop="order_no" label="加工单号" min-width="170" fixed/><el-table-column prop="craftsman_name" label="师傅" width="100"/><el-table-column label="来料折重" width="105" align="right"><template #default="s">{{ Number(s.row.incoming_net_weight || 0).toFixed(3) }} g</template></el-table-column><el-table-column label="店供补金" width="105" align="right"><template #default="s">{{ Number(s.row.store_gold_weight || 0).toFixed(3) }} g</template></el-table-column><el-table-column label="成品实重" width="105" align="right"><template #default="s">{{ Number(s.row.finished_weight || 0).toFixed(3) }} g</template></el-table-column><el-table-column label="回收屑" width="95" align="right"><template #default="s">{{ Number(s.row.recovered_weight || 0).toFixed(3) }} g</template></el-table-column><el-table-column label="损耗" width="105" align="right"><template #default="s"><span :class="Number(s.row.loss_over) === 1 || Number(s.row.loss_weight) < 0 ? 'loss-over' : ''">{{ Number(s.row.loss_weight || 0).toFixed(3) }} g</span></template></el-table-column><el-table-column label="损耗率" width="90" align="right"><template #default="s">{{ s.row.loss_permille == null ? '-' : Number(s.row.loss_permille).toFixed(2) + '‰' }}</template></el-table-column><el-table-column label="状态" width="90"><template #default="s"><el-tag v-if="Number(s.row.loss_weight) < 0" type="danger">异常</el-tag><el-tag v-else-if="Number(s.row.loss_over) === 1" type="warning">超标</el-tag><el-tag v-else type="success">正常</el-tag></template></el-table-column><el-table-column prop="loss_note" label="备注" min-width="140" show-overflow-tooltip/><el-table-column label="登记时间" min-width="165"><template #default="s">{{ formatTime(s.row.loss_time) }}</template></el-table-column></el-table>
    <template #footer><el-button @click="lossDetailDialog=false">关闭</el-button></template>
  </el-dialog>
  <el-dialog v-model="categoryDialog" title="加工分类管理" width="760px"><div class="page-toolbar"><span class="muted">分类用于组织加工项目；已有项目的分类仅支持禁用。</span><el-button type="primary" @click="openCategoryForm()">新增分类</el-button></div><el-table :data="categories"><el-table-column prop="name" label="分类名称"/><el-table-column prop="category_code" label="分类编码"/><el-table-column prop="sort" label="排序" width="90"/><el-table-column label="状态" width="90"><template #default="s"><el-tag :type="Number(s.row.status) === 1 ? 'success' : 'info'">{{ Number(s.row.status) === 1 ? '启用' : '禁用' }}</el-tag></template></el-table-column><el-table-column label="创建时间" min-width="160"><template #default="s">{{ formatTime(s.row.create_time) }}</template></el-table-column><el-table-column label="操作" width="200"><template #default="s"><el-button link type="primary" @click="openCategoryForm(s.row)">编辑</el-button><el-button link :type="Number(s.row.status) === 1 ? 'warning' : 'success'" @click="toggleCategory(s.row)">{{ Number(s.row.status) === 1 ? '禁用' : '启用' }}</el-button><el-button link type="danger" @click="deleteCategory(s.row)">删除</el-button></template></el-table-column></el-table><template #footer><el-button @click="categoryDialog=false">关闭</el-button></template></el-dialog>
  <el-dialog v-model="categoryFormDialog" :title="categoryEditing ? '编辑加工分类' : '新增加工分类'" width="480px" append-to-body><el-form label-width="90px"><el-form-item label="分类名称" required><el-input v-model="categoryForm.name" maxlength="64"/></el-form-item><el-form-item label="分类编码" required><el-input v-model="categoryForm.categoryCode" maxlength="32" placeholder="如 JEWELRY"/></el-form-item><el-form-item label="排序"><el-input-number v-model="categoryForm.sort" :min="0" :precision="0"/></el-form-item><el-form-item label="状态"><el-switch v-model="categoryForm.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="禁用"/></el-form-item></el-form><template #footer><el-button @click="categoryFormDialog=false">取消</el-button><el-button type="primary" @click="saveCategory">保存</el-button></template></el-dialog>
  <el-dialog v-model="itemDialog" :title="itemEditing ? '编辑加工项目' : '新增加工项目'" width="560px"><el-form label-width="100px"><el-form-item label="加工分类" required><div class="category-field"><el-select v-model="itemForm.categoryId" filterable style="flex:1"><el-option v-for="category in categories.filter(row => Number(row.status) === 1)" :key="category.category_id" :label="category.name" :value="category.category_id"/></el-select><el-button link type="primary" @click="openCategoryManager()">管理分类</el-button></div></el-form-item><el-form-item label="项目名称" required><el-input v-model="itemForm.name" maxlength="100"/></el-form-item><el-form-item label="项目编码" required><el-input v-model="itemForm.itemCode" maxlength="32"/></el-form-item><el-form-item label="工费"><el-input-number v-model="itemForm.laborFee" :min="0" :precision="2"/></el-form-item><el-form-item label="计价方式"><el-select v-model="itemForm.pricingUnit" style="width:120px"><el-option label="按件" value="按件"/><el-option label="按克" value="按克"/></el-select><span class="form-suffix">按克时工费单位为 元/克</span></el-form-item><el-form-item label="加工周期"><el-input-number v-model="itemForm.processingDays" :min="0" :precision="0"/><span class="form-suffix">天</span></el-form-item><el-form-item label="预计时长"><el-input v-model="itemForm.durationText" maxlength="30" placeholder="如 30分钟 / 2小时 / 1天"/></el-form-item><el-form-item label="工序"><el-input v-model="itemForm.processSteps" maxlength="200" placeholder="如 穿绳 / 打结 / 检查"/></el-form-item><el-form-item label="提成比例"><el-input-number v-model="itemForm.commissionRate" :min="0" :max="100" :precision="2"/><span class="form-suffix">%</span></el-form-item><el-form-item label="备注"><el-input v-model="itemForm.remark" type="textarea"/></el-form-item><el-form-item label="状态"><el-switch v-model="itemForm.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="禁用"/></el-form-item></el-form><template #footer><el-button @click="itemDialog=false">取消</el-button><el-button type="primary" @click="saveItem">保存</el-button></template></el-dialog>
  <el-dialog v-model="orderDialog" title="新增加工单" width="660px"><el-form label-width="112px"><el-form-item label="关联会员"><el-select v-model="orderForm.memberId" clearable filterable placeholder="可选" style="width:100%" @change="selectMember"><el-option v-for="member in members" :key="member.member_id" :label="`${member.name} / ${member.phone}`" :value="member.member_id"/></el-select></el-form-item><div class="form-grid"><el-form-item label="客户姓名" required><el-input v-model="orderForm.customerName"/></el-form-item><el-form-item label="客户电话" required><el-input v-model="orderForm.customerPhone"/></el-form-item><el-form-item label="加工项目" required><el-select v-model="orderForm.processingItemId" filterable><el-option v-for="item in items.filter(row => Number(row.status) === 1)" :key="item.item_id" :label="`${item.name}（工费 ${formatMoney(item.labor_fee)}）`" :value="item.item_id"/></el-select></el-form-item><el-form-item label="数量" required><el-input-number v-model="orderForm.quantity" :min="1" :precision="0"/></el-form-item><el-form-item v-if="currentItem?.pricing_unit === '按克'" label="计费总克重(g)" required><el-input-number v-model="orderForm.billingWeight" :min="0.001" :precision="3"/></el-form-item><el-form-item label="加工师傅"><el-select v-model="orderForm.craftsmanId" clearable filterable><el-option v-for="staff in craftsmen" :key="staff.user_id" :label="staff.real_name" :value="staff.user_id"/></el-select></el-form-item><el-form-item label="预计取货"><el-date-picker v-model="orderForm.pickupDate" type="date" value-format="YYYY-MM-DD"/></el-form-item></div><el-alert :closable="false" type="info" :title="`项目工费参考：${formatMoney(Number(currentItem?.labor_fee || 0) * Number((currentItem?.pricing_unit === '按克' ? orderForm.billingWeight : orderForm.quantity) || 0))}。开单仅生成应收，定金和尾款在订单详情中分笔收款。`"/><el-divider>客户旧金</el-divider><div class="form-grid"><el-form-item label="来料克重"><el-input-number v-model="orderForm.oldGoldWeight" :min="0" :precision="3"/></el-form-item><el-form-item label="来料成色"><el-input-number v-model="orderForm.oldGoldFineness" :min="0" :max="1" :precision="4"/></el-form-item><el-form-item label="剩余旧料处理"><el-radio-group v-model="orderForm.residualGoldHandling"><el-radio value="TAKE_AWAY">客户带走</el-radio><el-radio value="STORE_DEDUCT">留店抵扣工费</el-radio></el-radio-group></el-form-item></div><el-alert :closable="false" type="info" :title="`店供金料请在订单确认加工后通过详情页「补金登记」按成品称重录入，金额自动并入应收并扣减金料库存。`"/><div v-if="orderForm.residualGoldHandling === 'STORE_DEDUCT'" class="form-grid"><el-form-item label="旧料类型" required><el-select v-model="orderForm.residualMaterialType"><el-option v-for="type in materialTypes" :key="type" :label="type" :value="type"/></el-select></el-form-item><el-form-item label="剩余克重" required><el-input-number v-model="orderForm.residualGoldWeight" :min="0" :precision="3"/></el-form-item><el-form-item label="剩余成色" required><el-input-number v-model="orderForm.residualGoldFineness" :min="0" :max="1" :precision="4"/></el-form-item></div><el-form-item label="备注"><el-input v-model="orderForm.remark" type="textarea"/></el-form-item></el-form><template #footer><el-button @click="orderDialog=false">取消</el-button><el-button type="primary" @click="saveOrder">创建加工单</el-button></template></el-dialog>
  <el-dialog v-model="detailDialog" title="加工订单详情" width="760px" top="7vh"><template v-if="selectedOrder"><div class="order-head"><div><b>{{ selectedOrder.order_no }}</b><p>{{ selectedOrder.customer_name }} · {{ selectedOrder.customer_phone }}</p></div><el-tag :type="orderStatus(selectedOrder.status)[1]">{{ orderStatus(selectedOrder.status)[0] }}</el-tag></div><el-descriptions :column="3" border><el-descriptions-item label="加工项目">{{ selectedOrder.item_name_snapshot }} × {{ selectedOrder.quantity }}</el-descriptions-item><el-descriptions-item label="加工师傅">{{ selectedOrder.craftsman_name || '未分配' }}</el-descriptions-item><el-descriptions-item label="预计取货">{{ selectedOrder.pickup_date || '-' }}</el-descriptions-item><el-descriptions-item label="工费">{{ formatMoney(selectedOrder.labor_fee) }}</el-descriptions-item><el-descriptions-item label="旧料抵扣">{{ formatMoney(selectedOrder.residual_gold_deduction) }}</el-descriptions-item><el-descriptions-item v-if="Number(selectedOrder.store_gold_weight) > 0" label="店供金料">{{ Number(selectedOrder.store_gold_weight).toFixed(3) }}g · {{ Number(selectedOrder.store_gold_price).toFixed(2) }}/g</el-descriptions-item><el-descriptions-item v-if="Number(selectedOrder.store_gold_weight) > 0" label="补金金额">{{ formatMoney(selectedOrder.store_gold_amount) }}</el-descriptions-item><el-descriptions-item label="原应收">{{ formatMoney(selectedOrder.original_due_amount ?? selectedOrder.due_amount) }}</el-descriptions-item><el-descriptions-item v-if="selectedOrder.promotion_channel" label="团购平台">{{ formatPaymentMethod(selectedOrder.promotion_channel) }}</el-descriptions-item><el-descriptions-item v-if="selectedOrder.promotion_channel" label="团购优惠">{{ formatMoney(selectedOrder.promotion_discount) }}</el-descriptions-item><el-descriptions-item v-if="selectedOrder.voucher_no" label="核销单号">{{ selectedOrder.voucher_no }}</el-descriptions-item><el-descriptions-item label="优惠后应收">{{ formatMoney(selectedOrder.due_amount) }}</el-descriptions-item><el-descriptions-item label="已收">{{ formatMoney(selectedOrder.paid_amount) }}</el-descriptions-item><el-descriptions-item label="待收">{{ formatMoney(Number(selectedOrder.due_amount || 0) - Number(selectedOrder.paid_amount || 0)) }}</el-descriptions-item><el-descriptions-item label="剩余旧料">{{ handlingText(selectedOrder.residual_gold_handling) }}</el-descriptions-item><el-descriptions-item v-if="selectedOrder.finished_weight" label="成品实重">{{ Number(selectedOrder.finished_weight).toFixed(3) }}g</el-descriptions-item><el-descriptions-item v-if="selectedOrder.recovered_weight" label="回收屑">{{ Number(selectedOrder.recovered_weight).toFixed(3) }}g</el-descriptions-item><el-descriptions-item v-if="selectedOrder.loss_weight != null" label="损耗"><span :class="{ 'loss-over': Number(selectedOrder.loss_over) === 1 }">{{ Number(selectedOrder.loss_weight).toFixed(3) }}g{{ selectedOrder.loss_permille != null ? '（' + Number(selectedOrder.loss_permille) + '‰）' : '' }}{{ Number(selectedOrder.loss_over) === 1 ? ' 超预警' : '' }}</span></el-descriptions-item><el-descriptions-item label="创建时间">{{ formatTime(selectedOrder.create_time) }}</el-descriptions-item><el-descriptions-item v-if="selectedOrder.handover_time" label="转交前台">{{ formatTime(selectedOrder.handover_time) }}</el-descriptions-item><el-descriptions-item v-if="selectedOrder.completed_time" label="完成加工">{{ formatTime(selectedOrder.completed_time) }}</el-descriptions-item><el-descriptions-item v-if="selectedOrder.picked_up_time" label="取货时间">{{ formatTime(selectedOrder.picked_up_time) }}</el-descriptions-item></el-descriptions><div v-if="parsePhotos(selectedOrder.incoming_photos).length || parsePhotos(selectedOrder.weigh_photos).length || parsePhotos(selectedOrder.pickup_photos).length"><el-divider>照片存档</el-divider><div v-if="parsePhotos(selectedOrder.incoming_photos).length" class="photo-group"><span class="photo-label">来料照片</span><div class="photo-list"><el-image v-for="(url, i) in parsePhotos(selectedOrder.incoming_photos)" :key="'in' + i" :src="url" :preview-src-list="parsePhotos(selectedOrder.incoming_photos)" :initial-index="i" fit="cover" class="photo-thumb" preview-teleported/></div></div><div v-if="parsePhotos(selectedOrder.weigh_photos).length" class="photo-group"><span class="photo-label">称重照片</span><div class="photo-list"><el-image v-for="(url, i) in parsePhotos(selectedOrder.weigh_photos)" :key="'wg' + i" :src="url" :preview-src-list="parsePhotos(selectedOrder.weigh_photos)" :initial-index="i" fit="cover" class="photo-thumb" preview-teleported/></div></div><div v-if="parsePhotos(selectedOrder.pickup_photos).length" class="photo-group"><span class="photo-label">取货照片</span><div class="photo-list"><el-image v-for="(url, i) in parsePhotos(selectedOrder.pickup_photos)" :key="'pk' + i" :src="url" :preview-src-list="parsePhotos(selectedOrder.pickup_photos)" :initial-index="i" fit="cover" class="photo-thumb" preview-teleported/></div></div></div><el-divider>收款记录</el-divider><el-table :data="selectedOrder.payments || []" size="small"><el-table-column label="类型"><template #default="s">{{ paymentType(s.row.payment_type) }}</template></el-table-column><el-table-column label="金额" align="right"><template #default="s">{{ formatMoney(s.row.amount) }}</template></el-table-column><el-table-column label="支付方式"><template #default="s">{{ formatPaymentMethod(s.row.pay_method) }}</template></el-table-column><el-table-column label="收款时间"><template #default="s">{{ formatTime(s.row.create_time) }}</template></el-table-column></el-table><el-divider>加工提成</el-divider><el-table :data="selectedOrder.commissions || []" size="small"><el-table-column prop="employee_name" label="加工师傅"/><el-table-column label="提成金额" align="right"><template #default="s">{{ formatMoney(s.row.commission_amount) }}</template></el-table-column><el-table-column label="状态"><template #default="s">{{ s.row.status === 'PAID' ? '已发放' : '待发放' }}</template></el-table-column></el-table><p v-if="selectedOrder.remark" class="muted">备注：{{ selectedOrder.remark }}</p></template><template #footer><el-button @click="detailDialog=false">关闭</el-button><el-button @click="openAssign">分配师傅</el-button><el-button v-if="['PROCESSING', 'COMPLETED'].includes(selectedOrder?.status)" @click="openStoreGold">补金登记</el-button><el-button v-if="['PROCESSING', 'COMPLETED'].includes(selectedOrder?.status)" @click="openWeigh">损耗登记</el-button><el-button type="primary" :disabled="Number(selectedOrder?.paid_amount || 0) >= Number(selectedOrder?.due_amount || 0)" @click="openPayment">登记收款</el-button><el-button v-if="selectedOrder?.status !== 'PICKED_UP'" type="success" @click="moveStatus">{{ selectedOrder?.status === 'PENDING' ? '开始加工' : selectedOrder?.status === 'PROCESSING' ? '确认完成' : '确认取货' }}</el-button></template></el-dialog>
  <el-dialog v-model="storeGoldDialog" title="补金登记（成品反推）" width="430px"><el-form label-width="90px"><p class="muted" style="margin:0 0 10px;font-size:12px">按成品称重登记本单实际使用的店供金料；金额并入本单应收，库存按差额自动退补；可重复登记修正。</p><el-form-item label="补金克重" required><el-input-number v-model="storeGoldForm.weight" :min="0.001" :precision="3" style="width:100%"/></el-form-item><el-form-item label="补金成色"><el-input-number v-model="storeGoldForm.fineness" :min="0" :max="1" :precision="4"/></el-form-item><el-form-item label="计价金价"><el-input-number v-model="storeGoldForm.price" :min="0" :precision="2"/><span class="form-suffix">留 0 取当日足金价</span></el-form-item></el-form><template #footer><el-button @click="storeGoldDialog=false">取消</el-button><el-button type="primary" :loading="storeGoldSaving" @click="submitStoreGold">确认登记</el-button></template></el-dialog>
  <el-dialog v-model="weighDialog" title="称重损耗登记" width="430px"><el-form label-width="90px"><p class="muted" style="margin:0 0 10px;font-size:12px">损耗 = 来料折重 + 补金 − 成品折重 − 回收屑；超约定值自动标红预警。可重复登记修正。</p><el-form-item label="成品实重" required><el-input-number v-model="weighForm.finishedWeight" :min="0.001" :precision="3" style="width:100%"/></el-form-item><el-form-item label="成品成色"><el-input-number v-model="weighForm.finishedFineness" :min="0" :max="1" :precision="4"/></el-form-item><el-form-item label="回收屑"><el-input-number v-model="weighForm.recoveredWeight" :min="0" :precision="3"/></el-form-item><el-form-item label="备注"><el-input v-model="weighForm.note" type="textarea" rows="2"/></el-form-item></el-form><template #footer><el-button @click="weighDialog=false">取消</el-button><el-button type="primary" :loading="weighSaving" @click="submitWeigh">确认登记</el-button></template></el-dialog>
  <el-dialog v-model="paymentDialog" title="登记加工收款" width="430px"><el-form label-width="90px"><el-form-item label="收款类型"><el-radio-group v-model="paymentForm.paymentType"><el-radio value="DEPOSIT">定金</el-radio><el-radio value="BALANCE">尾款</el-radio></el-radio-group></el-form-item><el-form-item label="支付方式"><el-select v-model="paymentForm.payMethod" placeholder="暂无可用支付方式"><el-option v-for="channel in paymentChannels" :key="channel.channel_id" :label="channel.channel_name" :value="channel.channel_code"/></el-select></el-form-item><el-form-item label="收款金额"><el-input-number v-model="paymentForm.amount" :min="0.01" :precision="2"/></el-form-item><el-form-item label="备注"><el-input v-model="paymentForm.remark"/></el-form-item></el-form><template #footer><el-button @click="paymentDialog=false">取消</el-button><el-button type="primary" :disabled="!paymentForm.payMethod" @click="submitPayment">确认收款</el-button></template></el-dialog>
  <el-dialog v-model="assignDialog" title="分配加工师傅" width="400px"><el-form label-width="90px"><el-form-item label="加工师傅"><el-select v-model="assignForm.craftsmanId" clearable filterable style="width:100%"><el-option v-for="staff in craftsmen" :key="staff.user_id" :label="staff.real_name" :value="staff.user_id"/></el-select></el-form-item></el-form><template #footer><el-button @click="assignDialog=false">取消</el-button><el-button type="primary" @click="submitAssign">保存</el-button></template></el-dialog>
</template>

<style scoped>
.page-toolbar,.inline-actions,.order-head { display:flex; align-items:center; justify-content:space-between; gap:10px; flex-wrap:wrap; }
.stat-grid { display:grid; grid-template-columns:repeat(6,minmax(130px,1fr)); gap:12px; margin-bottom:16px; }
.stat-grid.compact { grid-template-columns:repeat(3,minmax(160px,1fr)); }
.stat-card { border:1px solid var(--el-border-color-lighter); background:#fffdf8; padding:15px; border-radius:6px; display:grid; gap:8px; }
.stat-card span,.muted { color:var(--el-text-color-secondary); font-size:13px; }
.stat-card strong { color:#9d6726; font-size:20px; }
.form-grid { display:grid; grid-template-columns:1fr 1fr; column-gap:14px; }
.category-field { display:flex; align-items:center; gap:10px; width:100%; }
.form-suffix { margin-left:8px; color:var(--el-text-color-secondary); }
.order-head { margin-bottom:14px; }.order-head p { margin:5px 0 0; color:var(--el-text-color-secondary); }
.photo-group { margin-bottom:10px; }
.photo-label { display:block; font-size:13px; color:var(--el-text-color-secondary); margin-bottom:6px; }
.photo-list { display:flex; gap:8px; flex-wrap:wrap; }
.photo-thumb { width:72px; height:72px; border-radius:6px; border:1px solid var(--el-border-color-lighter); cursor:zoom-in; }
@media (max-width: 1100px) { .stat-grid { grid-template-columns:repeat(3,minmax(130px,1fr)); } }
@media (max-width: 720px) { .stat-grid,.stat-grid.compact,.form-grid { grid-template-columns:1fr; } }
.loss-over { color:#c0392b; font-weight:700; }
</style>
