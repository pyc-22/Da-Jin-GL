<template>
<section  class="page">
<ProfilePanel @go="navigateSection"/>
</section>
</template>
<script setup>
import { inject } from 'vue'
import { roleHomeKey } from './context.js'
import ProfilePanel from '../../components/ProfilePanel.vue'
const { navigateSection } = inject(roleHomeKey)
</script>
