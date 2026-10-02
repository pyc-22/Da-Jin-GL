<template><DesignHeader :title="title"><slot /></DesignHeader></template>
<script setup>
import DesignHeader from './DesignHeader.vue'
defineProps({title:String})
</script>
