<template>
  <div class="shell">
    <DesignHeader title="货品搜索" :back="true" @back="router.back()"></DesignHeader>
    <div class="content page">
      <div class="search"><input v-model="keyword" placeholder="输入条码或名称搜索" @keyup.enter="doSearch" ref="inputRef"/><button @click="doScan">扫码</button><button @click="doSearch">搜索</button></div>
      <div v-if="scanned" class="goods-grid"><div class="list-card goods-search-card" style="border-color:var(--gold-deep)" @click="openGoods(scanned)"><div class="search-thumb"><img v-if="imageFor(scanned) && !failedImages.has(imageFor(scanned))" :src="imageFor(scanned)" :alt="`${scanned.name}照片`" @error="markImageFailed(imageFor(scanned))"/><span v-else>无图</span></div><div class="goods-search-info"><b>{{ scanned.name }}</b><p>{{ scanned.category }} · 条码 {{ scanned.barcode }}</p><small>库存 {{ inventoryText(scanned) }} · {{ Number(scanned.price_type) === 1 ? '按克计价' : `${scanned.weight || '—'}g` }}</small></div><strong>{{ priceLabel(scanned) }}</strong></div></div>
      <p v-if="loading" class="muted" style="text-align:center;padding:20px">搜索中...</p>
      <p v-if="error" class="error" style="text-align:center;padding:20px">{{ error }} <button class="outline" style="min-height:var(--tap);margin-left:8px" @click="doSearch">重试</button></p>
      <template v-if="!searched && recent.length">
        <p class="muted small" style="margin:8px 0">最近搜索</p>
        <div class="goods-grid recent-grid"><button v-for="t in recent" :key="t" class="list-card recent-card" @click="useRecent(t)"><span class="search-thumb">⌕</span><span class="goods-search-info"><b>{{ t }}</b><small>再次搜索</small></span></button></div>
      </template>
      <div class="goods-grid"><div v-for="g in goods" :key="g.goods_id" class="list-card goods-search-card" @click="openGoods(g)"><div class="search-thumb"><img v-if="imageFor(g) && !failedImages.has(imageFor(g))" :src="imageFor(g)" :alt="`${g.name}照片`" @error="markImageFailed(imageFor(g))"/><span v-else>无图</span></div><div class="goods-search-info"><b>{{ g.name }}</b><p>{{ g.category }} · {{ g.barcode }}</p><small>库存 {{ inventoryText(g) }} · {{ Number(g.price_type) === 1 ? '按克计价' : `${g.weight || '—'}g` }}</small></div><strong>{{ priceLabel(g) }}</strong></div></div>
      <EmptyState v-if="!loading && !scanned && !goods.length && searched" title="">未找到匹配商品<button class="outline" @click="clearSearch">清空重新搜索</button></EmptyState>
      <button v-if="hasMore" class="outline full" style="margin-top:12px" @click="loadMore">加载更多</button>
    </div>
  </div>
</template>
<script setup>
import EmptyState from '../components/EmptyState.vue'

import Chip from '../components/Chip.vue'

import DesignHeader from '../components/DesignHeader.vue'

import { ref, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api, http } from '../api/request.js'
import { getStorage, setStorage } from '../utils/storage.js'
import { firstGoodsImage } from '../utils/goodsImages.js'
import { inventoryText } from '../utils/inventoryUnit.js'
import { useAppStore } from '../stores/app.js'
import { isNativeApp, scanNativeBarcode } from '../utils/nativeDevice.js'
const router = useRouter()
const app = useAppStore()
const keyword = ref('')
const goods = ref([])
const scanned = ref(null)
const loading = ref(false)
const error = ref('')
const searched = ref(false)
const page = ref(1)
const hasMore = ref(false)
const inputRef = ref(null)
const recent = ref((()=>{try{return JSON.parse(getStorage('dajin-goods-searches','[]'))}catch{return []}})())
const failedImages = ref(new Set())
const money = (v) => Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const imageFor = goods => firstGoodsImage(goods, http.defaults.baseURL)
function markImageFailed(url) { failedImages.value = new Set([...failedImages.value, url]) }
function priceLabel(goods) {
  if (Number(goods?.price_type) !== 1) return `¥${money(goods?.sale_price)}`
  const type = String(goods?.gold_type || goods?.goldType || '足金')
  const quote = (Array.isArray(app.gold) ? app.gold : []).find(row => String(row.price_type || row.priceType || row.type_name || row.name || '') === type)
  const price = Number(quote?.salePrice ?? (type === '足金' ? app.primaryGold?.salePrice : 0))
  return price > 0 ? `¥${money(price)}/g` : '待同步金价'
}
function openGoods(goods) { router.push(`/goods/${goods.goods_id}`) }

