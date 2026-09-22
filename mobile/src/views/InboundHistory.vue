<template>
  <div class="shell">
    <header class="topbar"><button class="back" @click="router.back()">‹</button><strong>最近入库记录</strong><button class="outline" @click="load">刷新</button></header>
    <main class="content page inbound-page">
      <p class="muted small">仅显示最近3个月记录。离线暂存的单据需联网后逐单点「同步」上传，不会自动重放。</p>
      <p v-if="legacyData" class="error">本机保留了旧版未标记员工归属的草稿。为避免混单，已暂停显示和同步，请联系管理员核对；原数据未删除。</p>
      <div v-if="loading" class="empty">加载中...</div>
      <div v-else-if="!records.length" class="empty">暂无最近入库记录</div>
      <article v-for="record in records" :key="recordKey(record)" class="list-card inbound-history-card">
        <div class="inbound-history-main">
          <div class="history-title"><b>{{ record.inbound_no || '待同步入库单' }}</b><span :class="['history-status', statusClass(record.status)]">{{ statusLabel(record.status) }}</span></div>
          <p>{{ typeName(record.inbound_type || record.inboundType) }} · {{ formatTime(record.create_time || record.createdAt) }}</p>
          <p>{{ record.items?.length || record.itemCount || 0 }} 件 · {{ Number(record.total_weight || 0).toFixed(3) }}g · ¥{{ money(record.total_amount) }}</p>
          <p v-if="record.error" class="error">{{ record.error }}</p>
          <div v-if="record.items?.length" class="history-items">
            <span v-for="item in record.items.slice(0, 4)" :key="item.inbound_item_id || item.key || item.barcode" class="history-item">
              <img v-if="firstImage(item)" :src="firstImage(item)" alt="商品照片"/><span v-else class="history-no-image">无图</span><small>{{ item.name }}</small>
            </span>
          </div>
        </div>
        <div class="history-actions">
          <button v-if="syncable(record)" class="primary" :disabled="syncingId === recordKey(record)" @click="sync(record)">{{ syncingId === recordKey(record) ? '同步中...' : '同步' }}</button>
          <button v-if="record.inbound_id" class="outline history-detail" @click="router.push(`/inbound/detail/${record.inbound_id}`)">详情</button>
        </div>
      </article>
    </main>
  </div>
</template>
<script setup>
const pageCache = scopedStorage()
const getStorage = pageCache.get, setStorage = pageCache.set
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, http } from '../api/request.js'
import { normalizeGoodsImageUrl } from '../utils/goodsImages.js'
import { hasLegacyInboundData } from '../utils/storage.js'
const legacyData = hasLegacyInboundData()
import { useAppStore } from '../stores/app.js'
import { scopedStorage } from '../utils/storage.js'

