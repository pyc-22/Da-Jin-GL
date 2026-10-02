<template>
  <div :class="embedded ? 'messages-section' : 'shell'">
    <DesignHeader v-if="!embedded" title="消息" :back="true" @back="router.back()"><button class="text-action" @click="markAll">全部已读</button></DesignHeader>
    <main class="content page"><div v-if="embedded" class="summary-line"><span class="muted">消息通知</span><button class="text-action" @click="markAll">全部已读</button></div>
      <button type="button" v-for="n in notices" :key="n.id" class="list-card" :class="{ unread: !n.read }" @click="markRead(n)"><span class="entry-symbol" :class="{'message-unread':!n.read}">{{ n.title.slice(0,1) }}</span>
        <div><b>{{ n.title }}</b><p>{{ n.text }}</p><small>{{ n.time }}</small></div><StatusPill :tone="n.read ? 'muted' : 'gold'">{{ n.read ? '已读' : '未读' }}</StatusPill>
      </button>
      <p v-if="error" role="alert" class="error">{{ error }}</p><EmptyState v-if="!notices.length" title="暂无消息" />
    </main>
    <BottomNav v-if="!embedded" active="notifications" />
  </div>
</template>

<script setup>
import DesignHeader from '../components/DesignHeader.vue'
import BottomNav from '../components/BottomNav.vue'
import StatusPill from '../components/StatusPill.vue'
import EmptyState from '../components/EmptyState.vue'
const props=defineProps({embedded:Boolean})

import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api/request.js'
import { useAuthStore } from '../stores/auth.js'

const router = useRouter()
const auth = useAuthStore()
const notices = ref([]), error=ref('')

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
    error.value = '消息加载失败，请稍后刷新'
    notices.value = []
  }
})

async function markRead(notice) {
  if (notice.read) return
  try { await api.markNotificationRead(notice.id); notice.read = true } catch { error.value='标记已读失败，请重试' }
}
async function markAll(){await Promise.all(notices.value.filter(n=>!n.read).map(markRead))}
</script>
