<template>
  <div class="shell">
    <header class="topbar"><button class="back" @click="router.back()">‹</button><strong>入库准备</strong><button class="outline" @click="router.push('/inbound/history')">最近记录</button></header>
    <main class="content page inbound-page">
      <div class="form-card">
        <h3>入库类型</h3>
        <div class="filter-tabs inbound-types"><button v-for="type in types" :key="type.value" :class="{ active: form.inboundType === type.value }" @click="selectType(type.value)">{{ type.label }}</button></div>
        <label v-if="form.inboundType === 'purchase'" class="form-label">供应商
          <select v-model="form.sourceId"><option value="" disabled>请选择供应商</option><option v-for="supplier in suppliers" :key="supplier.supplier_id" :value="supplier.supplier_id">{{ supplier.supplier_name }}（{{ supplier.supplier_code }}）</option></select>
          <span v-if="creatingSupplier" class="supplier-create"><input v-model.trim="newSupplierName" placeholder="输入新供应商名称"/><button class="outline" :disabled="!newSupplierName || supplierSaving" @click="saveNewSupplier">{{ supplierSaving ? '保存中...' : '保存' }}</button><button class="outline" @click="creatingSupplier = false; newSupplierName = ''">取消</button></span>
          <button v-else class="outline supplier-add" type="button" @click="creatingSupplier = true">＋ 新增供应商</button>
        </label>
        <label v-if="form.inboundType === 'transfer'" class="form-label">调出门店
          <select v-model="form.sourceId"><option value="" disabled>请选择调出门店</option><option v-for="store in transferStores" :key="store.store_id" :value="store.store_id">{{ store.store_name }}</option></select>
        </label>
        <label class="form-label">入库门店
          <select v-model="form.storeId" :disabled="!canSwitchStore || !stores.length" @change="handleStoreChange"><option v-for="store in inboundStores" :key="store.store_id" :value="store.store_id">{{ store.store_name }}</option></select>
        </label>
        <small v-if="!canSwitchStore" class="muted">当前账号仅能向所属门店入库</small>
        <label class="form-label">备注（可选）<textarea v-model.trim="form.remark" rows="3" placeholder="填写入库说明"/></label>
        <p v-if="error" class="error">{{ error }}</p>
        <button v-permission="'stock:inbound:create'" class="primary full" :disabled="loading" @click="start">{{ loading ? '加载中...' : '开始扫码' }} <span>›</span></button>
      </div>
    </main>
  </div>
</template>
<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth.js'
import { api } from '../api/request.js'
import { scopedStorage } from '../utils/storage.js'
const pageCache = scopedStorage(), setStorage = pageCache.set
const router = useRouter(), auth = useAuthStore(), stores = ref([]), suppliers = ref([]), loading = ref(true), error = ref('')
const creatingSupplier = ref(false); const newSupplierName = ref(''); const supplierSaving = ref(false)
async function saveNewSupplier() {
  if (!newSupplierName.value.trim()) return
  supplierSaving.value = true
  try {
    const rows = await api.supplierCreate({ supplierName: newSupplierName.value.trim() })
    suppliers.value = Array.isArray(rows) ? rows : rows?.records || []
    const created = suppliers.value.find(supplier => supplier.supplier_name === newSupplierName.value.trim())
    if (created) form.sourceId = created.supplier_id
    creatingSupplier.value = false; newSupplierName.value = ''
  } catch (e) { error.value = e?.message || '新增供应商失败' } finally { supplierSaving.value = false }
}
const types = [{ value: 'purchase', label: '采购入库' }, { value: 'transfer', label: '调拨入库' }, { value: 'return', label: '退货入库' }, { value: 'profit', label: '盘盈入库' }]
const currentStoreId = Number(auth.user?.store_id || auth.user?.storeId || 1)
const form = reactive({ inboundType: 'purchase', sourceId: '', storeId: currentStoreId, remark: '' })
const canSwitchStore = computed(() => ['ADMIN', 'MANAGER'].includes(auth.role))
const inboundStores = computed(() => canSwitchStore.value ? stores.value : stores.value.filter(store => Number(store.store_id) === currentStoreId))
const transferStores = computed(() => stores.value.filter(store => Number(store.store_id) !== Number(form.storeId)))
async function loadSuppliers() { try { const rows = await api.inboundSuppliers(form.storeId); suppliers.value = Array.isArray(rows) ? rows : rows?.records || [] } catch { suppliers.value = []; error.value = '供应商列表加载失败，请检查网络连接' } }
function selectType(type) { form.inboundType = type; form.sourceId = ''; error.value = '' }
async function handleStoreChange() { form.sourceId = ''; error.value = ''; if (form.inboundType === 'purchase') await loadSuppliers() }
function start() {
  error.value = ''
  if (!form.storeId) { error.value = '请选择入库门店'; return }
  if (form.inboundType === 'purchase' && !form.sourceId) { error.value = '请选择供应商'; return }
  if (form.inboundType === 'transfer' && !form.sourceId) { error.value = '请选择调出门店'; return }
  // Starting from the preparation page always creates a new inbound voucher.
  // Clear a previous voucher's draft so catalog photos from the last inbound
  // cannot be mistaken for photos captured for this inbound.
  setStorage('dajin-inbound-draft', '[]')
  setStorage('dajin-inbound-client-id', '')
  const storeName = stores.value.find(store => Number(store.store_id) === Number(form.storeId))?.store_name || ''
  const sourceName = (form.inboundType === 'purchase'
    ? suppliers.value.find(supplier => Number(supplier.supplier_id) === Number(form.sourceId))?.supplier_name
    : stores.value.find(store => Number(store.store_id) === Number(form.sourceId))?.store_name) || ''
  setStorage('dajin-inbound-meta', JSON.stringify({ ...form, sourceId: form.sourceId === '' ? null : Number(form.sourceId), storeId: Number(form.storeId), storeName, sourceName }))
  router.push('/inbound/scan')
}
onMounted(async () => {
  try {
    const rows = await api.inboundStores()
    stores.value = Array.isArray(rows) ? rows : rows?.records || []
    if (!stores.value.some(store => Number(store.store_id) === currentStoreId)) stores.value.unshift({ store_id: currentStoreId, store_name: auth.user?.store_name || '当前门店' })
    if (!canSwitchStore.value) form.storeId = currentStoreId
    await loadSuppliers()
  } catch { stores.value = [{ store_id: currentStoreId, store_name: auth.user?.store_name || '当前门店' }]; error.value = '基础数据加载失败，已保留当前门店' } finally { loading.value = false }
})
</script>
