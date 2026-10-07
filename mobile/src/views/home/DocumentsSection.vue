<template>
  <section class="page documents-page">
    <div class="chips" aria-label="单据类型"><Chip v-for="item in filters" :key="item.key" :selected="filter===item.key" @click="filter=item.key">{{ item.label }} {{ count(item.key) }}</Chip></div>
    <div class="summary-line"><small class="muted">本月销售、回收记录 · 当前加工单</small><button class="text-action" :disabled="loading" @click="load">刷新</button></div>
    <p v-for="message in errors" :key="message" class="error" role="alert">{{ message }}</p>
    <EmptyState v-if="loading" title="正在汇总单据" />
    <template v-else><div v-for="row in filtered" :key="row.key" class="doc-swipe" @touchstart="onTouchStart(row)" @touchmove="onTouchMove" @touchend="onTouchEnd(row)"><button v-if="canArchive(row)" type="button" class="doc-delete" @click.stop="archiveRow(row)">删除</button><button class="document-card panel" :style="{ transform: `translateX(${swipeKey === row.key ? swipeOffset : 0}px)` }" @click="open(row)"><div class="summary-line"><small>{{ row.no || '—' }} · {{ row.typeLabel }}单</small><StatusPill :tone="row.tone">{{ row.status }}</StatusPill></div><div class="document-main"><strong>{{ row.title }}</strong><span class="document-amount">{{ row.amount == null ? '—' : money(row.amount) }}</span></div><div class="summary-line"><small>{{ row.customer }}<template v-if="row.salesperson"> · 导购 {{ row.salesperson }}</template></small><small>{{ row.amountLabel }}</small></div><small>{{ row.time }}</small></button></div><EmptyState v-if="!filtered.length" title="暂无单据" message="显示当前账号有权限查询的记录" /></template>
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
    {allowed:canReport('report:store-performance'),label:'销售',fetch:()=>api.reportSales({timeType:'month'})},
    {allowed:auth.can('processing:view'),label:'加工',fetch:()=>api.processingOrders()},
    {allowed:canReport('report:recycle'),label:'回收',fetch:()=>api.reportRecycle({timeType:'month'})}
  ]
  const results=await Promise.allSettled(sources.map(source=>source.allowed?source.fetch():Promise.resolve([])))
  if(version!==requestVersion)return
  errors.value=results.flatMap((result,index)=>result.status==='rejected'?[sources[index].label+'单加载失败，请刷新重试']:[])
  documents.value=mergeDocuments(...results.map(result=>result.status==='fulfilled'?result.value:[]));loading.value=false
}
function open(row){if(swipeOffset.value){swipeOffset.value=0;swipeKey.value='';return}if(row.type==='processing')router.push('/processing?id='+encodeURIComponent(row.id));else selected.value=row}
function canReport(permission){return auth.can('report:view')&&(auth.role!=='MANAGER'||auth.can(permission))}
// 已取货的加工单可以在手机端手动删除（只是从手机列表隐藏，管理端保留完整记录）
const swipeOffset=ref(0),swipeKey=ref('')
let startX=0,startY=0,horizontal=false,swiping=false
const canArchive=row=>row.type==='processing'&&row.raw?.status==='PICKED_UP'&&Number(row.raw?.mobile_archived)!==1
function onTouchStart(row){if(!canArchive(row)){swiping=false;return}swiping=true;horizontal=false;startX=0;startY=0;swipeOffset.value=0;swipeKey.value=''}
function onTouchMove(event){
  if(!swiping)return
  const touch=event.changedTouches?.[0];if(!touch)return
  if(!startX){startX=touch.clientX;startY=touch.clientY}
  const dx=touch.clientX-startX,dy=touch.clientY-startY
  if(!horizontal&&Math.abs(dx)>8&&Math.abs(dx)>Math.abs(dy))horizontal=true
  if(!horizontal)return
  if(event.cancelable)event.preventDefault()
  swipeOffset.value=Math.max(-82,Math.min(0,dx))
}
function onTouchEnd(row){
  if(!swiping)return
  swiping=false
  if(horizontal&&swipeOffset.value<-42){swipeOffset.value=-82;swipeKey.value=row.key}else{swipeOffset.value=0;swipeKey.value=''}
  horizontal=false;startX=0;startY=0
}
async function archiveRow(row){
  swipeOffset.value=0;swipeKey.value=''
  if(!window.confirm(`确认从手机端删除「已取货」加工单 ${row.no||''}？\n管理端仍保留完整记录与账务，不影响库存和提成。`))return
  try{await api.archiveProcessingOrder(row.id);documents.value=documents.value.filter(item=>item.key!==row.key)}
  catch(error){errors.value=[error?.message||'删除失败，请重试']}
}
onMounted(load)
watch(()=>app.eventVersion,load)
watch(()=>[auth.role,auth.permissions?.join('|')],load)
</script>
