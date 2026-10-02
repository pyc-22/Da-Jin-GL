<template>
  <main class="forbidden-page">
    <section class="forbidden-panel">
      <div class="forbidden-icon">!</div>
      <DesignHeader title="暂无访问权限" />
      <p>当前账号未配置此功能权限，请联系管理员调整角色权限后重新登录。</p>
      <button class="primary full" @click="backHome">返回首页</button>
    </section>
  </main>
</template>

<script setup>
import DesignHeader from '../components/DesignHeader.vue'

import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth.js'
const router = useRouter()
const auth = useAuthStore()
function backHome() { router.replace(`/${auth.role.toLowerCase()}`) }
</script>

<style scoped>
.forbidden-page { min-height:100dvh; display:grid; place-items:center; padding:24px; background:var(--bg); }
.forbidden-panel { width:min(100%, 380px); text-align:center; background:var(--card); border:1px solid var(--line); border-radius:var(--r-md); padding:32px 24px; }
.forbidden-icon { width:52px; height:52px; margin:0 auto 16px; display:grid; place-items:center; border-radius:50%; background:var(--gold-soft); color:var(--gold-deep); font-size:28px; font-weight:700; }
h1 { margin:0 0 10px; font-size:20px; }
p { margin:0 0 24px; color:var(--ink-2); font-size:14px; line-height:1.7; }
</style>
