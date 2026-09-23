<template><router-view :key="`${auth.user?.store_id ?? auth.user?.storeId}:${auth.user?.user_id ?? auth.user?.userId}`" /></template>
<script setup>
import { onMounted, onUnmounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from './stores/auth.js'
import { useAppStore } from './stores/app.js'
import { permissionForSection } from './config/roles.js'
const router = useRouter(); const auth = useAuthStore()
const app = useAppStore()
let hiddenAt = 0
async function onVisibility() {
  if (document.visibilityState === 'hidden') { hiddenAt = Date.now(); return }
  if (!hiddenAt) return
  hiddenAt = 0
  if (!auth.token) return
  const token = auth.token
  app.checkConnectivity()
  app.connectWs(auth.token)
  try { await auth.refreshSession(); app.eventVersion++ } catch (e) {
    if (auth.token === token && e?.response?.status === 401) { auth.logout(); router.replace('/login') }
  }
}
function syncSession(token) {
  app.stopHeartbeat(); app.closeWs()
  app.gold = []; app.approvals = []; app.unread = 0; app.dashboard = null; app.pendingInboundCount = 0
  if (token) { app.refreshLocalPendingInbounds(); app.loadPendingInbounds(); app.startHeartbeat(); app.connectWs(token) }
}
onMounted(() => { document.addEventListener('visibilitychange', onVisibility); syncSession(auth.token) })
onUnmounted(() => { document.removeEventListener('visibilitychange', onVisibility); app.stopHeartbeat(); app.closeWs() })
watch(() => auth.token, syncSession)
watch(() => auth.permissions.join('|'), () => {
  const current = router.currentRoute.value
  const required = current.meta.permission || permissionForSection(String(current.params.section || ''), auth.role)
  if (required && !auth.can(required)) router.replace({ path: '/forbidden', query: { from: current.fullPath } })
})
</script>
