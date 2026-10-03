<template>
  <div class="mobile-modal" role="dialog" aria-modal="true" aria-labelledby="message-settings-title">
    <section class="mobile-modal-card message-settings">
      <h3 id="message-settings-title">消息设置</h3>
      <p class="muted small" role="status">{{ connectionText }}</p>
      <div class="notify-row"><div><b>声音提醒</b><p class="muted small">关闭声音后仍保留通知栏提醒</p></div><button role="switch" aria-label="声音提醒" :aria-checked="messages.settings.sound" class="switch" :class="{ on: messages.settings.sound }" @click="toggle('sound')">{{ messages.settings.sound ? '开' : '关' }}</button></div>
      <button class="outline full" :disabled="busy !== ''" @click="testSound">试听提示音</button>
      <p v-if="feedback" role="status" class="muted small">{{ feedback }}</p>
      <h4>提醒类型</h4>
      <div v-for="item in categories" :key="item.key" class="notify-row"><span>{{ item.label }}</span><button role="switch" :aria-label="item.label" :aria-checked="messages.settings[item.key]" class="switch" :class="{on: messages.settings[item.key]}" @click="toggle(item.key)">{{ messages.settings[item.key] ? '开' : '关' }}</button></div>
      <p class="muted small">类型开关只控制本机提醒，消息记录仍保留。设置按账号保存在本机。</p>
      <template v-if="device.native">
        <h4>手机通知与后台设置</h4>
        <p class="small">系统通知：{{ device.notificationsEnabled ? '已开启' : '待开启' }} · 电池优化：{{ device.batteryOptimizationIgnored ? '已设为忽略' : '仍有限制' }}</p>
        <div class="setting-actions">
          <button v-if="!device.notificationsEnabled" class="primary" :disabled="busy !== ''" @click="enableNotifications">允许消息通知</button>
          <button class="outline" :disabled="busy !== ''" @click="open('notifications')">系统通知设置</button>
          <button class="outline" :disabled="busy !== ''" @click="open('battery')">申请忽略电池优化</button>
          <button class="outline" :disabled="busy !== ''" @click="open('background')">后台活动 / 自启动设置</button>
        </div>
        <p class="muted small">小米：将应用省电策略设为“无限制”，允许后台自启动。vivo：允许后台高耗电或后台耗电管理中的后台运行，并开启自启动。菜单名称随系统版本有所不同。</p>
        <details><summary>可选高级设置</summary><p class="muted small">悬浮窗不是消息提醒的必要条件，开启后也不保证后台持续运行。</p><button class="outline full" @click="open('overlay')">悬浮窗权限设置</button><button class="text-action" @click="open('batteryList')">查看电池优化应用列表</button></details>
        <p class="muted small">系统休眠或强行停止应用后，实时提醒会中断。重新打开后同步新消息，并合并提醒一次。提示音遵循手机静音、勿扰和通知音量设置。</p>
      </template>
      <p v-else class="muted small">网页可试听提示音；安卓通知栏及后台设置请在 APK 内使用。</p>
      <p v-if="messages.deliveryError" role="alert" class="error">{{ messages.deliveryError }}</p>
      <button class="primary full" @click="$emit('close')">完成</button>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { App } from '@capacitor/app'
import { useAppStore } from '../stores/app.js'
import { useMessagesStore } from '../stores/messages.js'
import { backgroundSettings, isAndroidMessages, openBackgroundSetting, requestMessagePermission } from '../utils/messagePermissions.js'
import { playMessageSound } from '../utils/messageSound.js'

defineEmits(['close'])
const messages = useMessagesStore(), app = useAppStore()
const device = ref({ native: isAndroidMessages() }), busy = ref(''), feedback = ref('')
let listener, disposed = false
const categories = [{ key: 'approval', label: '审批提醒' }, { key: 'stock', label: '库存预警' }, { key: 'visit', label: '回访 / 生日提醒' }, { key: 'system', label: '加工及其他消息' }]
const connectionText = computed(() => ({ connected: '消息连接正常', connecting: '消息连接中…', reconnecting: '消息连接已断开，正在重连…', offline: '网络已断开，联网后自动重连', stopped: '消息连接未启动' }[app.wsStatus] || '正在检查消息连接'))
function toggle(key) { messages.setPreference(key, !messages.settings[key]) }
async function refresh() { try { const value = await backgroundSettings(); if (!disposed) device.value = value } catch { feedback.value = '读取设置失败，请从手机设置中检查通知和电池限制' } }
async function run(action) {
  if (busy.value) return
  busy.value = 'working'; feedback.value = ''
  try { await action() } catch (error) { feedback.value = error?.message || '操作失败，请稍后重试' }
  finally { busy.value = '' }
}
function testSound() { return run(async () => {
  const result = await playMessageSound(messages.owner)
  feedback.value = result?.played ? '已播放提示音' : '请检查系统通知权限、静音 / 勿扰状态和通知音量；网页请先点击页面后再试听'
}) }
function enableNotifications() { return run(async () => {
  const result = await requestMessagePermission(); await refresh()
  if (!result.notificationsEnabled) await openBackgroundSetting('notifications')
}) }
function open(kind) { return run(() => openBackgroundSetting(kind)) }
const visibility = () => { if (document.visibilityState !== 'hidden') void refresh() }
onMounted(async () => {
  void refresh(); document.addEventListener('visibilitychange', visibility)
  if (isAndroidMessages()) {
    const handle = await App.addListener('appStateChange', ({ isActive }) => { if (isActive) void refresh() })
    if (disposed) await handle.remove(); else listener = handle
  }
})
onUnmounted(() => { disposed = true; document.removeEventListener('visibilitychange', visibility); void listener?.remove() })
</script>

<style scoped>
.message-settings{max-height:calc(100dvh - var(--s-6) - env(safe-area-inset-top) - env(safe-area-inset-bottom));overflow-y:auto;padding-bottom:calc(var(--s-4) + env(safe-area-inset-bottom))}
.message-settings h4{margin:var(--s-5) 0 var(--s-2)}
.message-settings p{line-height:1.6;overflow-wrap:anywhere}
.notify-row{display:flex;align-items:center;justify-content:space-between;gap:var(--s-3);padding:var(--s-2) 0;min-height:var(--tap)}
.notify-row p{margin:var(--s-1) 0}.switch{flex-shrink:0}
.setting-actions{display:grid;gap:var(--s-2)}
summary{min-height:var(--tap);display:flex;align-items:center;color:var(--gold-deep);cursor:pointer}
.message-settings>.primary{margin-top:var(--s-3)}
</style>
