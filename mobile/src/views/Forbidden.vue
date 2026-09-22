<template>
  <main class="forbidden-page">
    <section class="forbidden-panel">
      <div class="forbidden-icon">!</div>
      <h1>暂无访问权限</h1>
      <p>当前账号未配置此功能权限，请联系管理员调整角色权限后重新登录。</p>
      <button class="primary full" @click="backHome">返回首页</button>
    </section>
  </main>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth.js'
const router = useRouter()
const auth = useAuthStore()
function backHome() { router.replace(`/${auth.role.toLowerCase()}`) }
</script>

<style scoped>
.forbidden-page { min-height:100vh; display:grid; place-items:center; padding:24px; background:var(--bg); }
.forbidden-panel { width:min(100%, 380px); text-align:center; background:#fff; border:1px solid var(--line); border-radius:8px; padding:32px 24px; }
.forbidden-icon { width:52px; height:52px; margin:0 auto 16px; display:grid; place-items:center; border-radius:50%; background:#fff4df; color:var(--gold-deep); font-size:28px; font-weight:700; }
h1 { margin:0 0 10px; font-size:20px; }
p { margin:0 0 24px; color:var(--ink-2); font-size:14px; line-height:1.7; }
</style>
