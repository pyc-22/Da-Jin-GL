<template><div class="member-list"><EmptyState v-if="!members?.length" title="暂无会员" /><article v-for="m in members" :key="m.member_id||m.id" class="member-row" @click="$emit('open',m)"><div class="avatar">{{ (m.name||'?').slice(0,1) }}</div><div class="member-main"><b>{{ m.name }}</b><p>{{ maskPhone(m.phone) }} <span class="tag">{{ formatTags(m.tags) }}</span></p><small>累计消费 ¥{{ Number(m.total_consume||0).toFixed(2) }}</small></div><button v-if="claimable && !m.sales_id" class="outline" :disabled="claimingId === (m.member_id || m.id)" @click.stop="claimMember(m)">{{ claimingId === (m.member_id || m.id) ? '提交中...' : '申请认领' }}</button><span class="chevron">›</span></article></div></template><script setup>
import EmptyState from './EmptyState.vue'
import { useToast } from '../composables/useToast.js'
const { toast } = useToast()

import { ref } from 'vue'
import { api } from '../api/request.js'
import { maskPhone, formatTags } from '../utils/format.js'

const props = defineProps({ members: Array, claimable: Boolean })
const emit = defineEmits(['open', 'claim'])
const claimingId = ref(null)

async function claimMember(member) {
  const memberId = member.member_id || member.id
  if (!props.claimable || claimingId.value === memberId) return
  claimingId.value = memberId
  try {
    const result = await api.claimMember(memberId)
    emit('claim', member)
    toast(result?.approvalRequired ? '认领申请已提交，等待店长或管理员审批' : '认领申请已提交')
  } catch (error) {
    toast(error?.message || '认领申请提交失败')
  } finally {
    claimingId.value = null
  }
}
</script>
