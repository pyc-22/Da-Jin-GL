<template><section class="page">
  <section class="profile-card profile-line"><div class="avatar large">{{ auth.user?.real_name?.slice(0,1) || '员' }}</div><div><h2>{{ auth.user?.real_name || auth.user?.username }}</h2><p>{{ auth.role }} · {{ auth.user?.store_name || '默认门店' }}</p></div></section>
  <div class="profile-shortcuts"><button v-for="item in shortcuts" :key="item.label" class="entry" @click="router.push(item.path)"><span class="entry-symbol">{{ item.symbol }}</span><span class="entry-copy"><b>{{ item.label }}</b><small>{{ item.description }}</small></span><span>›</span></button></div>
  <div class="settings entry-list">
    <button v-if="isManager && auth.can('stock:check:approve')" class="entry" @click="$emit('go','approval')"><span class="entry-main"><i v-if="pendingCount" class="entry-badge">{{ pendingCount }}</i>审批中心</span><span class="entry-sub">{{ pendingCount ? `${pendingCount} 条待审批` : '暂无待审批' }} ›</span></button>
    <button v-else-if="!isManager && auth.can('report:view')" class="entry" @click="$emit('go','performance')"><span class="entry-main">个人业绩</span><span class="entry-sub">本月销售与提成 ›</span></button>
  </div>
  <section class="profile-card qr-card"><template v-if="registrationUrl"><canvas ref="qrCanvas" width="200" height="200"></canvas><p class="qr-title">我的专属二维码</p><small class="muted">客户扫码登记会员并绑定专属顾问</small><button class="outline" @click="saveQr">保存二维码</button></template><template v-else><p class="qr-title">会员登记二维码</p><small class="muted">当前环境未配置会员登记地址，请联系管理员配置后使用。</small></template></section>
  <div class="settings"><button @click="pwDialog=true">修改密码 <span>›</span></button><button @click="openNotify">消息设置 <span>›</span></button><button @click="showVersion">关于与版本 <span>v1.0.0-rc.3 ›</span></button></div><button class="danger full" @click="logout">退出登录</button>
  <div v-if="pwDialog" class="mobile-modal"><div class="mobile-modal-card"><h3>修改密码</h3><label class="form-label">旧密码<input v-model="pwForm.oldPassword" type="password" autocomplete="current-password"/></label><label class="form-label">新密码<input v-model="pwForm.newPassword" type="password" autocomplete="new-password" placeholder="至少6位"/></label><label class="form-label">确认新密码<input v-model="pwForm.confirm" type="password" autocomplete="new-password"/></label><p v-if="pwError" class="error">{{ pwError }}</p><p v-if="pwOk" class="success-text">{{ pwOk }}</p><div class="action-row"><button class="outline" @click="closePw">取消</button><button class="primary" :disabled="pwSubmitting" @click="submitPw">{{ pwSubmitting ? '提交中...' : '确认修改' }}</button></div></div></div><MessageSettings v-if="notifyDialog" @close="notifyDialog=false" />
</section></template>
<script setup>
import { useToast } from '../composables/useToast.js'
const { toast } = useToast()