const router = useRouter()
const app = useAppStore()
const loading = ref(true)
const records = ref([])
const syncingId = ref('')
const cacheKey = 'dajin-inbound-history'
const threeMonthsAgo = () => { const d = new Date(); d.setMonth(d.getMonth() - 3); return d.getTime() }
const money = value => Number(value || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const typeName = value => ({ purchase: '采购入库', transfer: '调拨入库', return: '退货入库', profit: '盘盈入库' }[String(value || '').toLowerCase()] || '盘点入库')
const parseDate = value => { const n = Number(value); if (Number.isFinite(n) && n > 0) return n; const parsed = Date.parse(String(value || '').replace(' ', 'T')); return Number.isNaN(parsed) ? 0 : parsed }
const formatTime = value => { const time = parseDate(value); return time ? new Date(time).toLocaleString('zh-CN', { hour12: false }).slice(0, 16) : '—' }
const statusLabel = value => ({ COMPLETED: '入库成功', SYNCED: '已同步', PENDING: '待同步', FAILED: '入库失败' }[String(value || '').toUpperCase()] || '处理中')
const statusClass = value => String(value || '').toUpperCase() === 'FAILED' ? 'failed' : (String(value || '').toUpperCase() === 'COMPLETED' || String(value || '').toUpperCase() === 'SYNCED' ? 'success' : 'pending')
const recordKey = record => record.clientRequestId || record.client_request_id || record.inbound_id || record.createdAt
const syncable = record => ['PENDING', 'FAILED'].includes(String(record.status || '').toUpperCase()) && Boolean(record.clientRequestId || record.client_request_id)
async function confirmSync(message) { if (typeof uni !== 'undefined' && uni.showModal) return await new Promise(resolve => uni.showModal({ title: '确认同步', content: message, success: r => resolve(Boolean(r.confirm)), fail: () => resolve(false) })); return window.confirm(message) }
function showAlert(content) { if (typeof uni !== 'undefined' && uni.showModal) { uni.showModal({ title: '同步失败', content, showCancel: false }); return } window.alert(content) }
async function sync(record) {
  const key = record.clientRequestId || record.client_request_id
  const failed = String(record.status || '').toUpperCase() === 'FAILED'
  const message = failed ? `上次同步失败：${record.error || '未知原因'}\n现在重试同步该入库单？` : '确认将该入库单同步到服务器？'
  if (!(await confirmSync(message))) return
  syncingId.value = key
  try {
    await app.syncPendingInbound(key)
    await load()
    app.loadPendingInbounds()
  } catch (e) {
    await load()
    showAlert(`同步失败：${e?.message || '未知原因'}`)
  } finally { syncingId.value = '' }
}
const firstImage = item => { try { const value = typeof item.images === 'string' ? JSON.parse(item.images) : item.images; return Array.isArray(value) ? normalizeGoodsImageUrl(value[0], http.defaults.baseURL) : '' } catch { return '' } }
function readCache() { try { const rows = JSON.parse(getStorage(cacheKey, '[]')); return Array.isArray(rows) ? rows.filter(row => parseDate(row.create_time || row.createdAt) >= threeMonthsAgo()) : [] } catch { return [] } }
function writeCache(rows) { setStorage(cacheKey, JSON.stringify(rows.filter(row => parseDate(row.create_time || row.createdAt) >= threeMonthsAgo()).slice(0, 200))) }
async function load() {
  loading.value = true
  const local = readCache()
  try {
    const remote = await api.inboundHistory()
    const remoteRows = Array.isArray(remote) ? remote : remote?.records || []
    const merged = [...remoteRows, ...local].reduce((all, row) => { const key = recordKey(row); if (!all.some(item => recordKey(item) === key)) all.push(row); return all }, [])
    records.value = merged.sort((a, b) => parseDate(b.create_time || b.createdAt) - parseDate(a.create_time || a.createdAt))
    writeCache(records.value)
  } catch {
    records.value = local.sort((a, b) => parseDate(b.create_time || b.createdAt) - parseDate(a.create_time || a.createdAt))
  } finally { loading.value = false }
}
onMounted(load)
</script>
<style scoped>
.inbound-history-card{align-items:flex-start}
.inbound-history-main{min-width:0;flex:1}
.history-title{display:flex;align-items:center;gap:8px;justify-content:space-between}
.history-title b{overflow-wrap:anywhere;line-height:1.4}
.inbound-history-main p{margin:5px 0 0;color:var(--ink-2);font-size:var(--f-xs)}
.history-status{flex:0 0 auto;font-size:var(--f-xs);font-weight:600}
.history-status.success{color:var(--ok)}
.history-status.failed{color:var(--err)}
.history-status.pending{color:var(--warn)}
.history-actions{display:flex;flex-direction:column;gap:8px;flex:0 0 auto}
.history-actions .primary{min-width:72px}
.history-detail{min-height:44px;padding:0 10px}
.history-items{display:flex;gap:8px;overflow:auto;margin-top:10px}
.history-item{width:52px;flex:0 0 52px;text-align:center;font-size:11px;color:var(--ink-3)}
.history-item img,.history-no-image{display:block;width:52px;height:52px;object-fit:cover;border-radius:6px;background:var(--line-soft);line-height:52px}
.history-item small{display:block;overflow-wrap:anywhere;line-height:1.4;margin-top:3px}
</style>
