<template>
  <header class="design-header">
    <button v-if="back" class="design-header-back" type="button" aria-label="返回" @click="$emit('back')"><svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="m15 5-7 7 7 7" /></svg></button>
    <div class="design-header-copy">
      <span v-if="eyebrow" class="design-eyebrow">{{ eyebrow }}</span>
      <h1>{{ title }}</h1>
      <small v-if="subtitle">{{ subtitle }}</small>
    </div>
    <div class="design-header-actions"><slot /></div>
  </header>
</template>

<script setup>
defineProps({
  title: { type: String, required: true },
  subtitle: { type: String, default: '' },
  eyebrow: { type: String, default: '' },
  back: { type: Boolean, default: false }
})
defineEmits(['back'])
</script>
