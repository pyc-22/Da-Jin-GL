<template><div class="shell"><DesignHeader title="活动案例" :back="true" @back="router.back()"></DesignHeader><main class="content page"><div class="filter-tabs"><Chip v-for="t in ['活动方案','销售话术','成功案例']" :key="t">{{ t }}</Chip></div><article v-for="item in items" :key="item.title" class="list-card"><div><b>{{ item.title }}</b><p>{{ item.desc }}</p></div><button class="icon-btn" @click="item.saved=!item.saved">{{ item.saved?'已收藏':'收藏' }}</button></article></main></div></template><script setup>
import Chip from '../components/Chip.vue'

import DesignHeader from '../components/DesignHeader.vue'
import{ref}from'vue';import{useRouter}from'vue-router';const router=useRouter();const items=ref([{title:'节日焕新季',desc:'以旧换新专场活动话术',saved:false},{title:'高端定制接待',desc:'预约客户沟通流程',saved:false},{title:'会员生日关怀',desc:'生日月专属权益模板',saved:false}])</script>
