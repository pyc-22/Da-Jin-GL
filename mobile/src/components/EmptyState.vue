<template>
  <div class="empty-state"><span class="empty-state-mark">⌁</span><strong v-if="title">{{ title }}</strong><small v-if="message" style="overflow-wrap:anywhere;max-width:100%">{{ message }}</small><slot /></div>
</template>

<script setup>
defineProps({ title: { type: String, default: '暂无数据' }, message: { type: String, default: '' } })
</script>
