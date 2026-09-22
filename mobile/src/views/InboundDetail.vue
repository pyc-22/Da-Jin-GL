<template>
  <div class="shell">
    <header class="topbar"><button class="back" @click="router.back()">‹</button><strong>入库凭证</strong><span></span></header>
    <main class="content page inbound-page">
      <div v-if="loading" class="empty">加载中...</div>
      <template v-else>
        <div class="voucher">
          <h2>入库凭证</h2>
          <div class="voucher-grid"><span>单号</span><b>{{ data.inbound_no }}</b><span>类型</span><b>{{ typeName(data.inbound_type) }}</b><template v-if="data.source_name"><span>来源</span><b>{{ data.source_name }}</b></template><span>时间</span><b>{{ (data.create_time || '').slice(0, 16) }}</b><span>备注</span><b>{{ data.remark || '—' }}</b></div>
          <div v-for="item in data.items || []" :key="item.inbound_item_id" class="rank-row">
            <span>{{ item.name }} ×{{ item.quantity }}<small v-if="item.certificate_no">证书号 {{ item.certificate_no }}</small><small v-for="pieceNo in pieceNos(item)" :key="pieceNo" class="piece-no">单件码 {{ pieceNo }}</small></span>
            <small>{{ item.gold_weight || 0 }}g</small><strong>¥{{ money(item.label_price) }}</strong>
          </div>
          <div class="inbound-summary"><span>总件数 <b>{{ data.total_quantity }}</b></span><span>总金重 <b>{{ data.total_weight }}g</b></span><span>总金额 <b>¥{{ money(data.total_amount) }}</b></span></div>
        </div>
        <button class="primary full" @click="router.replace('/inbound/create')">新建入库单</button>
      </template>
    </main>
  </div>
</template>
<script setup>
import { onMounted, ref } from 'vue'; import { useRoute, useRouter } from 'vue-router'; import { api } from '../api/request.js'
const route = useRoute(), router = useRouter(), data = ref({}), loading = ref(true), money = v => Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }), typeName = t => ({ purchase: '采购入库', transfer: '调拨入库', return: '退货入库', profit: '盘盈入库' }[t] || t)
function pieceNos(item) { try { const value = typeof item.piece_nos === 'string' ? JSON.parse(item.piece_nos) : item.piece_nos; return Array.isArray(value) ? value.filter(Boolean) : [] } catch { return [] } }
onMounted(async () => { try { data.value = await api.inboundDetail(route.params.id) || {} } catch (e) { alert(e.message || '凭证加载失败') } finally { loading.value = false } })
</script>
<style scoped>.piece-no{color:var(--gold-deep);overflow-wrap:anywhere}</style>
