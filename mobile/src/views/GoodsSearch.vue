<template>
  <div class="shell">
    <header class="topbar"><div><button class="back" @click="router.back()">&#8249;</button><strong>货品搜索</strong></div><div></div></header>
    <div class="content page">
      <div class="search"><input v-model="keyword" placeholder="输入条码或名称搜索" @keyup.enter="doSearch" ref="inputRef"/><button @click="doScan">扫码</button><button @click="doSearch">搜索</button></div>
      <div v-if="scanned" class="list-card goods-search-card" style="border-color:#b7791f" @click="openGoods(scanned)"><div class="search-thumb"><img v-if="imageFor(scanned) && !failedImages.has(imageFor(scanned))" :src="imageFor(scanned)" :alt="`${scanned.name}照片`" @error="markImageFailed(imageFor(scanned))"/><span v-else>无图</span></div><div class="goods-search-info"><b>{{ scanned.name }}</b><p>{{ scanned.category }} · 条码 {{ scanned.barcode }}</p><small>库存 {{ scanned.stock }} · 克重 {{ scanned.weight }}g</small></div><strong>¥{{ money(scanned.sale_price) }}</strong></div>
      <p v-if="loading" class="muted" style="text-align:center;padding:20px">搜索中...</p>
      <p v-if="error" class="error" style="text-align:center;padding:20px">{{ error }} <button class="outline" style="min-height:36px;margin-left:8px" @click="doSearch">重试</button></p>
      <template v-if="!searched && recent.length">
        <p class="muted small" style="margin:8px 0">最近搜索</p>
        <div class="filter-tabs"><button v-for="t in recent" :key="t" @click="useRecent(t)">{{ t }}</button></div>
      </template>
      <div v-for="g in goods" :key="g.goods_id" class="list-card goods-search-card" @click="openGoods(g)"><div class="search-thumb"><img v-if="imageFor(g) && !failedImages.has(imageFor(g))" :src="imageFor(g)" :alt="`${g.name}照片`" @error="markImageFailed(imageFor(g))"/><span v-else>无图</span></div><div class="goods-search-info"><b>{{ g.name }}</b><p>{{ g.category }} · {{ g.barcode }}</p><small>库存 {{ g.stock }} · {{ g.weight }}g</small></div><strong>¥{{ money(g.sale_price) }}</strong></div>
      <div v-if="!loading && !scanned && !goods.length && searched" class="empty">未找到匹配商品<button class="outline" @click="clearSearch">清空重新搜索</button></div>
      <button v-if="hasMore" class="outline full" style="margin-top:12px" @click="loadMore">加载更多</button>
    </div>
  </div>
</template>
<script setup>
import { ref, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api, http } from '../api/request.js'
import { getStorage, setStorage } from '../utils/storage.js'
import { firstGoodsImage } from '../utils/goodsImages.js'
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
function openGoods(goods) { router.push({ path: `/goods/${goods.goods_id}`, query: { goods: JSON.stringify(goods) } }) }

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
