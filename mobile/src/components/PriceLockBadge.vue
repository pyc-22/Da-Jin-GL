<template><StatusPill v-if="locked" class="price-lock-badge">金价已锁</StatusPill></template>
<script setup>
import StatusPill from './StatusPill.vue'
defineProps({ locked: Boolean })
</script>
