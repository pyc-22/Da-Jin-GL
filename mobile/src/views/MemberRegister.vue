<template>
  <div class="register-page"><main class="register-card"><h1>会员登记</h1><p class="muted">填写信息后提交，店员会为您建立会员档案。</p><form @submit.prevent="submit"><label>姓名<input v-model.trim="form.name" required placeholder="请输入姓名" /></label><label>手机号<input v-model.trim="form.phone" required inputmode="tel" maxlength="11" placeholder="请输入手机号" /></label><label>性别<select v-model="form.gender"><option value="">未填写</option><option value="女">女</option><option value="男">男</option></select></label><label>生日<input v-model="form.birthday" type="date" /></label><p v-if="error" class="error">{{ error }}</p><p v-if="done" class="success">登记成功，感谢您的填写。</p><button class="primary full" :disabled="saving || done">{{ saving ? '提交中...' : '提交登记' }}</button></form></main></div>
</template>
<script setup>
import { reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../api/request.js'
const route=useRoute();const form=reactive({name:'',phone:'',gender:'',birthday:''});const saving=ref(false);const done=ref(false);const error=ref('')
async function submit(){error.value='';if(!route.query.salesId||!route.query.storeId)return error.value='登记链接无效，请联系店员重新获取';if(!/^1[3-9]\d{9}$/.test(form.phone))return error.value='请输入正确的手机号';saving.value=true;try{await api.memberRegister({...form,salesId:Number(route.query.salesId),storeId:Number(route.query.storeId)});done.value=true}catch(e){error.value=e?.message||'登记失败，请稍后重试'}finally{saving.value=false}}
</script>
<style scoped>
.register-page{min-height:100dvh;background:var(--bg);padding:24px 16px;box-sizing:border-box}.register-card{max-width:480px;margin:0 auto;background:var(--card);border:1px solid var(--line);border-radius:var(--r-lg);padding:24px}.register-card h1{margin:0 0 8px;color:var(--ink)}.register-card label{display:block;margin:16px 0 0;font-size:13px;color:var(--ink-2)}.register-card input,.register-card select{display:block;width:100%;box-sizing:border-box;min-height:46px;margin-top:6px;padding:0 12px;border:1px solid var(--line);border-radius:var(--r-md);background:var(--card)}.register-card .success{color:var(--ok)}
</style>
