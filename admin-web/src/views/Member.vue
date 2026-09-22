<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { memberApi, staffApi } from '../api/modules'
import { useAppStore } from '../stores/app'
import { formatMoney, formatTime } from '../utils/format'

const app = useAppStore()
const tab = ref('all')
const rows = ref([])
const pool = ref([])
const sales = ref([])
const filters = reactive({ keyword: '', birthdayFilter: '', birthdayFrom: '', birthdayTo: '', birthdayOrder: 'desc' })
const dialog = ref(false)
const editingId = ref(null)
const detailVisible = ref(false)
const detail = ref(null)
const balanceDialog = ref(false)
const balanceBusy = ref(false)
const assignDialog = ref(false)
const balanceForm = reactive({ amount: 0, type: 'RECHARGE', payMethod: 'CASH', clientRequestId: '' })
const assignForm = reactive({ salesId: null })
const form = reactive({ name: '', phone: '', tags: '[]', birthday: '', gender: '', source: '管理端', salesId: null })
const dialogTitle = computed(() => editingId.value ? '编辑会员' : '新增会员')
const visibleRows = computed(() => tab.value === 'pool' ? pool.value : rows.value)
const rechargeChannels = computed(() => app.paymentChannels.filter(channel => Number(channel.status) === 1 && !['BALANCE', 'COMBINATION'].includes(String(channel.channel_code).toUpperCase())))

function requestParams() {
  const params = { ...filters }
  if (filters.birthdayFilter !== 'custom') {
    params.birthdayFrom = undefined
    params.birthdayTo = undefined
  }
  return params
}

async function load() {
  const params = requestParams()
  const data = tab.value === 'pool' ? await memberApi.pool(params) : await memberApi.list(params)
  if (tab.value === 'pool') pool.value = data.records || []
  else rows.value = data.records || []
  sales.value = (await staffApi.list()).filter(user => [3, 4].includes(Number(user.role_id)))
}

function resetForm() {
  Object.assign(form, { name: '', phone: '', tags: '[]', birthday: '', gender: '', source: '管理端', salesId: null })
  editingId.value = null
}

function openCreate() {
  resetForm()
  dialog.value = true
}

function openEdit(row) {
  Object.assign(form, {
    name: row?.name || '',
    phone: row?.phone || '',
    tags: typeof row?.tags === 'string' ? row.tags : JSON.stringify(row?.tags || []),
    birthday: row?.birthday ? String(row.birthday).slice(0, 10) : '',
    gender: row?.gender || '',
    source: row?.source || '管理端',
    salesId: row?.sales_id || null
  })
  editingId.value = row?.member_id || null
  dialog.value = true
}

async function save() {
  const name = form.name.trim()
  const phone = form.phone.trim()
  if (!name) return ElMessage.warning('请输入会员姓名')
  if (!/^1[3-9]\d{9}$/.test(phone)) return ElMessage.warning('请输入正确的手机号')
  try {
    const payload = { ...form, name, phone, tags: form.tags || '[]', birthday: form.birthday || null }
    const updating = Boolean(editingId.value)
    if (updating) await memberApi.update(editingId.value, payload)
    else await memberApi.create(payload)
    dialog.value = false
    ElMessage.success(updating ? '会员已更新' : '会员已保存')
    await load()
    if (detail.value?.member_id === editingId.value) await show(detail.value)
    editingId.value = null
  } catch (error) {
    ElMessage.error(error?.message || '会员保存失败')
  }
}

async function show(row) {
  try {
    const data = await memberApi.detail(row.member_id)
    const [consume, balanceRecords] = await Promise.all([memberApi.consume(row.member_id), memberApi.balanceRecords(row.member_id)])
    detail.value = { ...(data?.member || data), consume: consume || [], balanceRecords: balanceRecords || [] }
    detailVisible.value = true
  } catch (error) {
    ElMessage.error(error?.message || '会员详情加载失败')
  }
}

async function balance() {
  if (balanceBusy.value) return
  balanceBusy.value = true
  try {
    await memberApi.balance(detail.value.member_id, balanceForm)
    balanceDialog.value = false
    ElMessage.success(balanceForm.type === 'RECHARGE' ? '充值成功' : '扣款成功')
    await show(detail.value)
    await load()
  } catch (error) {
    ElMessage.error(error?.message || '储值操作失败')
  } finally {
    balanceBusy.value = false
  }
}

