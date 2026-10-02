<template>
<section  class="page">
<PageTitle title="会员总览">
<button v-if="auth.can('member:create')" class="outline" @click="memberCreateOpen=true">新增会员</button>
</PageTitle>
<div class="kpi-grid">
<Kpi label="会员总数" :value="memberStats.total"/>
<Kpi label="新增会员" :value="memberStats.new"/>
<Kpi label="活跃会员" :value="memberStats.active"/>
<Kpi label="储值余额" :value="`¥${money(memberStats.balance)}`"/>
</div>
<Panel title="会员分层分析">
<div v-for="t in tiers" :key="t.label" class="tier-row">
<i class="tier-dot" :style="{background:t.color}">
</i>
<span>{{ t.label }}</span>
<b>{{ t.count }} 人</b>
<small>累计消费 ¥{{ money(t.amount) }}</small>
</div>
<p class="muted small">按累计消费分层，基于最近500名会员</p>
</Panel>
<Panel :title="`本月生日会员（${birthdayMembers.length}）`">
<div v-for="m in birthdayMembers.slice(0,10)" :key="'bm'+(m.member_id||m.id)" class="rank-row">
<span>{{ m.name }}</span>
<small>生日 {{ (m.birthday||'').slice(5) }} · {{ maskPhone(m.phone) }}</small>
<button class="outline" @click="call(m.phone)">拨号</button>
</div>
<EmptyState v-if="!birthdayMembers.length" title="本月暂无生日会员" />
</Panel>
<MemberList :members="members" @open="openMember"/>
</section>
</template>
<script setup>
import EmptyState from '../../components/EmptyState.vue'

import { inject } from 'vue'
import { roleHomeKey } from './context.js'
import Kpi from '../../components/Kpi.vue'
import Panel from '../../components/Panel.vue'
import MemberList from '../../components/MemberList.vue'
import PageTitle from '../../components/PageTitle.vue'
const { maskPhone, auth, section, members, birthdayMembers, memberCreateOpen, memberStats, money, tiers, openMember, call } = inject(roleHomeKey)
</script>
