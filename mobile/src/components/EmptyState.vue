<template>
  <div class="empty-state"><span class="empty-state-mark">⌁</span><strong>{{ title }}</strong><small v-if="message">{{ message }}</small><slot /></div>
</template>

<script setup>
defineProps({ title: { type: String, default: '暂无数据' }, message: { type: String, default: '' } })
</script>
