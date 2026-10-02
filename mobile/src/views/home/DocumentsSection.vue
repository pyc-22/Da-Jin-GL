<template>
  <section class="page documents-page">
    <div class="chips" aria-label="单据类型"><Chip v-for="item in filters" :key="item.key" :selected="filter===item.key" @click="filter=item.key">{{ item.label }} {{ count(item.key) }}</Chip></div>
    <div class="summary-line"><small class="muted">本月销售、回收记录 · 当前加工单</small><button class="text-action" :disabled="loading" @click="load">刷新</button></div>
    <p v-for="message in errors" :key="message" class="error" role="alert">{{ message }}</p>
    <EmptyState v-if="loading" title="正在汇总单据" />
    <template v-else><button v-for="row in filtered" :key="row.key" class="document-card panel" @click="open(row)"><div class="summary-line"><small>{{ row.no || '—' }} · {{ row.typeLabel }}单</small><StatusPill :tone="row.tone">{{ row.status }}</StatusPill></div><div class="document-main"><strong>{{ row.title }}</strong><span class="document-amount">{{ row.amount == null ? '—' : money(row.amount) }}</span></div><div class="summary-line"><small>{{ row.customer }}<template v-if="row.salesperson"> · 导购 {{ row.salesperson }}</template></small><small>{{ row.amountLabel }}</small></div><small>{{ row.time }}</small></button><EmptyState v-if="!filtered.length" title="暂无单据" message="显示当前账号有权限查询的记录" /></template>
    <div v-if="selected" class="mobile-modal" @click.self="selected=null"><section class="mobile-modal-card" role="dialog" aria-modal="true" aria-label="单据详情"><div class="modal-head"><h3>{{ selected.typeLabel }}单详情</h3><button class="modal-close" aria-label="关闭详情" @click="selected=null">×</button></div><p>{{ selected.no }}</p><StatusPill :tone="selected.tone">{{ selected.status }}</StatusPill><p class="money">{{ selected.amount == null ? '—' : money(selected.amount) }}</p><p>{{ selected.title }}</p><p>{{ selected.customer }} · {{ selected.time }}</p><template v-if="selected.type==='sale'"><div v-for="(line,index) in selected.raw.lines" :key="index" class="summary-line"><span>{{ line.goods_name }}</span><small>{{ line.weight == null ? '' : line.weight + 'g' }}</small></div><p v-if="selected.raw.remaining_amount != null">待收 {{ money(selected.raw.remaining_amount) }}</p></template><p v-else-if="selected.raw.weight != null">克重 {{ selected.raw.weight }}g · 成色 {{ Number(selected.raw.purity || 0)*100 }}%</p></section></div>
  </section>
</template>
<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth.js'
import { useAppStore } from '../../stores/app.js'
import { api } from '../../api/request.js'
import { mergeDocuments } from '../../utils/documents.js'
import Chip from '../../components/Chip.vue'
import StatusPill from '../../components/StatusPill.vue'
import EmptyState from '../../components/EmptyState.vue'
const auth=useAuthStore(), app=useAppStore(), router=useRouter()
const filter=ref('all'), documents=ref([]), loading=ref(false), errors=ref([]), selected=ref(null)
const filters=[{key:'all',label:'全部'},{key:'sale',label:'销售'},{key:'processing',label:'加工'},{key:'recycle',label:'回收'}]
const filtered=computed(()=>documents.value.filter(row=>filter.value==='all'||row.type===filter.value))
const count=key=>documents.value.filter(row=>key==='all'||row.type===key).length
const money=value=>'¥'+Number(value).toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2})
let requestVersion=0
async function load(){
  const version=++requestVersion; loading.value=true; errors.value=[]; documents.value=[]; selected.value=null
  const sources=[
    {allowed:auth.can('report:view'),label:'销售',fetch:()=>api.reportSales({timeType:'month'})},
    {allowed:auth.can('processing:view'),label:'加工',fetch:()=>api.processingOrders()},
    {allowed:auth.can('report:view'),label:'回收',fetch:()=>api.reportRecycle({timeType:'month'})}
  ]
  const results=await Promise.allSettled(sources.map(source=>source.allowed?source.fetch():Promise.resolve([])))
  if(version!==requestVersion)return
  errors.value=results.flatMap((result,index)=>result.status==='rejected'?[sources[index].label+'单加载失败，请刷新重试']:[])
  documents.value=mergeDocuments(...results.map(result=>result.status==='fulfilled'?result.value:[]));loading.value=false
}
function open(row){if(row.type==='processing')router.push('/processing?id='+encodeURIComponent(row.id));else selected.value=row}
onMounted(load)
watch(()=>app.eventVersion,load)
watch(()=>auth.permissions?.join('|'),load)
</script>