async function assign() {
  try {
    await memberApi.assign(detail.value.member_id, assignForm.salesId)
    assignDialog.value = false
    ElMessage.success('会员已分配')
    await show(detail.value)
    await load()
  } catch (error) {
    ElMessage.error(error?.message || '会员分配失败')
  }
}

function openBalance(type) {
  balanceForm.clientRequestId = globalThis.crypto?.randomUUID?.() || `balance-${Date.now()}-${Math.random().toString(36).slice(2)}`
  balanceForm.payMethod = rechargeChannels.value[0]?.channel_code || ''
  balanceForm.type = type
  balanceForm.amount = 0
  balanceDialog.value = true
}

function openAssign(row) {
  detail.value = row
  assignForm.salesId = row.sales_id || null
  assignDialog.value = true
}

function handleBirthdayFilter(value) {
  if (value !== 'custom') {
    filters.birthdayFrom = ''
    filters.birthdayTo = ''
  }
  load()
}

function handleSortChange({ prop, order }) {
  if (prop !== 'birthday') return
  filters.birthdayOrder = order === 'ascending' ? 'asc' : 'desc'
  load()
}

function clearFilters() {
  Object.assign(filters, { keyword: '', birthdayFilter: '', birthdayFrom: '', birthdayTo: '', birthdayOrder: 'desc' })
  load()
}

function formatBirthday(value) {
  return value ? String(value).slice(0, 10) : '-'
}

onMounted(load)
watch(() => app.eventVersion, () => {
  if (['MEMBER_UPDATED', 'ORDER_COMPLETED'].includes(app.lastEventType)) load()
})
</script>

