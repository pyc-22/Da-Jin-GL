<template>
  <Teleport to="body"><Transition name="toast"><div v-if="message" class="design-toast" role="status" aria-live="polite">{{ message }}</div></Transition></Teleport>
</template>
<script setup>
import { useToast } from '../composables/useToast.js'
const { message } = useToast()
</script>