import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import QRCode from 'qrcode'
import { useAuthStore } from '../stores/auth.js'
import { useAppStore } from '../stores/app.js'
import { api } from '../api/request.js'
import MessageSettings from './MessageSettings.vue'
defineEmits(['go'])
const router=useRouter(); const auth=useAuthStore(); const app=useAppStore()
const isManager=computed(()=>auth.role==='MANAGER'||auth.role==='ADMIN')
const shortcuts=computed(()=>[
 {symbol:'班',label:'交班结算',description:'当班收银核对与交接',path:'/shift',permission:'shift:confirm'},
 {symbol:'价',label:'金价设置 / 价格变更日志',description:'定价配置与价格变更记录',path:'/gold-settings',permission:'gold:manage',admin:true},
 {symbol:'存',label:'客存金台账',description:'寄存金料与取回记录',path:'/manager/deposit',permission:'processing:view'},
 {symbol:'报',label:'经营日报',description:'每日营业、回收与加工',path:'/manager/daily',permission:'report:view:all'},
 {symbol:'表',label:'经营报表',description:'销售业绩与提成明细',path:'/report',permission:'report:view'},
 {symbol:'访',label:'客户回访',description:'客户跟进与回访任务',path:'/visits',permission:'member:follow'},
 {symbol:'生',label:'生日提醒',description:'查看会员生日',path:'/sales/birthday',permission:'member:view'},
 {symbol:'素',label:'活动素材库',description:'朋友圈文案与活动海报',path:'/activity',permission:'member:follow'}
].filter(item=>(!item.admin || auth.role==='ADMIN') && auth.can(item.permission)))
const pendingCount=computed(()=>app.approvals.length)
const pwDialog=ref(false); const pwSubmitting=ref(false); const pwError=ref(''); const pwOk=ref(''); const pwForm=reactive({oldPassword:'',newPassword:'',confirm:''})
const notifyDialog=ref(false)
function closePw(){pwDialog.value=false;pwError.value='';pwOk.value='';pwForm.oldPassword='';pwForm.newPassword='';pwForm.confirm=''}
async function submitPw(){pwError.value='';pwOk.value='';if(!pwForm.oldPassword||!pwForm.newPassword)return pwError.value='请填写完整';if(pwForm.newPassword.length<6)return pwError.value='新密码至少6位';if(pwForm.newPassword!==pwForm.confirm)return pwError.value='两次输入的新密码不一致';pwSubmitting.value=true;try{await api.changePassword({oldPassword:pwForm.oldPassword,newPassword:pwForm.newPassword});pwOk.value='密码修改成功，请重新登录';setTimeout(()=>{closePw();auth.logout();router.replace('/login')},1200)}catch(e){pwError.value=e?.response?.status===401002?'旧密码错误，请重新输入':(e.message||'修改失败')}finally{pwSubmitting.value=false}}
function showVersion(){toast('打金店移动端 1.0.0-rc.3\n请通过门店管理员获取更新安装包，覆盖安装以保留本账号草稿。')}
function openNotify(){notifyDialog.value=true}

function logout(){
  if (!window.confirm('确认退出登录？本账号的入库和盘点草稿会保留，待同步入库单请使用原账号登录后同步。')) return
  auth.logout();router.replace('/login')
}
const qrCanvas=ref(null); const qrDataUrl=ref('')
const registrationUrl = computed(() => String(import.meta.env.VITE_MEMBER_REGISTER_URL || '').trim())
onMounted(async()=>{
  if (!registrationUrl.value) return
  try{
    const separator = registrationUrl.value.includes('?') ? '&' : '?'
    const text=`${registrationUrl.value}${separator}salesId=${encodeURIComponent(auth.user?.user_id||'')}&storeId=${encodeURIComponent(auth.user?.store_id||auth.user?.storeId||'')}`
    await QRCode.toCanvas(qrCanvas.value,text,{width:200,margin:1,color:{dark:'#1a1a1a',light:'#ffffff'}})
    qrDataUrl.value=qrCanvas.value.toDataURL('image/png')
  }catch{ if(qrCanvas.value) qrCanvas.value.style.display='none' }
})
function saveQr(){ if(!qrDataUrl.value)return; const a=document.createElement('a'); a.href=qrDataUrl.value; a.download='sales-qr.png'; a.click() }
</script>
<style scoped>
.entry-list{margin-bottom:var(--s-3)}
.entry{font-size:var(--f-sm)}
.entry-main{display:flex;align-items:center;gap:var(--s-2);font-weight:600}
.entry-sub{color:var(--ink-3);font-size:var(--f-xs)}
.entry-badge{font-style:normal;background:var(--err);color:var(--card);border-radius:999px;min-width:20px;height:20px;display:inline-grid;place-items:center;font-size:var(--f-xs);padding:0 6px}
</style>
