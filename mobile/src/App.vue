<template><router-view v-slot="{ Component }"><Transition name="page" mode="out-in"><component :is="Component" :key="`${auth.user?.store_id ?? auth.user?.storeId}:${auth.user?.user_id ?? auth.user?.userId}`" /></Transition></router-view><Toast /></template>
<script setup>
import Toast from './components/Toast.vue'
import { onMounted, onUnmounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from './stores/auth.js'
import { useAppStore } from './stores/app.js'
import { permissionForSection } from './config/roles.js'
import { useMessageRuntime } from './composables/useMessageRuntime.js'
const router = useRouter(); const auth = useAuthStore()
const app = useAppStore()
useMessageRuntime(router)
function syncSession(token) {
  app.stopHeartbeat(); app.closeWs()
  app.gold = []; app.approvals = []; app.unread = 0; app.dashboard = null; app.pendingInboundCount = 0
  if (token) { app.refreshLocalPendingInbounds(); app.loadPendingInbounds(); app.startHeartbeat(); app.connectWs(token) }
}
onMounted(() => syncSession(auth.token))
onUnmounted(() => { app.stopHeartbeat(); app.closeWs() })
watch(() => auth.token, syncSession)
watch(() => auth.permissions.join('|'), () => {
  const current = router.currentRoute.value
  const required = current.meta.permission || permissionForSection(String(current.params.section || ''), auth.role)
  if (required && !auth.can(required)) router.replace({ path: '/forbidden', query: { from: current.fullPath } })
})
</script>
