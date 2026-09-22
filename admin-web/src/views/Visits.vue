<script setup>
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { staffApi, visitApi } from '../api/modules'
import { formatMoney, formatTime } from '../utils/format'
import { useAppStore } from '../stores/app'
const app=useAppStore()

const rows = ref([])
const staff = ref([])
const detailVisible = ref(false)
const selectedRecord = ref(null)
const stats = reactive({ total: 0, pending: 0, completed: 0, connected: 0, unsuccessful: 0 })
const filters = reactive({ keyword: '', status: null, visitType: '' })

const typeLabel = value => ({ PURCHASE_3D: '顾客成交3天回访', FOLLOW_UP: '后续跟进', BIRTHDAY: '生日关怀' }[value] || value || '日常回访')
const resultLabel = value => ({ CONNECTED: '已接通', NO_ANSWER: '未接通', REJECTED: '拒接', INVALID: '空号', UNREACHABLE: '无法联系', ORDER_REFUNDED: '订单退款' }[value] || '-')

async function load() {
  try {
    const [tasks, summary, users] = await Promise.all([visitApi.tasks(filters), visitApi.stats(), staffApi.list({ status: 1 })])
    rows.value = tasks || []
    Object.assign(stats, summary || {})
    staff.value = (users || []).filter(user => [2, 4].includes(Number(user.role_id)) && Number(user.status) === 1)
  } catch (error) { ElMessage.error(error?.message || '回访数据加载失败') }
}

async function assign(row, salesId) {
  try { await visitApi.assign(row.task_id, salesId); ElMessage.success('回访任务已转派'); await load() }
  catch (error) { ElMessage.error(error?.message || '转派失败') }
}

function showRecord(row) {
  selectedRecord.value = row
  detailVisible.value = true
}

onMounted(load)
watch(()=>app.eventVersion,()=>{if(['VISIT_TASK_UPDATED','MEMBER_UPDATED','STAFF_UPDATED','ORDER_COMPLETED'].includes(app.lastEventType))load()})
</script>

<template>
  <div class="kpi-grid visit-kpis">
    <section class="panel kpi"><span class="kpi-label">全部任务</span><b class="kpi-value">{{ stats.total || 0 }}</b></section>
    <section class="panel kpi"><span class="kpi-label">待回访</span><b class="kpi-value">{{ stats.pending || 0 }}</b></section>
    <section class="panel kpi"><span class="kpi-label">已完成</span><b class="kpi-value">{{ stats.completed || 0 }}</b></section>
    <section class="panel kpi"><span class="kpi-label">已接通</span><b class="kpi-value">{{ stats.connected || 0 }}</b></section>
  </div>
  <section class="panel visit-panel">
    <div class="page-toolbar">
      <div class="filters">
        <el-input v-model="filters.keyword" placeholder="客户 / 手机号 / 销售单号" clearable @keyup.enter="load" />
        <el-select v-model="filters.status" placeholder="任务状态" clearable><el-option label="待回访" :value="1" /><el-option label="已完成" :value="2" /></el-select>
        <el-select v-model="filters.visitType" placeholder="回访类别" clearable><el-option label="顾客成交3天回访" value="PURCHASE_3D" /><el-option label="后续跟进" value="FOLLOW_UP" /><el-option label="生日关怀" value="BIRTHDAY" /></el-select>
        <el-button type="primary" @click="load">查询</el-button>
      </div>
    </div>
    <el-table :data="rows">
      <el-table-column prop="order_no" label="销售单号" min-width="190" />
      <el-table-column prop="member_name" label="客户" min-width="100" />
      <el-table-column prop="phone" label="手机号" min-width="130" />
      <el-table-column prop="gender" label="性别" width="80"><template #default="scope">{{ scope.row.gender || '-' }}</template></el-table-column>
      <el-table-column label="金额" width="120"><template #default="scope">{{ formatMoney(scope.row.pay_amount) }}</template></el-table-column>
      <el-table-column label="购买时间" min-width="165"><template #default="scope">{{ formatTime(scope.row.purchase_time) }}</template></el-table-column>
      <el-table-column label="回访类别" min-width="160"><template #default="scope">{{ typeLabel(scope.row.visit_type) }}</template></el-table-column>
      <el-table-column label="计划时间" min-width="165"><template #default="scope">{{ formatTime(scope.row.plan_time) }}</template></el-table-column>
      <el-table-column label="状态" width="90"><template #default="scope"><el-tag :type="Number(scope.row.status) === 2 ? 'success' : 'warning'">{{ Number(scope.row.status) === 2 ? '已完成' : '待回访' }}</el-tag></template></el-table-column>
      <el-table-column label="结果" width="100"><template #default="scope">{{ resultLabel(scope.row.call_result) }}</template></el-table-column>
      <el-table-column label="回访内容" min-width="220" show-overflow-tooltip><template #default="scope"><span class="record-preview">{{ scope.row.record || (Number(scope.row.status) === 2 ? '未填写' : '-') }}</span></template></el-table-column>
      <el-table-column label="完成时间" min-width="165"><template #default="scope">{{ Number(scope.row.status) === 2 ? formatTime(scope.row.update_time) : '-' }}</template></el-table-column>
      <el-table-column label="负责员工" min-width="150"><template #default="scope"><el-select :model-value="scope.row.sales_id" size="small" @change="assign(scope.row, $event)"><el-option v-for="user in staff" :key="user.user_id" :label="user.real_name" :value="user.user_id" /></el-select></template></el-table-column>
      <el-table-column label="操作" width="100" fixed="right"><template #default="scope"><el-button v-if="Number(scope.row.status) === 2" link type="primary" @click="showRecord(scope.row)">查看详情</el-button><span v-else>-</span></template></el-table-column>
    </el-table>
  </section>
  <el-dialog v-model="detailVisible" title="回访记录详情" width="620px">
    <el-descriptions v-if="selectedRecord" :column="2" border>
      <el-descriptions-item label="客户">{{ selectedRecord.member_name || '-' }}</el-descriptions-item>
      <el-descriptions-item label="手机号">{{ selectedRecord.phone || '-' }}</el-descriptions-item>
      <el-descriptions-item label="销售单号">{{ selectedRecord.order_no || '-' }}</el-descriptions-item>
      <el-descriptions-item label="回访类别">{{ typeLabel(selectedRecord.visit_type) }}</el-descriptions-item>
      <el-descriptions-item label="拨号结果">{{ resultLabel(selectedRecord.call_result) }}</el-descriptions-item>
      <el-descriptions-item label="负责员工">{{ selectedRecord.sales_name || '-' }}</el-descriptions-item>
      <el-descriptions-item label="拨号时间">{{ formatTime(selectedRecord.call_started_at) }}</el-descriptions-item>
      <el-descriptions-item label="完成时间">{{ formatTime(selectedRecord.update_time) }}</el-descriptions-item>
      <el-descriptions-item label="回访内容" :span="2"><div class="record-content">{{ selectedRecord.record || '未填写' }}</div></el-descriptions-item>
    </el-descriptions>
    <template #footer><el-button type="primary" @click="detailVisible = false">关闭</el-button></template>
  </el-dialog>
</template>

<style scoped>
.visit-kpis { margin-bottom: 16px; }
.visit-panel :deep(.el-input) { width: 220px; }
.visit-panel :deep(.el-select) { width: 160px; }
.record-preview { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.record-content { min-height: 72px; line-height: 1.7; white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
