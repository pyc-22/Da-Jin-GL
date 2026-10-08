<script setup>
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'
import { useAppStore } from '../stores/app'
import { goldApi, notificationApi, systemApi } from '../api/modules'
import { formatTime } from '../utils/format'
import { pagePermissions, firstAllowedPage, canAccessPage } from '../permissions'
import { LayoutDashboard, Package, CircleDollarSign, Boxes, ClipboardCheck, Receipt, Users, UserCog, Percent, BarChart3, Settings, Bell, LogOut, ChevronDown, ClipboardList, Wrench, BadgeDollarSign, PhoneCall, Scale } from 'lucide-vue-next'
const auth = useAuthStore(); const app = useAppStore(); const router = useRouter(); const route = useRoute(); const connected = ref(true); let timer
const notificationItems = ref([])
const notificationOpen = ref(false)
const unreadCount = computed(() => notificationItems.value.filter(item => item.action !== 'READ').length)
const menus = [
  ['dashboard','仪表盘',LayoutDashboard],['goods','商品',Package],['gold-price','金价',CircleDollarSign],['stock','库存',Boxes],['approval','审批',ClipboardCheck],['sales','销售',Receipt],['member','会员',Users],['visits','回访',PhoneCall],['staff','人员',UserCog],['commission','提成',Percent],['finance','报表',BarChart3],['system','系统',Settings]
]
const processingMenus = [
  ['processing-dashboard', '加工看板', LayoutDashboard], ['processing-orders', '加工订单', ClipboardList], ['processing-items', '加工项目', Wrench], ['processing-commissions', '加工提成', BadgeDollarSign], ['processing-loss', '损耗考核', Scale]
]
const visibleMenus = computed(() => menus.filter(m => canAccessPage(m[0], code => auth.can(code))))
const visibleProcessingMenus = computed(() => processingMenus.filter(m => canAccessPage(m[0], code => auth.can(code))))
const processingExpanded = computed(() => route.path.startsWith('/processing-'))
const pageTitle = computed(() => [...menus, ...processingMenus].find(x => x[0] === route.path.slice(1))?.[1] || '仪表盘')
async function loadContext() {
  try {
    const [store, gold, channels] = await Promise.all([systemApi.store(), goldApi.current(), systemApi.activeChannels()])
    app.setStore(store)
    app.setGold(gold)
    app.setPaymentChannels(channels)
    connected.value = true
  } catch (e) { connected.value = false }
}
async function loadPaymentChannels() {
  try { app.setPaymentChannels(await systemApi.activeChannels()) } catch { /* periodic context refresh will retry */ }
}
function logout() { auth.logout(); router.push('/login'); ElMessage.success('已退出登录') }
async function loadNotifications() {
  if (!auth.can('notification:view')) return
  try { notificationItems.value = await notificationApi.list() } catch { /* retry on next refresh */ }
}
async function readNotification(item) {
  if (item.action === 'READ') return
  try {
    await notificationApi.read(item.notification_id)
    item.action = 'READ'
  } catch (error) { ElMessage.error(error?.message || '标记已读失败') }
}
watch(() => app.eventVersion, () => {
  if (app.lastEventType === 'PAY_CHANNELS_UPDATED') loadPaymentChannels()
  if (app.lastEventType === 'BARGAIN_RECORDED' && ['ADMIN', 'MANAGER'].includes(auth.role)) loadNotifications()
  if (['ROLE_PERMISSIONS_UPDATED', 'USER_PERMISSIONS_UPDATED'].includes(app.lastEventType)) {
    auth.refresh(true).then(() => {
      const required = pagePermissions[route.path.slice(1)]
      if (required && !canAccessPage(route.path.slice(1), code => auth.can(code))) router.replace(`/${firstAllowedPage(code => auth.can(code)) || 'no-access'}`)
    }).catch(() => { auth.logout(); router.replace('/login') })
  }
})
onMounted(() => {
  loadContext()
  loadNotifications()
  app.connectWs(auth.token)
  timer = setInterval(() => { loadContext(); loadNotifications() }, 30000)
})
onBeforeUnmount(() => { clearInterval(timer); app.disconnectWs() })
</script>
<template><div class="admin-shell"><aside class="sidebar"><div class="logo"><span>DAJIN</span><small>管理中台</small></div><nav><router-link v-for="m in visibleMenus" :key="m[0]" :to="`/${m[0]}`" class="nav-item"><component :is="m[2]" :size="18" /><span>{{m[1]}}</span><i v-if="m[0]==='approval' && app.notifications" class="badge">{{app.notifications}}</i></router-link><router-link v-if="visibleProcessingMenus.length" :to="`/${visibleProcessingMenus[0][0]}`" class="nav-item" :class="{ active: processingExpanded }"><Wrench :size="18" /><span>加工管理</span><ChevronDown :size="15" class="nav-arrow" /></router-link><div v-if="visibleProcessingMenus.length" class="processing-nav" :class="{ open: processingExpanded }"><router-link v-for="m in visibleProcessingMenus" :key="m[0]" :to="`/${m[0]}`" class="nav-item sub-nav"><component :is="m[2]" :size="16" /><span>{{m[1]}}</span></router-link></div></nav><div class="sidebar-foot">v1.0.0-rc.1 · 单店版</div></aside><section class="workspace"><header class="topbar"><div><strong>{{app.store?.store_name || '默认门店'}}</strong><span class="online-dot" :class="{off:!connected}"></span><span class="muted">{{connected?'在线':'离线'}}</span></div><div class="top-actions"><span class="gold-mini" v-if="app.gold?.length && auth.can('gold:view')">足金 {{app.gold.find(g => g.price_type==='足金')?.price || '--'}} /g</span><el-popover v-if="auth.can('notification:view')" v-model:visible="notificationOpen" trigger="click" placement="bottom-end" :width="380"><template #reference><el-badge :value="unreadCount" :hidden="!unreadCount"><el-button text title="消息通知"><Bell :size="17" /> 通知</el-button></el-badge></template><div class="notification-heading"><strong>消息通知</strong><el-button text size="small" @click="loadNotifications">刷新</el-button></div><div class="notification-list"><div v-if="!notificationItems.length" class="notification-empty">暂无消息</div><button v-for="item in notificationItems" :key="item.notification_id" type="button" class="notification-row" :class="{ unread: item.action !== 'READ' }" @click="readNotification(item)"><span>{{item.content}}</span><small>{{formatTime(item.create_time)}} · {{item.action === 'READ' ? '已读' : '未读'}}</small></button></div></el-popover><span class="user-name">{{auth.user?.real_name || auth.user?.username}}</span><el-button text @click="logout"><LogOut :size="16" />退出</el-button></div></header><main class="content"><div class="content-heading"><div><div class="eyebrow">OPERATIONS</div><h2>{{pageTitle}}</h2></div><span class="muted">{{formatTime(new Date().toISOString()).slice(0, 10)}}</span></div><router-view /></main></section></div></template>
<style scoped>
.processing-nav { display: none; }
.processing-nav.open { display: block; }
.sub-nav { padding-left: 34px; font-size: 13px; min-height: 38px; }
.nav-arrow { margin-left: auto; }
.notification-heading { display: flex; align-items: center; justify-content: space-between; padding-bottom: 8px; border-bottom: 1px solid #e5e9f0; }
.notification-list { max-height: 320px; overflow-y: auto; }
.notification-empty { padding: 24px 8px; color: #8491a3; text-align: center; }
.notification-row { display: grid; width: 100%; gap: 5px; padding: 12px 8px; border: 0; border-bottom: 1px solid #edf0f4; background: #fff; text-align: left; color: #425064; cursor: pointer; line-height: 1.5; }
.notification-row:hover { background: #f7f9fc; }
.notification-row.unread { color: #243044; font-weight: 600; }
.notification-row small { color: #8491a3; font-weight: 400; }
</style>
