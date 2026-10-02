<template>
  <section class="panel" :class="{ 'panel-dark': dark }">
    <header v-if="title || $slots.actions" class="panel-head"><h2 v-if="title">{{ title }}</h2><slot name="actions" /></header>
    <slot />
  </section>
</template>
<script setup>
defineProps({ title: String, dark: Boolean })
</script>
