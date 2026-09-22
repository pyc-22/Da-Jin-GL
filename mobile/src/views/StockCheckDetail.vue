<template>
  <div class="shell">
    <header class="topbar"><button class="back" @click="router.back()">‹</button><strong>盘点详情</strong><button class="outline" @click="router.replace('/stock-check/create')">新建盘点</button></header>
    <main class="content page check-page">
      <div v-if="loading" class="empty">正在加载...</div>
      <div v-else-if="error" class="empty"><span>{{ error }}</span><button class="outline" @click="load">重新加载</button></div>
      <template v-else>
        <section class="check-detail-hero">
          <div><span>盘点单号</span><b>{{ data.bill_no }}</b><small>{{ data.scope_name || scopeLabel(data.scope_type) }}</small></div>
          <em :class="statusClass(data.status)">{{ statusLabel(data.status) }}</em>
        </section>
        <div class="check-detail-grid"><span>提交人<b>{{ data.operator_name || '-' }}</b></span><span>提交时间<b>{{ formatTime(data.create_time) }}</b></span><span>商品数<b>{{ data.item_count || 0 }}</b></span><span>数量差异<b :class="diffClass(data.total_diff)">{{ signed(data.total_diff) }}</b></span><span>盘盈金额<b>¥{{ money(data.profit_amount) }}</b></span><span>盘亏金额<b>¥{{ money(data.loss_amount) }}</b></span></div>
        <section v-if="data.remark || data.decision_remark" class="check-remarks"><p v-if="data.remark"><span>盘点备注</span>{{ data.remark }}</p><p v-if="data.decision_remark"><span>{{ Number(data.status) === 4 ? '驳回原因' : '审批备注' }}</span><b :class="{ error: Number(data.status) === 4 }">{{ data.decision_remark }}</b></p></section>
        <div class="filter-tabs"><button :class="{ active: detailFilter === 'all' }" @click="detailFilter = 'all'">全部</button><button :class="{ active: detailFilter === 'different' }" @click="detailFilter = 'different'">只看差异</button></div>
        <div v-if="!visibleItems.length" class="empty">没有差异商品</div>
        <article v-for="item in visibleItems" v-else :key="item.goodsId" class="check-detail-item"><div><b>{{ item.name || `商品 ${item.goodsId}` }}</b><small>{{ item.barcode || '-' }} · {{ item.parentCategoryName || '' }} {{ item.categoryName || '' }}</small></div><div class="check-detail-numbers"><span>系统 {{ number(item.stockSnapshot ?? item.stock) }}</span><span>实盘 {{ number(item.actual) }}</span><strong :class="diffClass(item.difference ?? Number(item.actual || 0) - Number(item.stock || 0))">{{ signed(item.difference ?? Number(item.actual || 0) - Number(item.stock || 0)) }}</strong></div></article>
      </template>
    </main>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api/request.js'

const route = useRoute()
const router = useRouter()
const data = ref({ items: [] })
const detailFilter = ref('all')
const loading = ref(false)
const error = ref('')
const visibleItems = computed(() => (data.value.items || []).filter(item => detailFilter.value === 'all' || Number(item.difference ?? Number(item.actual || 0) - Number(item.stock || 0)) !== 0))
const statusLabel = value => ({ 1: '待审批', 3: '已通过', 4: '已驳回' }[Number(value)] || '处理中')
const statusClass = value => Number(value) === 3 ? 'check-status approved' : Number(value) === 4 ? 'check-status rejected' : 'check-status pending'
const scopeLabel = value => ({ STORE: '全店商品', CATEGORY_L1: '一级分类', CATEGORY_L2: '二级分类', GOODS: '指定商品', CUSTOM: '自定义商品' }[value] || '盘点')
const number = value => Number(value || 0).toLocaleString('zh-CN', { maximumFractionDigits: 3 })
const signed = value => `${Number(value) > 0 ? '+' : ''}${number(value)}`
const money = value => Number(value || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const diffClass = value => Number(value) > 0 ? 'check-profit' : Number(value) < 0 ? 'check-loss' : 'check-even'
const formatTime = value => String(value || '').replace('T', ' ').slice(0, 16) || '-'
async function load() { loading.value = true; error.value = ''; try { data.value = await api.stockCheckDetail(route.params.id) || { items: [] } } catch (e) { error.value = e.message || '盘点详情加载失败' } finally { loading.value = false } }
onMounted(load)
</script>
