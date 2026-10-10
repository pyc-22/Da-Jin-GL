<template>
  <div :class="embedded ? 'messages-section' : 'shell'">
    <DesignHeader v-if="!embedded" title="消息" :back="true" @back="router.back()"><button class="text-action" @click="markAll">全部已读</button><button class="text-action" @click="clearAll">清空</button></DesignHeader>
    <main class="content page"><div v-if="embedded" class="summary-line"><span class="muted">消息通知</span><span><button class="text-action" @click="markAll">全部已读</button><button class="text-action" @click="clearAll">清空</button></span></div>
      <div v-for="n in notices" :key="n.id" class="notice-swipe" @touchstart="touchStart(n.id, $event)" @touchmove="touchMove(n.id, $event)" @touchend="touchEnd(n.id)">
        <button type="button" class="notice-delete" aria-label="删除消息" @click.stop="removeNotice(n)">删除</button>
        <button type="button" class="list-card" :class="{ unread: !n.read }" :style="{ transform: `translateX(${offsetFor(n.id)}px)` }" @click="openNotice(n)"><span class="entry-symbol" :class="{'message-unread':!n.read}">{{ n.title.slice(0,1) }}</span>
          <div><b>{{ n.title }}</b><p>{{ n.text }}</p><small>{{ n.time }}</small></div><StatusPill :tone="n.read ? 'muted' : 'gold'">{{ n.read ? '已读' : '未读' }}</StatusPill>
        </button>
      </div>
      <p v-if="error || messages.error" role="alert" class="error">{{ error || messages.error }}</p><EmptyState v-if="!notices.length && !messages.loading" title="暂无消息" />
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

import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth.js'
import { useMessagesStore } from '../stores/messages.js'
import { navigateNotification } from '../utils/notificationNavigation.js'

const router = useRouter()
const auth = useAuthStore()
const messages = useMessagesStore(), error=ref('')
const notices = computed(() => messages.rows.map(n => {
  const read = n.read === true || String(n.action).toUpperCase() === 'READ'
  const rawTitle = String(n.title || '').trim()
  const title = rawTitle && rawTitle.toUpperCase() !== 'READ' ? rawTitle : (actionLabel(n.action) || '通知')
  return { id: n.notification_id ?? n.id ?? n.log_id, title, text: n.content, time: fmtTime(n.create_time), read }
}))

const actionLabel = value => ({
  REMIND: '超期/库存提醒',
  PROCESSING_READY: '加工完成提醒',
  PROCESSING_LOSS_OVER: '损耗率超标预警',
  APPROVAL_CREATED: '审批提醒',
  REMINDER_REFRESH: '提醒'
}[String(value || '').toUpperCase()] || '')

const fmtTime = value => {
  if (!value) return ''
  return String(value).replace('T', ' ').slice(0, 16)
}

async function openNotice(notice) {
  // 划开状态下先收起，避免误点进详情
  if (offsetFor(notice.id)) { offsets.value = {}; return }
  await markRead(notice)
  const source = messages.rows.find(row => String(row.notification_id ?? row.id ?? row.log_id) === String(notice.id)) || notice
  await navigateNotification(router, source)
}

// 左滑露出「删除」，与入库卡片同一套手势逻辑
const offsets = ref({})
const offsetFor = id => Number(offsets.value[String(id)] || 0)
let activeId = null, startX = 0, startY = 0, horizontal = false
function touchStart(id, event) { activeId = String(id); offsets.value = {}; startX = event.changedTouches?.[0]?.clientX || 0; startY = event.changedTouches?.[0]?.clientY || 0; horizontal = false }
function touchMove(id, event) {
  const touch = event.changedTouches?.[0]; if (!touch) return
  if (activeId !== String(id)) return
  const dx = touch.clientX - startX, dy = touch.clientY - startY
  if (!horizontal && Math.abs(dx) > 8 && Math.abs(dx) > Math.abs(dy)) horizontal = true
  if (!horizontal) return
  if (event.cancelable) event.preventDefault()
  offsets.value = { [activeId]: Math.max(-82, Math.min(0, dx)) }
}
function touchEnd(id) { if (activeId !== String(id)) return; offsets.value = { [activeId]: horizontal && offsetFor(id) < -42 ? -82 : 0 }; horizontal = false }

async function removeNotice(notice) {
  offsets.value = {}
  try { await messages.remove(notice) } catch { error.value = '删除消息失败，请重试' }
}
async function clearAll() {
  if (!notices.value.length) return
  if (!window.confirm(`确认清空全部 ${notices.value.length} 条消息？清空后不可恢复。`)) return
  try { await messages.clearAll() } catch { error.value = '清空消息失败，请重试' }
}



onMounted(async () => {
  try { await messages.refresh() } catch { error.value = messages.error }
})

async function markRead(notice) {
  if (notice.read) return
  try {
    await messages.markRead(notice)
  } catch { error.value='标记已读失败，请重试' }
}
async function markAll(){await Promise.all(notices.value.filter(n=>!n.read).map(markRead))}
</script>
