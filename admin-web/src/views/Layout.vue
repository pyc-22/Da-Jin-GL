<script setup>
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'
import { useAppStore } from '../stores/app'
import { goldApi, systemApi } from '../api/modules'
import { formatTime } from '../utils/format'
import { LayoutDashboard, Package, CircleDollarSign, Boxes, ClipboardCheck, Receipt, Users, UserCog, Percent, BarChart3, Settings, Bell, LogOut, ChevronDown, ClipboardList, Wrench, BadgeDollarSign, PhoneCall, Scale } from 'lucide-vue-next'
const auth = useAuthStore(); const app = useAppStore(); const router = useRouter(); const route = useRoute(); const connected = ref(true); let timer
const menus = [
  ['dashboard','仪表盘',LayoutDashboard],['goods','商品',Package],['gold-price','金价',CircleDollarSign],['stock','库存',Boxes],['approval','审批',ClipboardCheck],['sales','销售',Receipt],['member','会员',Users],['visits','回访',PhoneCall],['staff','人员',UserCog],['commission','提成',Percent],['finance','报表',BarChart3],['system','系统',Settings]
]
const processingMenus = [
  ['processing-dashboard', '加工看板', LayoutDashboard], ['processing-orders', '加工订单', ClipboardList], ['processing-items', '加工项目', Wrench], ['processing-commissions', '加工提成', BadgeDollarSign], ['processing-loss', '损耗考核', Scale]
]
const visibleMenus = computed(() => auth.role === 'ADMIN' ? menus : menus.filter(m => m[0] !== 'system'))
const processingExpanded = computed(() => route.path.startsWith('/processing-'))
const pageTitle = computed(() => [...menus, ...processingMenus].find(x => x[0] === route.path.slice(1))?.[1] || '仪表盘')
async function loadContext() {
  try {
    const [store, gold, channels] = await Promise.all([systemApi.store(), goldApi.current(), systemApi.channels()])
    app.setStore(store)
    app.setGold(gold)
    app.setPaymentChannels(channels)
    connected.value = true
  } catch (e) { connected.value = false }
}
async function loadPaymentChannels() {
  try { app.setPaymentChannels(await systemApi.channels()) } catch { /* periodic context refresh will retry */ }
}
function logout() { auth.logout(); router.push('/login'); ElMessage.success('已退出登录') }
watch(() => app.eventVersion, () => { if (app.lastEventType === 'PAY_CHANNELS_UPDATED') loadPaymentChannels() })
onMounted(() => { loadContext(); app.connectWs(auth.token); timer = setInterval(loadContext, 30000) }); onBeforeUnmount(() => { clearInterval(timer); app.disconnectWs() })
</script>
<template><div class="admin-shell"><aside class="sidebar"><div class="logo"><span>DAJIN</span><small>管理中台</small></div><nav><router-link v-for="m in visibleMenus" :key="m[0]" :to="`/${m[0]}`" class="nav-item"><component :is="m[2]" :size="18" /><span>{{m[1]}}</span><i v-if="m[0]==='approval' && app.notifications" class="badge">{{app.notifications}}</i></router-link><router-link to="/processing-dashboard" class="nav-item" :class="{ active: processingExpanded }"><Wrench :size="18" /><span>加工管理</span><ChevronDown :size="15" class="nav-arrow" /></router-link><div class="processing-nav" :class="{ open: processingExpanded }"><router-link v-for="m in processingMenus" :key="m[0]" :to="`/${m[0]}`" class="nav-item sub-nav"><component :is="m[2]" :size="16" /><span>{{m[1]}}</span></router-link></div></nav><div class="sidebar-foot">v1.0.0-rc.1 · 单店版</div></aside><section class="workspace"><header class="topbar"><div><strong>{{app.store?.store_name || '默认门店'}}</strong><span class="online-dot" :class="{off:!connected}"></span><span class="muted">{{connected?'在线':'离线'}}</span></div><div class="top-actions"><span class="gold-mini" v-if="app.gold?.length">足金 {{app.gold.find(g => g.price_type==='足金')?.price || '--'}} /g</span><el-button text><Bell :size="17" /> 通知</el-button><span class="user-name">{{auth.user?.real_name || auth.user?.username}}</span><el-button text @click="logout"><LogOut :size="16" />退出</el-button></div></header><main class="content"><div class="content-heading"><div><div class="eyebrow">OPERATIONS</div><h2>{{pageTitle}}</h2></div><span class="muted">{{formatTime(new Date()).slice(0, 10)}}</span></div><router-view /></main></section></div></template>
<style scoped>
.processing-nav { display: none; }
.processing-nav.open { display: block; }
.sub-nav { padding-left: 34px; font-size: 13px; min-height: 38px; }
.nav-arrow { margin-left: auto; }
</style>
