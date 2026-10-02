<template>
<section  class="page">
<PageTitle title="货品"/>
<button class="outline full" @click="router.push('/goods/search')">🔍 搜索货品（条码/名称）</button>
<p class="muted small">支持扫码或手动输入条码查询商品信息与价格</p>
</section>
</template>
<script setup>
import { inject } from 'vue'
import { roleHomeKey } from './context.js'
import PageTitle from '../../components/PageTitle.vue'
const { router, section } = inject(roleHomeKey)
</script>