function saveRecent(term){
  recent.value = [term, ...recent.value.filter(t=>t!==term)].slice(0,8)
  setStorage('dajin-goods-searches', JSON.stringify(recent.value))
}
function useRecent(t){ keyword.value = t; doSearch() }
function clearSearch(){ keyword.value=''; searched.value=false; goods.value=[]; scanned.value=null; error.value=''; inputRef.value?.focus() }

onMounted(() => { inputRef.value?.focus() })
watch(()=>app.eventVersion,()=>{if(searched.value&&['GOODS_UPDATED','STOCK_UPDATED','STOCK_IN_COMPLETED','ORDER_COMPLETED'].includes(app.lastEventType))doSearch()})

async function doSearch() {
  if (!keyword.value.trim()) return
  failedImages.value = new Set()
  scanned.value = null
  loading.value = true
  error.value = ''
  searched.value = true
  page.value = 1
  saveRecent(keyword.value.trim())
  try {
    const d = await api.goods({ keyword: keyword.value, page: 1, size: 20, status: 1 })
    const rows = d.records || d || []
    goods.value = rows
    hasMore.value = rows.length >= 20
  } catch (e) {
    error.value = e.message || '搜索失败'
    goods.value = []
  } finally { loading.value = false }
}

async function loadMore() {
  page.value++
  loading.value = true
  try {
    const d = await api.goods({ keyword: keyword.value, page: page.value, size: 20, status: 1 })
    const rows = d.records || d || []
    goods.value = [...goods.value, ...rows]
    hasMore.value = rows.length >= 20
  } catch { } finally { loading.value = false }
}

async function doScan() {
  if (isNativeApp()) {
    try { keyword.value = await scanNativeBarcode(); await scanBarcode(keyword.value) }
    catch (e) { if (!e.message?.includes('取消')) error.value = e.message || '扫码失败，请重试' }
    return
  }
  if (typeof uni !== 'undefined') {
    uni.scanCode({
      success: async (r) => {
        keyword.value = r.result
        await scanBarcode(r.result)
      }
    })
  } else {
    const code = prompt('输入商品条码')
    if (code) {
      keyword.value = code
      await scanBarcode(code)
    }
  }
}

async function scanBarcode(barcode) {
  failedImages.value = new Set()
  loading.value = true
  error.value = ''
  searched.value = true
  scanned.value = null
  goods.value = []
  try {
    const g = await api.scanGoods(barcode)
    scanned.value = g
  } catch (e) {
    if (e.message?.includes('不存在')) {
      error.value = '该条码未找到商品'
    } else {
      await doSearch()
    }
  } finally { loading.value = false }
}
</script>
<style scoped>
.goods-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:12px;margin-top:10px}.goods-search-card{display:grid;grid-template-columns:64px minmax(0,1fr);gap:10px;align-items:center;min-width:0}.goods-search-card>strong{grid-column:2;color:var(--gold-deep)}.recent-grid{grid-template-columns:repeat(auto-fill,minmax(160px,1fr))}.recent-card{display:flex;align-items:center;gap:10px;text-align:left}.recent-card .search-thumb{display:grid;place-items:center;font-size:24px}.recent-card .goods-search-info{display:grid;gap:4px}@media(max-width:520px){.goods-grid{grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}.goods-search-card{display:block}.goods-search-card .goods-search-info{margin-top:6px}.goods-search-card>strong{display:block;margin-top:6px}.search-thumb{width:100%;aspect-ratio:1}}
</style>
