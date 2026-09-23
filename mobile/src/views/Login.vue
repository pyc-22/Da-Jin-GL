<template>
  <main class="login-page"><section class="login-panel"><div class="brand-mark">DAJIN</div><h1>打金店移动工作台</h1><p class="muted">店长与销售统一入口 · 前台请使用电脑收银端</p>
    <form @submit.prevent="submit"><label>账号<input v-model.trim="username" autocomplete="username" placeholder="请输入账号" /></label><label>密码<input v-model="password" type="password" autocomplete="current-password" placeholder="请输入密码" /></label><p v-if="error" class="error">{{ error }}</p><p v-if="error && error.includes('锁定')" class="muted small">账号已临时锁定，请稍后再试或联系店长重置</p><button class="primary full" :disabled="loading">{{ loading ? '登录中...' : '登录' }}</button></form>
  </section></main>
</template>
<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth.js'
import { useAppStore } from '../stores/app.js'
const router = useRouter(); const auth = useAuthStore(); const app = useAppStore(); const username = ref(''); const password = ref(''); const loading = ref(false); const error = ref('')
async function submit() { loading.value = true; error.value = ''; try { await auth.login(username.value, password.value); if (auth.role === 'CASHIER') { auth.logout(); error.value = '前台账号请使用电脑收银端，移动端未开放'; return } await app.loadGold();  router.replace(`/${auth.role.toLowerCase()}`) } catch (e) { error.value = e.message || '登录失败' } finally { loading.value = false } }
</script>
