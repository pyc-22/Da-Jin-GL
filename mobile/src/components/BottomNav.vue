<template>
  <nav class="tabbar" aria-label="主导航">
    <template v-for="key in positions" :key="key">
      <button v-if="tabs.find(tab => tab.key === key)" :class="{ active: active === key, 'tabbar-order': key === 'processing' }" :aria-current="active === key ? 'page' : undefined" @click="open(key)">
        <span class="tab-icon"><svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path :d="icons[key]" /></svg></span>
        {{ tabs.find(tab => tab.key === key).label }}
      </button>
      <span v-else class="tab-placeholder" aria-hidden="true" />
    </template>
  </nav>
</template>
<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth.js'
import { tabsForRole } from '../config/roles.js'
const props = defineProps({ active: String, local: Boolean })
const emit = defineEmits(['select'])
const auth = useAuthStore(), router = useRouter()
const manager = computed(() => ['ADMIN', 'MANAGER'].includes(auth.role))
const home = computed(() => manager.value ? 'dashboard' : 'home')
const positions = computed(() => [home.value, 'documents', 'processing', 'notifications', 'profile'])
const tabs = computed(() => tabsForRole(auth.role, auth.permissions))
const icons = { dashboard: 'M3 11l9-8 9 8v10h-6v-6H9v6H3z', home: 'M3 11l9-8 9 8v10h-6v-6H9v6H3z', documents: 'M5 3h14v18H5z M8 8h8 M8 12h8 M8 16h5', processing: 'M14 5l5 5M4 16L16 4a2 2 0 0 1 4 4L8 20l-5 1z M13 7l4 4', notifications: 'M4 4h16v14H9l-5 3z M8 9h8 M8 13h5', profile: 'M16 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0M4 21v-2c0-6 16-6 16 0v2' }
function open(key) {
  if (key === 'processing') return router.push('/processing?create=1')
  if (props.local) return emit('select', key)
  router.push('/' + (manager.value ? 'manager' : 'sales') + '/' + key)
}
</script>
