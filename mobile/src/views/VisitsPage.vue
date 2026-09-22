<template>
  <div class="shell">
    <header class="topbar"><div><button class="back" @click="router.back()">&#8249;</button><strong>回访任务</strong></div><div><button class="outline" style="min-height:36px;padding:0 12px" @click="loadVisits">刷新</button></div></header>
    <div class="content page">
      <div class="visit-stats"><span>待回访 <b>{{ visitStats.pending }}</b></span><span>已完成 <b>{{ visitStats.done }}</b></span><span>完成率 <b>{{ visitStats.rate }}%</b></span></div>
      <div class="filter-tabs"><button v-for="t in visitTabs" :key="t" :class="{active: visitFilter===t}" @click="visitFilter=t">{{ t }}</button></div>
      <template v-if="visitFilter==='生日提醒'">
        <div v-for="m in birthdayMembers" :key="'sb'+(m.member_id||m.id)" class="list-card"><div><b>{{ m.name }}</b><p>{{ maskPhone(m.phone) }}</p><small>生日 {{ (m.birthday||'').slice(5) }}</small></div><button class="outline" @click="call(m.phone)">拨号</button></div>
        <div v-if="!birthdayMembers.length" class="empty">本月暂无生日会员</div>
      </template>
      <template v-else>
        <div v-for="task in filteredVisits" :key="task.task_id||task.id" class="list-card"><div><b>{{ task.memberName || task.member_name || '会员' }}</b><p>{{ task.order_no || task.order_no_snapshot || '无关联订单' }} · ¥{{ Number(task.pay_amount ?? task.amount_snapshot ?? 0).toFixed(2) }}</p><small>购买时间 {{ formatDate(task.purchase_time || task.purchase_time_snapshot) }} · {{ task.gender || task.gender_snapshot || '性别未知' }} · {{ labelType(task.visit_type) }}</small><p>{{ maskPhone(task.phone) }}</p><div v-if="Number(task.status)===2" class="visit-result"><p><b>拨号结果：</b>{{ callResultLabel(task.call_result) }}</p><p><b>回访记录：</b>{{ task.record || '未填写' }}</p><small>完成时间 {{ formatDate(task.update_time) }}</small></div></div><div class="card-actions"><button class="outline" @click="call(task.phone, task)">拨号</button><button class="outline" @click="openVisit(task)">{{ Number(task.status)===2 ? '查看/补充' : '填写记录' }}</button></div></div>
        <div v-if="!filteredVisits.length" class="empty">暂无{{ visitFilter }}任务<button class="outline" @click="loadVisits">刷新</button></div>
      </template>
    </div>
    <div v-if="visitDialog" class="mobile-modal"><div class="mobile-modal-card"><h3>填写回访记录</h3><select v-model="visitForm.callResult"><option value="">请选择拨号结果</option><option value="CONNECTED">已接通</option><option value="NO_ANSWER">未接通</option><option value="REJECTED">拒接</option><option value="INVALID">空号</option><option value="UNREACHABLE">无法联系</option></select><textarea v-model="visitForm.record" placeholder="记录客户需求、跟进结果"/><label class="visit-next-follow-up"><span>下次跟进时间（选填）</span><input v-model="visitForm.nextFollowUp" type="datetime-local" aria-label="下次跟进时间"/></label><div class="action-row"><button class="outline" @click="visitDialog=false">取消</button><button class="primary" @click="saveVisit">保存</button></div></div></div>
  </div>
</template>
<script setup>
import { computed, onMounted, ref, reactive, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api/request.js'
import { maskPhone } from '../utils/format.js'
import { useAppStore } from '../stores/app.js'
const router = useRouter()
const app = useAppStore()
const visits = ref([])
const birthdayMembers = ref([])
const visitTabs = ['待回访', '已完成', '生日提醒']
const visitFilter = ref('待回访')
const visitDialog = ref(false)
const selectedVisit = ref(null)
const visitForm = reactive({ record: '', nextFollowUp: '', callResult: '', callStartedAt: '' })
const visitStats = computed(() => { const p = visits.value.filter(t => Number(t.status ?? 1) === 1).length; const d = visits.value.filter(t => Number(t.status ?? 1) === 2).length; const total = p + d; return { pending: p, done: d, rate: total ? Math.round(d / total * 100) : 0 } })
const filteredVisits = computed(() => visits.value.filter(t => { const st = Number(t.status ?? 1); const raw = String(t.visit_type || t.type || ''); const vt = raw.toUpperCase(); if (visitFilter.value === '待回访') return st === 1; if (visitFilter.value === '已完成') return st === 2; if (visitFilter.value === '生日提醒') return vt.includes('BIRTH') || raw.includes('生日'); return true }))
async function loadVisits() { try { visits.value = await api.visits() || [] } catch { visits.value = [] } }
async function loadBirthdayMembers() { try { const d = await api.members({ birthdayFilter: 'month', size: 50 }); birthdayMembers.value = d.records || d || [] } catch { birthdayMembers.value = [] } }
function formatDate(value) { if (!value) return '—'; return String(value).replace('T', ' ').slice(0, 16) }
function labelType(value) { return String(value || '').toUpperCase() === 'PURCHASE_3D' ? '顾客成交3天回访' : (value || '日常回访') }
function callResultLabel(value) { return ({ CONNECTED: '已接通', NO_ANSWER: '未接通', REJECTED: '拒接', INVALID: '空号', UNREACHABLE: '无法联系', ORDER_REFUNDED: '订单已退款' })[String(value || '').toUpperCase()] || '未记录' }
function call(phone, task) { if (!phone) return alert('该会员没有手机号'); if (task) { task.call_started_at = new Date().toISOString(); api.startVisitCall(task.task_id || task.id).catch(() => {}) } if (typeof uni !== 'undefined') uni.makePhoneCall({ phoneNumber: phone }); else window.location.href = `tel:${phone}` }
function openVisit(task) { selectedVisit.value = task; visitForm.record = task.record || ''; visitForm.nextFollowUp = ''; visitForm.callResult = task.call_result || ''; visitForm.callStartedAt = task.call_started_at || ''; visitDialog.value = true }
async function saveVisit() {
  if (!visitForm.callResult) return alert('请选择拨号结果')
  if (!visitForm.record) return alert('请填写回访内容')
  try {
    await api.recordVisit(selectedVisit.value.task_id || selectedVisit.value.id, visitForm.record, visitForm.nextFollowUp || null, visitForm.callResult || null, visitForm.callStartedAt || null)
    visitDialog.value = false
    visits.value = visits.value.map(v => (v.task_id || v.id) === (selectedVisit.value.task_id || selectedVisit.value.id) ? { ...v, status: 2, record: visitForm.record, call_result: visitForm.callResult, update_time: new Date().toISOString() } : v)
  } catch (e) { alert(e.message || '保存失败') }
}
onMounted(() => { loadVisits(); loadBirthdayMembers() })
watch(()=>app.eventVersion,()=>{if(['VISIT_TASK_UPDATED','MEMBER_UPDATED','ORDER_COMPLETED'].includes(app.lastEventType)){loadVisits();loadBirthdayMembers()}})
</script>
