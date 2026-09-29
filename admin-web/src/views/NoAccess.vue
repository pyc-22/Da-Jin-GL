<script setup>
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'
import { firstAllowedPage } from '../permissions'

const router = useRouter()
const auth = useAuthStore()

async function refresh() {
  try {
    await auth.refresh()
    const page = firstAllowedPage(code => auth.can(code))
    if (page) router.replace(`/${page}`)
  } catch (error) { ElMessage.error(error?.message || '权限刷新失败') }
}
function logout() { auth.logout(); router.replace('/login') }
</script>

<template>
  <main class="no-access">
    <h1>当前账号未配置管理端权限</h1>
    <div class="actions"><el-button type="primary" @click="refresh">刷新权限</el-button><el-button @click="logout">退出登录</el-button></div>
  </main>
</template>

<style scoped>
.no-access { max-width: 520px; margin: 15vh auto; padding: 24px; }
h1 { font-size: 20px; font-weight: 600; }
.actions { display: flex; gap: 8px; margin-top: 20px; }
</style>
