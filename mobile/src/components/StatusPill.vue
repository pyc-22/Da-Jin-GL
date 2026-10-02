<template><span class="status-pill" :class="'status-' + tone"><slot /></span></template>
<script setup>defineProps({ tone: { type: String, default: 'gold', validator: value => ['gold', 'ok', 'err', 'muted'].includes(value) } })</script>
