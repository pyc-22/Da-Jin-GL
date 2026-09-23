import { createRouter, createWebHashHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
const pages = [
  ['dashboard','仪表盘','Dashboard'], ['goods','商品管理','Goods'], ['gold-price','金价管理','GoldPrice'], ['stock','库存管理','Stock'], ['approval','审批中心','Approval'], ['sales','销售管理','Sales'], ['member','会员管理','Member'], ['visits','回访管理','Visits'], ['staff','人员管理','Staff'], ['commission','提成设置','Commission'], ['finance','财务报表','Finance'], ['system','系统设置','System']
]
const processingPages = [
  ['processing-dashboard', '加工看板', 'dashboard'],
  ['processing-orders', '加工订单', 'orders'],
  ['processing-items', '加工项目', 'items'],
  ['processing-commissions', '加工提成', 'commissions'],
  ['processing-loss', '损耗考核', 'loss']
]
const routes = [{ path: '/login', component: () => import('../views/Login.vue') }, { path: '/', component: () => import('../views/Layout.vue'), redirect: '/dashboard', children: [
  ...pages.map(([path, title, component]) => ({ path, name: title, component: () => import(`../views/${component}.vue`) })),
  ...processingPages.map(([path, title, processingTab]) => ({ path, name: title, component: () => import('../views/Processing.vue'), meta: { processingTab } }))
] }, { path: '/:pathMatch(.*)*', redirect: '/' }]
const router = createRouter({ history: createWebHashHistory(), routes })
router.beforeEach(to => { const auth = useAuthStore(); if (to.path !== '/login' && !auth.loggedIn) return '/login'; if (to.path === '/login' && auth.loggedIn) return '/dashboard' })
export default router
