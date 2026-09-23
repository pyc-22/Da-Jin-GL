<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'
const router = useRouter(); const auth = useAuthStore(); const loading = ref(false)
const form = reactive({ username: '', password: '', clientType: 'ADMIN_WEB' })
async function submit() { loading.value = true; try { await auth.login(form); router.push('/dashboard') } catch (e) { ElMessage.error(e.message || '登录失败') } finally { loading.value = false } }
</script>
<template><main class="login-page"><section class="login-panel"><div class="brand-mark">DAJIN</div><h1>打金店管理中台</h1><p>门店经营、库存与销售数据，一站式掌握</p><el-form :model="form" @submit.prevent="submit"><el-form-item><el-input v-model="form.username" size="large" placeholder="用户名" /></el-form-item><el-form-item><el-input v-model="form.password" size="large" type="password" show-password placeholder="密码" @keyup.enter="submit" /></el-form-item><el-button type="primary" size="large" class="login-btn" :loading="loading" @click="submit">登录</el-button></el-form></section></main></template>
