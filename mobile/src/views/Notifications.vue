<template>
  <div class="shell">
    <header class="topbar"><button class="back" @click="router.back()">‹</button><strong>消息通知</strong><span></span></header>
    <main class="content page">
      <div v-for="n in notices" :key="n.id" class="list-card" :class="{ unread: !n.read }" @click="markRead(n)">
        <div><b>{{ n.title }}</b><p>{{ n.text }}</p><small>{{ n.time }}</small></div><span>{{ n.read ? '已读' : '未读' }}</span>
      </div>
      <div v-if="!notices.length" class="empty">暂无消息</div>
    </main>
    <nav class="tabbar">
      <button v-for="tab in tabs" :key="tab.key" :class="{ active: tab.key === 'notifications' }" @click="openTab(tab.key)"><span>{{ TAB_ICONS[tab.key] || '•' }}</span>{{ tab.label }}</button>
    </nav>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api/request.js'
import { tabsForRole } from '../config/roles.js'
import { useAuthStore } from '../stores/auth.js'

const router = useRouter()
const auth = useAuthStore()
const notices = ref([])
const isManager = computed(() => ['ADMIN', 'MANAGER'].includes(auth.role))
const TAB_ICONS = { dashboard: '⌂', home: '⌂', report: '↗', performance: '↗', member: '👤', members: '👤', notifications: '●', profile: '⚙' }
const tabs = computed(() => tabsForRole(auth.role, auth.permissions))

const actionLabel = value => ({
  REMIND: '超期/库存提醒',
  PROCESSING_READY: '加工完成提醒',
  PROCESSING_LOSS_OVER: '损耗超标预警',
  APPROVAL_CREATED: '审批提醒',
  REMINDER_REFRESH: '提醒'
}[String(value || '').toUpperCase()] || '')

const fmtTime = value => {
  if (!value) return ''
  const time = String(value).replace(' ', 'T')
  const source = /[zZ]|[+-]\d{2}:?\d{2}$/.test(time) ? time : `${time}Z`
  const date = new Date(source)
  if (Number.isNaN(date.getTime())) return String(value).slice(0, 16).replace('T', ' ')
  return new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(date).replace(/\//g, '-')
}

function openTab(key) {
  if (key === 'notifications') return
  if (key === 'dashboard' || key === 'home') return router.push(isManager.value ? '/manager/dashboard' : '/sales/home')
  if (key === 'report' || key === 'performance') return router.push('/report')
  if (key === 'member' || key === 'members') return router.push(isManager.value ? '/manager/member' : '/sales/members')
  if (key === 'profile') return router.push(isManager.value ? '/manager/profile' : '/sales/profile')
}

onMounted(async () => {
  try {
    const server = await api.notifications() || []
    notices.value = server.map(n => {
      const read = n.read === true || String(n.action).toUpperCase() === 'READ'
      const rawTitle = String(n.title || '').trim()
      const title = rawTitle && rawTitle.toUpperCase() !== 'READ' ? rawTitle : (actionLabel(n.action) || '通知')
      return { id: n.notification_id, title, text: n.content, time: fmtTime(n.create_time), read }
    })
  } catch {
    notices.value = []
  }
})

async function markRead(notice) {
  if (notice.read) return
  notice.read = true
  try { await api.markNotificationRead(notice.id) } catch {}
}
</script>