<template>
  <el-tabs v-model="tab" @tab-change="load">
    <el-tab-pane label="全部会员" name="all" />
    <el-tab-pane label="公海池" name="pool" />
  </el-tabs>

  <section class="panel">
    <div class="page-toolbar">
      <div class="filters">
        <el-input v-model="filters.keyword" placeholder="姓名 / 手机号" clearable @keyup.enter="load" />
        <el-select v-model="filters.birthdayFilter" placeholder="生日筛选" clearable @change="handleBirthdayFilter">
          <el-option label="今日生日" value="today" />
          <el-option label="本月生日" value="month" />
          <el-option label="自定义日期范围" value="custom" />
        </el-select>
        <el-date-picker v-if="filters.birthdayFilter === 'custom'" v-model="filters.birthdayFrom" type="date" value-format="YYYY-MM-DD" placeholder="开始日期" clearable @change="load" />
        <el-date-picker v-if="filters.birthdayFilter === 'custom'" v-model="filters.birthdayTo" type="date" value-format="YYYY-MM-DD" placeholder="结束日期" clearable @change="load" />
        <el-select v-model="filters.birthdayOrder" placeholder="生日排序" @change="load">
          <el-option label="生日倒序" value="desc" />
          <el-option label="生日正序" value="asc" />
        </el-select>
        <el-button @click="load">查询</el-button>
        <el-button @click="clearFilters">重置</el-button>
      </div>
      <el-button type="primary" @click="openCreate">新增会员</el-button>
    </div>

    <el-table :data="visibleRows" @sort-change="handleSortChange">
      <el-table-column prop="name" label="姓名" min-width="100" />
      <el-table-column prop="phone" label="手机号" min-width="130" />
      <el-table-column prop="birthday" label="生日" sortable="custom" min-width="120">
        <template #default="scope">{{ formatBirthday(scope.row.birthday) }}</template>
      </el-table-column>
      <el-table-column prop="gender" label="性别" min-width="80"><template #default="scope">{{ scope.row.gender || '-' }}</template></el-table-column>
      <el-table-column prop="tags" label="标签" min-width="100" />
      <el-table-column prop="balance" label="储值余额" min-width="110"><template #default="scope">{{ formatMoney(scope.row.balance) }}</template></el-table-column>
      <el-table-column prop="points" label="积分" min-width="70" />
      <el-table-column prop="total_consume" label="累计消费" min-width="110"><template #default="scope">{{ formatMoney(scope.row.total_consume) }}</template></el-table-column>
      <el-table-column v-if="tab === 'pool'" label="归属" min-width="140">
        <template #default="scope"><el-button link type="primary" @click="openAssign(scope.row)">分配</el-button><el-button link @click="memberApi.claim(scope.row.member_id).then(load)">认领</el-button></template>
      </el-table-column>
      <el-table-column label="操作" min-width="130"><template #default="scope"><el-button link type="primary" @click="show(scope.row)">详情</el-button><el-button link @click="openEdit(scope.row)">编辑</el-button></template></el-table-column>
    </el-table>
  </section>

  <el-dialog v-model="dialog" :title="dialogTitle" width="460px">
    <el-form label-width="90px">
      <el-form-item label="姓名" required><el-input v-model="form.name" maxlength="100" /></el-form-item>
      <el-form-item label="手机号" required><el-input v-model="form.phone" maxlength="30" /></el-form-item>
      <el-form-item label="生日"><el-date-picker v-model="form.birthday" type="date" value-format="YYYY-MM-DD" placeholder="选择生日" clearable style="width:100%" /></el-form-item>
      <el-form-item label="性别"><el-select v-model="form.gender" placeholder="请选择" clearable style="width:100%"><el-option label="男" value="男" /><el-option label="女" value="女" /></el-select></el-form-item>
      <el-form-item label="标签"><el-input v-model="form.tags" placeholder="JSON 标签数组" /></el-form-item>
    </el-form>
    <template #footer><el-button @click="dialog = false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template>
  </el-dialog>

  <el-drawer v-model="detailVisible" title="会员详情" size="44%">
    <template v-if="detail">
      <div class="page-toolbar"><strong>{{ detail.name }}</strong><el-button type="primary" size="small" @click="openEdit(detail)">编辑</el-button></div>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="姓名">{{ detail.name }}</el-descriptions-item>
        <el-descriptions-item label="手机号">{{ detail.phone }}</el-descriptions-item>
        <el-descriptions-item label="生日">{{ formatBirthday(detail.birthday) }}</el-descriptions-item>
        <el-descriptions-item label="性别">{{ detail.gender || '-' }}</el-descriptions-item>
        <el-descriptions-item label="储值余额">{{ formatMoney(detail.balance) }}</el-descriptions-item>
        <el-descriptions-item label="积分">{{ detail.points || 0 }}</el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ formatTime(detail.create_time) }}</el-descriptions-item>
      </el-descriptions>
      <div class="inline-actions balance-actions"><el-button type="primary" @click="openBalance('RECHARGE')">储值充值</el-button><el-button type="warning" @click="openBalance('DEDUCT')">储值扣款</el-button><el-button @click="openAssign(detail)">分配销售</el-button></div>
      <h4>储值流水</h4>
      <el-table :data="detail.balanceRecords || []" size="small"><el-table-column prop="amount" label="变动金额" /><el-table-column label="时间"><template #default="scope">{{ formatTime(scope.row.consume_time) }}</template></el-table-column></el-table>
      <h4>消费记录</h4>
      <el-table :data="detail.consume || []" size="small"><el-table-column prop="amount" label="金额" /><el-table-column label="时间"><template #default="scope">{{ formatTime(scope.row.consume_time) }}</template></el-table-column></el-table>
    </template>
  </el-drawer>

  <el-dialog v-model="balanceDialog" :title="balanceForm.type === 'RECHARGE' ? '储值充值' : '储值扣款'" width="380px">
    <el-form label-width="90px"><el-form-item label="金额"><el-input-number v-model="balanceForm.amount" :disabled="balanceBusy" :min="0.01" :precision="2" /></el-form-item><el-form-item v-if="balanceForm.type === 'RECHARGE'" label="支付方式"><el-select v-model="balanceForm.payMethod" :disabled="balanceBusy" placeholder="暂无可用支付方式"><el-option v-for="channel in rechargeChannels" :key="channel.channel_id" :label="channel.channel_name" :value="channel.channel_code" /></el-select></el-form-item></el-form>
    <template #footer><el-button :disabled="balanceBusy" @click="balanceDialog = false">取消</el-button><el-button type="primary" :loading="balanceBusy" :disabled="balanceForm.type === 'RECHARGE' && !balanceForm.payMethod" @click="balance">确认</el-button></template>
  </el-dialog>

  <el-dialog v-model="assignDialog" title="分配销售" width="380px">
    <el-select v-model="assignForm.salesId" placeholder="选择销售" style="width:100%"><el-option v-for="user in sales" :key="user.user_id" :value="user.user_id" :label="user.real_name || user.username" /></el-select>
    <template #footer><el-button @click="assignDialog = false">取消</el-button><el-button type="primary" @click="assign">确认分配</el-button></template>
  </el-dialog>
</template>
