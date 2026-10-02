<template>
  <header class="welcome-header">
    <div class="avatar">{{ name.slice(0, 1) }}</div>
    <div class="welcome-copy"><h1>{{ name }}，{{ greeting }}</h1><p>{{ roleLabel }} · {{ store }}</p></div>
    <button v-if="auth.can('notification:view')" class="welcome-notice" aria-label="消息" @click="$emit('messages')"><svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M6 9a6 6 0 0 1 12 0c0 6 3 6 3 8H3c0-2 3-2 3-8M10 21h4" /></svg><i v-if="unread" /></button>
  </header>
</template>
<script setup>
import { computed } from 'vue'
import { useAuthStore } from '../stores/auth.js'
defineProps({ unread: Number })
defineEmits(['messages'])
const auth = useAuthStore()
const name = computed(() => auth.user?.real_name || auth.user?.realName || auth.user?.username || '员工')
const store = computed(() => auth.user?.store_name || auth.user?.storeName || '默认门店')
const roleLabel = computed(() => ({ ADMIN: '管理员', MANAGER: '店长', SALES: '导购' }[auth.role] || auth.role))
const hour = new Date().getHours()
const greeting = hour < 12 ? '上午好' : hour < 18 ? '下午好' : '晚上好'
</script>
