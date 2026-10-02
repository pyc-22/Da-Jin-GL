<template><button type="button" class="chip" :class="{ active: selected }" :aria-pressed="selected" :disabled="disabled"><slot /></button></template>
<script setup>defineProps({ selected: Boolean, disabled: Boolean })</script>
