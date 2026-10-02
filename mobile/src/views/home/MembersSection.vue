<template>
<section  class="page">
<PageTitle :title="role === 'SALES' ? '我的会员' : '会员查询'">
<button v-if="auth.can('member:create')" class="outline" @click="memberCreateOpen=true">新增会员</button>
<button class="outline" @click="loadMembers">刷新</button>
</PageTitle>
<button v-if="role === 'SALES' && auth.can('member:follow')" class="entry" @click="router.push('/visits')">
<span class="entry-main">回访任务</span>
<span class="entry-sub">待回访 {{ visitStats.pending }} · 去处理 ›</span>
</button>
<div class="search">
<input v-model="keyword" placeholder="搜索姓名或手机号" @keyup.enter="loadMembers"/>
<button @click="loadMembers">搜索</button>
</div>
<div v-if="role === 'SALES'" class="seg">
<Chip :selected="memberTab==='mine'" @click="memberTab='mine'">我的会员</Chip>
<Chip :selected="memberTab==='pool'" @click="memberTab='pool'">公海池</Chip>
</div>
<MemberList :members="members" :sales="role === 'SALES' && memberTab==='mine'" @open="openMember" @claim="claim"/>
</section>
</template>
<script setup>
import Chip from '../../components/Chip.vue'

import { inject } from 'vue'
import { roleHomeKey } from './context.js'
import MemberList from '../../components/MemberList.vue'
import PageTitle from '../../components/PageTitle.vue'
const { router, auth, role, section, members, keyword, memberTab, visits, memberCreateOpen, visitStats, loadMembers, openMember, claim } = inject(roleHomeKey)
</script>
