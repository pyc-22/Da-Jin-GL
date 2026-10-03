<template>
<section  class="page">
<PageTitle title="审批中心"/>
<div class="seg">
<Chip :selected="approvalView==='pending'" @click="approvalView='pending'">待审批（{{ approvals.length }}）</Chip>
<Chip :selected="approvalView==='history'" @click="openApprovalHistory">历史</Chip>
</div>
<template v-if="approvalView==='pending'">
<div class="chips">
<Chip v-for="t in approvalTabs" :key="t" :selected="approvalFilter===t" @click="approvalFilter=t">{{ t }}</Chip>
</div>
<EmptyState v-if="!filteredApprovals.length" title="暂无待审批" />
<article v-for="item in filteredApprovals" :key="item.approval_id || item.id" class="panel approval-card" @click="openApprovalDetail(item)">
<h3>{{ typeName(item.type) }}<span class="more">{{ (item.create_time || item.time || '').slice(5,16).replace('T',' ') }}</span>
</h3>
<p>{{ formatApprovalReason(item) }}</p>
<div class="approval-foot">
<b>{{ Number(item.amount) ? '¥'+money(item.amount) : '—' }}</b>
<div v-if="canHandleApproval(auth,item)" class="card-actions">
<button class="success" @click.stop="openApprovalDetail(item)">通过</button>
<button class="danger" @click.stop="openApprovalDetail(item,true)">驳回</button>
</div>
</div>
</article>
</template>
<template v-else>
<EmptyState v-if="!approvalHistoryData.length" title="暂无审批历史" />
<article v-for="item in approvalHistoryData" :key="'h'+item.approval_id" class="panel approval-card" @click="openApprovalDetail(item)">
<h3>{{ typeName(item.type) }}<span class="more">{{ (item.approve_time||item.create_time||'').slice(5,16).replace('T',' ') }}</span>
</h3>
<p>{{ item.approve_remark || formatApprovalReason(item) }}</p>
<div class="approval-foot">
<b :class="item.status===3?'ok':(item.status===4?'error':'')">{{ item.status===3?'已通过':(item.status===4?'已驳回':'已处理') }}</b>
</div>
</article>
</template>
<p class="fine">审批支持移动处理：通过 / 驳回（驳回必填原因），全程留痕</p>
</section>
</template>
<script setup>
import EmptyState from '../../components/EmptyState.vue'

import Chip from '../../components/Chip.vue'

import { inject } from 'vue'
import { roleHomeKey } from './context.js'
import PageTitle from '../../components/PageTitle.vue'
import { canHandleApproval } from '../../utils/approvalPermissions'
const { auth, formatApprovalReason, section, approvals, approvalFilter, approvalTabs, approvalView, approvalHistoryData, typeName, filteredApprovals, money, openApprovalHistory, openApprovalDetail } = inject(roleHomeKey)
</script>
