<template>
<section  class="page">
<ProfilePanel @go="k=>section=k"/>
</section>
</template>
<script setup>
import { inject } from 'vue'
import { roleHomeKey } from './context.js'
import ProfilePanel from '../../components/ProfilePanel.vue'
const { section } = inject(roleHomeKey)
</script>
