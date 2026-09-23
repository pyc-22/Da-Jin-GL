<template>
  <div class="shell">
    <header class="topbar"><button class="back" @click="router.back()">‹</button><div><strong>客存金台账</strong><span class="store">{{ storeName }}</span></div><button class="icon-btn" @click="load">刷新</button></header>
    <main class="content page">
      <div class="kpi-grid"><Kpi label="存料类型" :value="rows.length"/><Kpi label="在存总重" :value="`${totalWeight.toFixed(3)}g`"/><Kpi label="库存估值" :value="money(totalValue)"/><Kpi label="明细笔数" :value="detailCount"/></div>
      <div class="banner">按旧料类型独立记账，收料、领用、补料和结余均保留流水，交付时请与客户当面核对克重。</div>
      <div class="ledger-toolbar"><button class="primary" @click="openForm('in')">收料入账</button><button class="outline" @click="openForm('out')">领用出账</button></div>
      <div v-if="loading" class="empty">加载中...</div><div v-else-if="error" class="empty"><span>{{ error }}</span><button class="outline" @click="load">重试</button></div><div v-else-if="!rows.length" class="empty">暂无客存金记录</div>
      <article v-for="row in rows" :key="row.material_type" class="list-card ledger-row"><div><b>{{ row.material_type || '未分类旧料' }}</b><p>{{ row.inbound_count || 0 }} 笔收料 · {{ row.outbound_count || 0 }} 笔领用</p></div><div class="ledger-value"><strong>{{ Number(row.total_weight || 0).toFixed(3) }}g</strong><small>{{ money(row.total_value) }}</small></div><button class="outline" @click="openDetails(row)">明细</button></article>
    </main>
    <div v-if="modal" class="mobile-modal" @click.self="modal=null"><div class="mobile-modal-card"><div class="modal-head"><h3>{{ modal.type==='details' ? `${modal.row.material_type} · 流水明细` : (modal.type==='in'?'收料入账':'领用出账') }}</h3><button class="icon-btn" @click="modal=null">×</button></div>
      <template v-if="modal.type==='details'"><div v-if="!(modal.row.details||[]).length" class="empty">暂无流水</div><div v-for="(d,i) in modal.row.details||[]" :key="i" class="ledger-detail"><div><b>{{ d.direction===-1||d.direction==='-1'?'领用出账':'收料入账' }}</b><small>{{ d.source || '手工记录' }} · {{ String(d.create_time||'').slice(0,16) }}</small></div><strong :class="Number(d.direction||1)<0?'negative':''">{{ Number(d.direction||1)<0?'−':'+' }}{{ Number(d.weight||0).toFixed(3) }}g</strong></div><button class="outline full" @click="modal=null">关闭</button></template>
      <template v-else><label class="form-label">旧料类型<select v-model="form.materialType"><option v-for="t in types" :key="t" :value="t">{{ t }}</option></select></label><label class="form-label">克重（g）<input v-model.number="form.weight" type="number" min="0.001" step="0.001" placeholder="请输入克重"/></label><label class="form-label">成色（0-1）<input v-model.number="form.purity" type="number" min="0.001" max="1" step="0.001"/></label><label class="form-label">估值（元）<input v-model.number="form.value" type="number" min="0" step="0.01"/></label><label class="form-label">备注<textarea v-model="form.remark" rows="2"></textarea></label><p v-if="formError" class="error">{{ formError }}</p><button class="primary full" :disabled="saving" @click="submitForm">{{ saving?'提交中...':'确认提交' }}</button></template>
    </div></div>
  </div>
</template>
<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth.js'
import { api } from '../api/request.js'
import Kpi from '../components/Kpi.vue'
const router=useRouter(); const auth=useAuthStore()
const rows=ref([]); const loading=ref(false); const error=ref(''); const modal=ref(null); const types=ref([]); const saving=ref(false); const formError=ref(''); const form=ref({materialType:'',weight:null,purity:.999,value:0,remark:''})
const totalWeight=computed(()=>rows.value.reduce((s,r)=>s+Number(r.total_weight||0),0)); const totalValue=computed(()=>rows.value.reduce((s,r)=>s+Number(r.total_value||0),0)); const detailCount=computed(()=>rows.value.reduce((s,r)=>s+Number(r.inbound_count||0)+Number(r.outbound_count||0),0)); const money=v=>`¥${Number(v||0).toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2})}`; const storeName=computed(()=>auth.user?.store_name||auth.user?.storeName||'默认门店')
async function load(){loading.value=true;error.value='';try{const [ledger,typeRows]=await Promise.all([api.oldMaterial(),api.oldMaterialTypes()]);rows.value=Array.isArray(ledger)?ledger:(ledger?.records||[]);types.value=(Array.isArray(typeRows)?typeRows:typeRows?.records||[]).filter(x=>Number(x.status??1)===1).map(x=>x.name);if(!form.value.materialType)form.value.materialType=types.value[0]||''}catch(e){error.value=e?.message||'客存金台账加载失败'}finally{loading.value=false}}
function openDetails(row){modal.value={type:'details',row}}
function openForm(type){formError.value='';form.value={materialType:types.value[0]||'',weight:null,purity:.999,value:0,remark:''};modal.value={type}}
async function submitForm(){if(!form.value.materialType||!(Number(form.value.weight)>0)){formError.value='请填写旧料类型和有效克重';return}saving.value=true;formError.value='';try{const payload={materialType:form.value.materialType,weight:Number(form.value.weight),purity:Number(form.value.purity||.999),value:Number(form.value.value||0),remark:form.value.remark};if(modal.value.type==='in')await api.oldMaterialIn(payload);else await api.oldMaterialOut(payload);modal.value=null;await load()}catch(e){formError.value=e?.message||'提交失败'}finally{saving.value=false}}
onMounted(load)
</script>
<style scoped>
.ledger-toolbar{display:flex;gap:8px;margin:12px 0}.ledger-toolbar button{flex:1}.ledger-row{align-items:center}.ledger-row>div:first-child{flex:1;min-width:0}.ledger-row p{margin:5px 0 0;color:var(--ink-3);font-size:12px}.ledger-value{text-align:right;margin-right:6px}.ledger-value strong{display:block;color:var(--gold-deep);font-size:15px}.ledger-value small{color:var(--ink-3);font-size:11px}.modal-head{display:flex;align-items:center;justify-content:space-between;margin-bottom:10px}.modal-head h3{margin:0;font-size:16px}.ledger-detail{display:flex;justify-content:space-between;padding:10px 0;border-bottom:1px solid var(--line-soft)}.ledger-detail small{display:block;color:var(--ink-3);font-size:11px;margin-top:3px}.ledger-detail strong{color:var(--ok)}.ledger-detail strong.negative{color:var(--err)}.form-label select,.form-label input,.form-label textarea{display:block;width:100%;margin-top:4px;min-height:44px;border:1px solid #dfe3e8;border-radius:8px;padding:8px 10px;background:#fff}.form-label{display:block;font-size:12px;color:var(--ink-2);margin:10px 0}.form-label textarea{min-height:70px}
</style>
