<template>
  <div class="shell"><DesignHeader title="会员生日提醒" :back="true" @back="router.back()"><button class="icon-btn" @click="load">刷新</button></DesignHeader><main class="content page"><div class="banner"><b>未来 10 天生日会员</b><span> · 来自当前账号负责的会员</span></div><EmptyState v-if="loading" title="加载中..." /><EmptyState v-else-if="error" title=""><span>{{ error }}</span><button class="outline" @click="load">重试</button></EmptyState><EmptyState v-else-if="!members.length" title="未来 10 天暂无生日会员" /><article v-for="member in members" :key="member.member_id||member.id" class="list-card birthday-card"><div class="avatar">{{ (member.name||'会').slice(0,1) }}</div><div class="birthday-main"><b>{{ member.name || '会员' }}</b><p>{{ member.phone || '-' }} · {{ member.level || member.grade || '普通会员' }}</p><p>生日 {{ birthday(member.birthday) }} · 累计消费 {{ money(member.total_consume) }}</p></div><div class="birthday-actions"><button class="outline" @click="call(member.phone)">拨号</button><button class="primary" @click="bless(member)">祝福</button></div></article><p class="muted small">生日信息仅用于客户回访，请按门店隐私规范使用。</p></main></div>
</template>
<script setup>
import { useToast } from '../composables/useToast.js'
const { toast } = useToast()

import EmptyState from '../components/EmptyState.vue'

import DesignHeader from '../components/DesignHeader.vue'

import { onMounted, ref } from 'vue'; import { useRouter } from 'vue-router'; import { api } from '../api/request.js'
const router=useRouter(); const members=ref([]); const loading=ref(false); const error=ref(''); const money=v=>`¥${Number(v||0).toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2})}`; const birthday=v=>String(v||'—').slice(5,10)||'—'
async function load(){loading.value=true;error.value='';try{const now=new Date();const end=new Date(now);end.setDate(now.getDate()+10);const md=d=>`${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`;const d=await api.members({birthdayFilter:'window',birthdayFrom:md(now),birthdayTo:md(end),birthdayOrder:'asc',size:100});const rows=(Array.isArray(d)?d:(d?.records||[])).filter(m=>m.birthday);rows.sort((a,b)=>String(a.birthday).slice(5).localeCompare(String(b.birthday).slice(5)));members.value=rows}catch(e){error.value=e?.message||'生日提醒加载失败'}finally{loading.value=false}}
function call(phone){if(!phone)return;if(typeof uni!=='undefined')uni.makePhoneCall({phoneNumber:String(phone)});else window.location.href=`tel:${phone}`}
async function bless(member){const text=`${member.name||'您好'}，祝您生日快乐！打金店为您准备了生日专属礼遇，欢迎到店体验。`;try{await navigator.clipboard.writeText(text);toast('生日祝福文案已复制')}catch{toast(text)}}
onMounted(load)
</script>
<style scoped>
.banner{background:var(--gold-soft);border:1px solid var(--gold-line);border-radius:var(--r-md);padding:12px;color:var(--gold-deep);font-size:13px;margin-bottom:12px}.birthday-card{align-items:flex-start}.birthday-card .avatar{width:42px;height:42px}.birthday-main{flex:1;min-width:0}.birthday-main p{margin:4px 0 0;color:var(--ink-2);font-size:12px;overflow-wrap:anywhere;line-height:1.45}.birthday-actions{display:flex;flex-direction:column;gap:6px;flex:0 0 auto}.birthday-actions button{min-width:54px;min-height:var(--tap);padding:0 8px}
</style>
