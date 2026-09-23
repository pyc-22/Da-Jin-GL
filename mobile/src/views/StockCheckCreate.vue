<template>
  <div class="shell">
    <header class="topbar"><button class="back" @click="router.back()">‹</button><strong>盘点准备</strong><button class="outline" @click="router.push('/stock-check/history')">最近记录</button></header>
    <main class="content page check-page">
      <section class="form-card">
        <h3>选择盘点范围</h3>
        <div class="check-scope-grid">
          <button v-for="option in scopeOptions" :key="option.value" :class="{ active: form.scopeType === option.value }" @click="selectScope(option.value)">
            <b>{{ option.short }}</b><span>{{ option.label }}</span>
          </button>
        </div>

        <label v-if="form.scopeType === 'CATEGORY_L1'" class="form-label">一级分类
          <select v-model="form.scopeId"><option value="" disabled>请选择一级分类</option><option v-for="item in roots" :key="item.category_id" :value="item.category_id">{{ item.name }}</option></select>
        </label>
        <label v-if="form.scopeType === 'CATEGORY_L2'" class="form-label">一级分类
          <select v-model="parentId" @change="form.scopeId = ''"><option value="" disabled>请选择一级分类</option><option v-for="item in roots" :key="item.category_id" :value="item.category_id">{{ item.name }}</option></select>
        </label>
        <label v-if="form.scopeType === 'CATEGORY_L2'" class="form-label">二级分类
          <select v-model="form.scopeId" :disabled="!parentId"><option value="" disabled>请选择二级分类</option><option v-for="item in childOptions" :key="item.category_id" :value="item.category_id">{{ item.name }}</option></select>
        </label>

        <template v-if="form.scopeType === 'GOODS'">
          <label class="form-label">指定商品</label>
          <div class="search check-goods-search"><input v-model.trim="goodsKeyword" placeholder="输入条码或商品名称" @keyup.enter="searchGoods"/><button @click="searchGoods">查询</button></div>
          <div v-if="selectedGoods" class="selected-check-goods"><span><b>{{ selectedGoods.name }}</b><small>{{ selectedGoods.barcode }} · 系统库存 {{ selectedGoods.stock }}</small></span><button aria-label="移除商品" @click="clearSelectedGoods">×</button></div>
          <template v-else><button v-for="goods in goodsResults" :key="goods.goods_id" class="check-goods-option" @click="chooseGoods(goods)"><span><b>{{ goods.name }}</b><small>{{ goods.barcode }} · {{ goods.category || '未分类' }}</small></span><strong>库存 {{ goods.stock }}</strong></button></template>
        </template>

        <label class="form-label">盘点门店<input :value="storeName" disabled /></label>
        <label class="form-label">备注（选填）<textarea v-model.trim="form.remark" maxlength="500" rows="3" placeholder="填写本次盘点说明"></textarea></label>
        <p v-if="error" class="error">{{ error }}</p>
      </section>
      <p class="check-tip">提交后由店长或管理员审批，审批通过才会产生库存调整和出入库流水。</p>
      <button v-permission="'stock:check:create'" class="primary full" :disabled="loading" @click="start">{{ loading ? '正在读取库存...' : '开始盘点' }}</button>
    </main>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api/request.js'
import { useAuthStore } from '../stores/auth.js'
import { scopedStorage } from '../utils/storage.js'
const pageCache = scopedStorage(), setStorage = pageCache.set

const router = useRouter()
const auth = useAuthStore()
const scopeOptions = [
  { value: 'STORE', label: '全店商品', short: '全' },
  { value: 'CATEGORY_L1', label: '一级分类', short: '一' },
  { value: 'CATEGORY_L2', label: '二级分类', short: '二' },
  { value: 'GOODS', label: '指定商品', short: '单' }
]
const form = reactive({ scopeType: 'STORE', scopeId: '', remark: '' })
const categories = ref([])
const parentId = ref('')
const goodsKeyword = ref('')
const goodsResults = ref([])
const selectedGoods = ref(null)
const error = ref('')
const loading = ref(false)
const storeName = computed(() => auth.user?.store_name || auth.user?.storeName || '当前门店')
const roots = computed(() => categories.value.filter(item => Number(item.level) === 1))
const childOptions = computed(() => categories.value.filter(item => Number(item.level) === 2 && Number(item.parent_id) === Number(parentId.value)))

function selectScope(value) {
  form.scopeType = value
  form.scopeId = ''
  parentId.value = ''
  selectedGoods.value = null
  goodsResults.value = []
  error.value = ''
}
async function searchGoods() {
  if (!goodsKeyword.value) { error.value = '请输入商品条码或名称'; return }
  try {
    const data = await api.goods({ keyword: goodsKeyword.value, page: 1, size: 20, includeInactive: true })
    goodsResults.value = data?.records || []
    error.value = goodsResults.value.length ? '' : '没有找到可盘点商品'
  } catch (e) { error.value = e.message || '商品查询失败' }
}
function chooseGoods(goods) { selectedGoods.value = goods; form.scopeId = goods.goods_id; goodsResults.value = []; error.value = '' }
function clearSelectedGoods() { selectedGoods.value = null; form.scopeId = ''; goodsKeyword.value = '' }
function scopeName() {
  if (form.scopeType === 'STORE') return storeName.value
  if (form.scopeType === 'GOODS') return selectedGoods.value?.name || '指定商品'
  return categories.value.find(item => Number(item.category_id) === Number(form.scopeId))?.name || '所选分类'
}
function normalizeItem(goods) {
  return {
    key: `check-${goods.goods_id}`,
    goodsId: goods.goods_id,
    barcode: goods.barcode,
    name: goods.name,
    categoryId: goods.category_id,
    category: goods.category || '',
    parentCategory: goods.parent_category || '',
    systemStock: Number(goods.stock || 0),
    actual: 0,
    counted: false,
    images: goods.images || [],
    scannedPieceNos: []
  }
}
async function start() {
  error.value = ''
  if (form.scopeType !== 'STORE' && !form.scopeId) { error.value = '请选择盘点范围'; return }
  loading.value = true
  try {
    const rows = await api.stockCheckScopeGoods(form.scopeType, form.scopeType === 'STORE' ? null : Number(form.scopeId))
    if (!pageCache.current()) return
    if (!Array.isArray(rows) || !rows.length) { error.value = '所选范围内没有可盘点商品'; return }
    setStorage('dajin-stock-check-meta', JSON.stringify({ scopeType: form.scopeType, scopeId: form.scopeType === 'STORE' ? null : Number(form.scopeId), scopeName: scopeName(), remark: form.remark, startedAt: Date.now() }))
    setStorage('dajin-stock-check-draft', JSON.stringify(rows.map(normalizeItem)))
    setStorage('dajin-stock-check-client-id', '')
    router.push('/stock-check/scan')
  } catch (e) { error.value = e.message || '读取盘点范围失败' }
  finally { loading.value = false }
}
onMounted(async () => {
  try { categories.value = await api.goodsCategories() || [] }
  catch { categories.value = [] }
})
</script>
