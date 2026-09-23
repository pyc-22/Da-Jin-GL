import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import Login from './views/Login.vue'
import RoleHome from './views/RoleHome.vue'
import MemberDetail from './views/MemberDetail.vue'
import ActivityLibrary from './views/ActivityLibrary.vue'
import InventoryOverview from './views/InventoryOverview.vue'
import Notifications from './views/Notifications.vue'
import GoodsSearch from './views/GoodsSearch.vue'
import GoodsDetail from './views/GoodsDetail.vue'
import Shift from './views/Shift.vue'
import VisitsPage from './views/VisitsPage.vue'
import InboundCreate from './views/InboundCreate.vue'
import InboundScan from './views/InboundScan.vue'
import InboundDetail from './views/InboundDetail.vue'
import InboundHistory from './views/InboundHistory.vue'
import StockCheckCreate from './views/StockCheckCreate.vue'
import StockCheckScan from './views/StockCheckScan.vue'
import StockCheckHistory from './views/StockCheckHistory.vue'
import StockCheckDetail from './views/StockCheckDetail.vue'
import Forbidden from './views/Forbidden.vue'
import ReportDashboard from './views/ReportDashboard.vue'
import ProcessingOrders from './views/ProcessingOrders.vue'
import PendingTasks from './views/PendingTasks.vue'
import DepositLedger from './views/DepositLedger.vue'
import DailyReport from './views/DailyReport.vue'
import SalesCalculator from './views/SalesCalculator.vue'
import RecycleCreate from './views/RecycleCreate.vue'
import BirthdayReminder from './views/BirthdayReminder.vue'
import { permissionForSection } from './config/roles.js'
import { useAuthStore } from './stores/auth.js'
import { permission } from './directives/permission.js'
import './styles/app.css'
import { isNativeApp, setupNativeNavigation } from './utils/nativeDevice.js'

const routes = [
  { path: '/login', component: Login },
  { path: '/forbidden', component: Forbidden, meta: { auth: true } },
  { path: '/member/:id', component: MemberDetail, meta: { auth: true } },
  { path: '/activity', component: ActivityLibrary, meta: { auth: true, permission: 'member:follow' } },
  { path: '/inventory', component: InventoryOverview, meta: { auth: true, permission: 'stock:view' } },
  { path: '/notifications', component: Notifications, meta: { auth: true, permission: 'notification:view' } },
  { path: '/goods/search', component: GoodsSearch, meta: { auth: true, permission: 'goods:search' } },
  { path: '/goods/:id', component: GoodsDetail, meta: { auth: true, permission: 'goods:search' } },
  { path: '/shift', component: Shift, meta: { auth: true, permission: 'shift:confirm' } },
  { path: '/visits', component: VisitsPage, meta: { auth: true, permission: 'member:follow' } },
  { path: '/inbound/create', component: InboundCreate, meta: { auth: true, permission: 'stock:inbound:create' } },
  { path: '/inbound/scan', component: InboundScan, meta: { auth: true, permission: 'stock:inbound:create' } },
  { path: '/inbound/detail/:id', component: InboundDetail, meta: { auth: true, permission: 'stock:inbound:create' } },
  { path: '/inbound/history', component: InboundHistory, meta: { auth: true, permission: 'stock:inbound:create' } },
  { path: '/stock-check/create', component: StockCheckCreate, meta: { auth: true, permission: 'stock:check:create' } },
  { path: '/stock-check/scan', component: StockCheckScan, meta: { auth: true, permission: 'stock:check:create' } },
  { path: '/stock-check/history', component: StockCheckHistory, meta: { auth: true, permission: 'stock:check:view' } },
  { path: '/stock-check/detail/:id', component: StockCheckDetail, meta: { auth: true, permission: 'stock:check:view' } },
  { path: '/report', component: ReportDashboard, meta: { auth: true, permission: 'report:view' } },
  { path: '/report/:kind', component: ReportDashboard, meta: { auth: true, permission: 'report:view' } },
  { path: '/processing', component: ProcessingOrders, meta: { auth: true, permission: 'processing:view' } },
  { path: '/todo', component: PendingTasks, meta: { auth: true, permission: 'processing:view' } },
  { path: '/manager/processing', redirect: '/processing' },
  { path: '/manager/deposit', component: DepositLedger, meta: { auth: true, permission: 'processing:view' } },
  { path: '/manager/daily', component: DailyReport, meta: { auth: true, permission: 'report:view:all' } },
  { path: '/sales/calc', component: SalesCalculator, meta: { auth: true, permission: 'order:create' } },
  { path: '/sales/recycle', component: RecycleCreate, meta: { auth: true, permission: 'recycle:view' } },
  { path: '/sales/processing-progress', redirect: '/processing' },
  { path: '/sales/birthday', component: BirthdayReminder, meta: { auth: true, permission: 'member:view' } },
  { path: '/:role/:section?', component: RoleHome, meta: { auth: true } },
  { path: '/', redirect: '/login' }
]
const router = createRouter({ history: createWebHistory(), routes })
router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.meta.auth && !auth.token) return '/login'
  if (auth.token && auth.role === 'CASHIER') { auth.logout(); return '/login' }
  if (to.meta.roles && !to.meta.roles.includes(auth.role)) return { path: '/forbidden', query: { from: to.fullPath } }
  if (to.meta.permission && !auth.can(to.meta.permission)) return { path: '/forbidden', query: { from: to.fullPath } }
  if (to.meta.role && auth.role !== to.meta.role && !(to.meta.role === 'MANAGER' && auth.role === 'ADMIN')) return { path: '/forbidden', query: { from: to.fullPath } }
  const requestedRole = String(to.params.role || '').toUpperCase()
  if (requestedRole && requestedRole !== auth.role && !(auth.role === 'ADMIN' && requestedRole === 'MANAGER')) return { path: '/forbidden', query: { from: to.fullPath } }
  const sectionPermission = permissionForSection(String(to.params.section || ''), auth.role)
  if (sectionPermission && !auth.can(sectionPermission)) return { path: '/forbidden', query: { from: to.fullPath } }
  if (to.path === '/login' && auth.token) return `/${auth.role.toLowerCase()}`
})

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.directive('permission', permission)
app.mount('#app')
setupNativeNavigation(router)

if (import.meta.env.PROD && !isNativeApp() && 'serviceWorker' in navigator) {
  window.addEventListener('load', () => navigator.serviceWorker.register('/sw.js'))
}
