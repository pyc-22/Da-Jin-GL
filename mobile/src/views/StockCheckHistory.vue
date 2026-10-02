<template>
  <div class="shell">
    <DesignHeader title="盘点记录" :back="true" @back="router.back()"><button class="outline" :disabled="loading" @click="load">刷新</button></DesignHeader>
    <main class="content page check-page">
      <p class="check-tip">仅展示最近 3 个月记录；销售只显示本人提交的盘点单。</p>
      <EmptyState v-if="loading" title="正在加载..." />
      <EmptyState v-else-if="error" title=""><span>{{ error }}</span><button class="outline" @click="load">重新加载</button></EmptyState>
      <EmptyState v-else-if="!records.length" title="最近 3 个月暂无盘点记录" />
      <button v-for="record in records" v-else :key="record.check_id" class="check-history-card" @click="router.push(`/stock-check/detail/${record.check_id}`)">
        <div class="check-history-head"><span><b>{{ record.bill_no }}</b><small>{{ record.scope_name || scopeLabel(record.scope_type) }}</small></span><em :class="statusClass(record.status)">{{ statusLabel(record.status) }}</em></div>
        <div class="check-history-stats"><span>商品 <b>{{ record.item_count || 0 }}</b></span><span>盘盈 <b>{{ record.profit_count || 0 }}</b></span><span>盘亏 <b>{{ record.loss_count || 0 }}</b></span><span>差异 <b>{{ signed(record.total_diff) }}</b></span></div>
        <div class="check-history-foot"><span>{{ record.operator_name || '提交人' }} · {{ formatTime(record.create_time) }}</span><strong>详情 ›</strong></div>
      </button>
    </main>
  </div>
</template>

<script setup>
import EmptyState from '../components/EmptyState.vue'

import DesignHeader from '../components/DesignHeader.vue'

import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api/request.js'
import { useAppStore } from '../stores/app.js'

const router = useRouter()
const app = useAppStore()
const records = ref([])
const loading = ref(false)
const error = ref('')
const statusLabel = value => ({ 1: '待审批', 3: '已通过', 4: '已驳回' }[Number(value)] || '处理中')
const statusClass = value => Number(value) === 3 ? 'check-status approved' : Number(value) === 4 ? 'check-status rejected' : 'check-status pending'
const scopeLabel = value => ({ STORE: '全店商品', CATEGORY_L1: '一级分类', CATEGORY_L2: '二级分类', GOODS: '指定商品', CUSTOM: '自定义商品' }[value] || '盘点')
const number = value => Number(value || 0).toLocaleString('zh-CN', { maximumFractionDigits: 3 })
const signed = value => `${Number(value) > 0 ? '+' : ''}${number(value)}`
const formatTime = value => String(value || '').replace('T', ' ').slice(0, 16) || '-'
async function load() { loading.value = true; error.value = ''; try { records.value = await api.stockCheckHistory() || [] } catch (e) { error.value = e.message || '盘点记录加载失败' } finally { loading.value = false } }
onMounted(load)
watch(() => app.eventVersion, load)
</script>
