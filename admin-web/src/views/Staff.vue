<script setup>
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { staffApi } from '../api/modules'
import { useAuthStore } from '../stores/auth'
import { useAppStore } from '../stores/app'
import { canManageStaffRole, filterAssignableRoles, staffRoleLabel } from '../utils/staffRoles'
const app=useAppStore()

const auth = useAuthStore()
const rows = ref([])
const roles = ref([])
const schedules = ref([])
const loading = ref(false)
const dialog = ref(false)
const scheduleDialog = ref(false)
const permissionDialog = ref(false)
const permissionTree = ref([])
const permissionTreeRef = ref()
const permissionUser = ref(null)
const permissionSaving = ref(false)
const formRef = ref()
const editingId = ref(null)
const usernameState = reactive({ checking: false, available: null, message: '' })
const filters = reactive({ keyword: '', status: '' })
const mode = ref('week')
const cursor = ref(new Date())
let usernameTimer

const today = () => {
  const date = new Date()
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}
const emptyForm = () => ({ username: '', realName: '', password: '', confirmPassword: '', roleId: null, phone: '', storeId: auth.user?.store_id || auth.user?.storeId || 1, entryDate: today(), status: 1, remark: '' })
const form = reactive(emptyForm())
const scheduleForm = reactive({ userId: null, date: '', shift: '早班', startTime: '09:00', endTime: '18:00', remark: '' })

const visibleRoles = computed(() => filterAssignableRoles(roles.value, auth.role))
const days = computed(() => {
  const monday = new Date(cursor.value)
  const day = monday.getDay() || 7
  monday.setDate(monday.getDate() - day + 1)
  const count = mode.value === 'day' ? 1 : 7
  return Array.from({ length: count }, (_, index) => {
    const date = new Date(monday)
    date.setDate(monday.getDate() + index)
    return { key: date.toISOString().slice(0, 10), label: `${date.getMonth() + 1}/${date.getDate()} ${['日', '一', '二', '三', '四', '五', '六'][date.getDay()]}` }
  })
})

const rules = {
  realName: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  username: [
    { required: true, message: '请输入账号', trigger: 'blur' },
    { pattern: /^[A-Za-z0-9]{4,20}$/, message: '账号须为4-20位字母或数字', trigger: ['blur', 'change'] },
    { validator: (_rule, _value, callback) => usernameState.available === false ? callback(new Error(usernameState.message || '登录账号已存在')) : callback(), trigger: 'blur' }
  ],
  password: [{ validator: (_rule, value, callback) => {
    if (!editingId.value && !value) return callback(new Error('请输入初始密码'))
    if (value && !/^(?=.*[A-Za-z])(?=.*\d).{6,20}$/.test(value)) return callback(new Error('密码须为6-20位且同时包含字母和数字'))
    callback()
  }, trigger: 'blur' }],
  confirmPassword: [{ validator: (_rule, value, callback) => value !== form.password ? callback(new Error('两次输入的密码不一致')) : callback(), trigger: 'blur' }],
  roleId: [{ required: true, message: '请选择角色', trigger: 'change' }],
  storeId: [{ required: true, message: '请选择所属门店', trigger: 'change' }],
  phone: [{ pattern: /^$|^1\d{10}$/, message: '请输入正确的11位手机号', trigger: 'blur' }]
}

async function load() {
  loading.value = true
  try {
    const [staffRows, roleRows, scheduleRows] = await Promise.all([
      staffApi.list({ keyword: filters.keyword || undefined, status: filters.status === '' ? undefined : Number(filters.status) }),
      staffApi.roles(),
      staffApi.schedules({ start: days.value[0]?.key, end: days.value.at(-1)?.key })
    ])
    rows.value = staffRows
    roles.value = roleRows
    schedules.value = scheduleRows
  } catch (error) { ElMessage.error(error.message || '人员数据加载失败') }
  finally { loading.value = false }
}

function resetForm() {
  Object.assign(form, emptyForm())
  editingId.value = null
  usernameState.available = null
  usernameState.message = ''
  nextTick(() => formRef.value?.clearValidate())
}

function openStaff(row) {
  if (row && !canManageRow(row)) return ElMessage.warning('店长只能管理销售、前台收银和打金师傅账号')
  resetForm()
  if (row) {
    editingId.value = row.user_id
    Object.assign(form, { username: row.username, realName: row.real_name, roleId: row.role_id, phone: row.phone || '', storeId: row.store_id, entryDate: row.entry_date || today(), status: Number(row.status), remark: row.remark || '' })
    usernameState.available = true
  } else {
    const defaultRole = visibleRoles.value.find(role => role.role_code === 'SALES') || visibleRoles.value[0]
    form.roleId = defaultRole?.role_id || null
  }
  dialog.value = true
}

function scheduleUsernameCheck() {
  clearTimeout(usernameTimer)
  usernameState.available = null
  usernameState.message = ''
  if (!/^[A-Za-z0-9]{4,20}$/.test(form.username)) return
  usernameTimer = setTimeout(checkUsername, 350)
}

async function checkUsername() {
  usernameState.checking = true
  try {
    const result = await staffApi.checkUsername({ username: form.username, excludeId: editingId.value || undefined })
    usernameState.available = result.available
    usernameState.message = result.message
    if (!result.available) formRef.value?.validateField('username').catch(() => {})
  } catch (error) {
    usernameState.available = false
    usernameState.message = error.message || '账号校验失败'
  } finally { usernameState.checking = false }
}

async function save() {
  await checkUsername()
  try { await formRef.value.validate() } catch { return }
  if (usernameState.available === false) return ElMessage.warning(usernameState.message)
  try {
    const payload = { ...form }
    if (!payload.password) { delete payload.password; delete payload.confirmPassword }
    if (editingId.value) await staffApi.update(editingId.value, payload)
    else await staffApi.create(payload)
    dialog.value = false
    ElMessage.success(editingId.value ? '员工信息已更新' : '员工档案已创建')
    await load()
  } catch (error) { ElMessage.error(error.message || '员工保存失败') }
}

async function toggleStatus(row) {
  if (!canManageRow(row)) return ElMessage.warning('店长只能管理销售、前台收银和打金师傅账号')
  const next = Number(row.status) === 1 ? 0 : 1
  try {
    await staffApi.status(row.user_id, next)
    ElMessage.success(next === 1 ? '员工已启用' : '员工已禁用，现有登录会立即失效')
    await load()
  } catch (error) { ElMessage.error(error.message || '状态修改失败') }
}

async function removeUser(row) {
  if (!canManageRow(row)) return ElMessage.warning('店长只能管理销售、前台收银和打金师傅账号')
  try {
    await ElMessageBox.confirm(`确定删除员工“${row.real_name || row.username}”吗？删除后不可恢复`, '二次确认', { type: 'warning', confirmButtonText: '确定删除', cancelButtonText: '取消' })
    await staffApi.delete(row.user_id)
    ElMessage.success('员工已删除')
    await load()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(error.message || '删除失败')
  }
}

async function openPermissions(user) {
  permissionUser.value = user
  permissionDialog.value = true
  try {
    const [tree, selected] = await Promise.all([staffApi.permissionTree(), staffApi.userPermissions(user.user_id)])
    permissionTree.value = tree
    await nextTick()
    permissionTreeRef.value?.setCheckedKeys(selected.permissions || [])
  } catch (error) { ElMessage.error(error.message || '权限数据加载失败') }
}

function selectAllPermissions() {
  const leafCodes = permissionTree.value.flatMap(group => (group.children || []).map(item => item.id))
  permissionTreeRef.value?.setCheckedKeys(leafCodes)
}

async function savePermissions() {
  const codes = permissionTreeRef.value?.getCheckedKeys(true) || []
  permissionSaving.value = true
  try {
    await staffApi.saveUserPermissions(permissionUser.value.user_id, codes)
    permissionDialog.value = false
    ElMessage.success('员工个人权限已保存，员工重新登录后生效')
  } catch (error) { ElMessage.error(error.message || '权限保存失败') }
  finally { permissionSaving.value = false }
}

function roleTag(code) { return ({ ADMIN: 'danger', MANAGER: 'warning', CASHIER: 'success', SALES: 'primary', CRAFTSMAN: 'info' })[code] || 'info' }
function canManageRow(row) { return canManageStaffRole(row?.role_code, auth.role) }
function openSchedule(date) { scheduleForm.date = date; scheduleForm.userId = rows.value[0]?.user_id || null; scheduleDialog.value = true }
function dragStart(event, user) { event.dataTransfer.setData('userId', user.user_id) }
function dropUser(event, date) { scheduleForm.userId = Number(event.dataTransfer.getData('userId')); scheduleForm.date = date; scheduleDialog.value = true }
function content(item) { try { return JSON.parse(item.content) } catch { return {} } }
function listFor(date) { return schedules.value.filter(schedule => content(schedule).date === date) }
function shift(offset) { const date = new Date(cursor.value); date.setDate(date.getDate() + offset * (mode.value === 'day' ? 1 : 7)); cursor.value = date; load() }
async function saveSchedule() { if (!scheduleForm.userId || !scheduleForm.date) return ElMessage.warning('请选择员工和日期'); await staffApi.saveSchedule({ ...scheduleForm }); scheduleDialog.value = false; ElMessage.success('排班已保存'); load() }

onMounted(load)
watch(()=>app.eventVersion,()=>{if(['STAFF_UPDATED','ROLE_UPDATED','USER_PERMISSIONS_UPDATED','SCHEDULE_UPDATED'].includes(app.lastEventType))load()})
</script>

<template>
  <el-tabs>
    <el-tab-pane label="员工档案">
      <section class="panel">
        <div class="page-toolbar">
          <div class="filters">
            <el-input v-model="filters.keyword" clearable placeholder="搜索姓名 / 账号 / 手机号" style="width:260px" @keyup.enter="load" @clear="load" />
            <el-select v-model="filters.status" placeholder="状态筛选" style="width:130px" @change="load"><el-option label="全部" value="" /><el-option label="在职" :value="1" /><el-option label="已禁用" :value="0" /></el-select>
            <el-button @click="load">查询</el-button>
          </div>
          <el-button type="primary" @click="openStaff()">新增员工</el-button>
        </div>
        <el-table v-loading="loading" :data="rows" stripe>
          <el-table-column prop="real_name" label="姓名" min-width="100" /><el-table-column prop="username" label="移动端账号" min-width="130" />
          <el-table-column label="角色" width="110"><template #default="scope"><el-tag :type="roleTag(scope.row.role_code)">{{ scope.row.role_name }}</el-tag></template></el-table-column>
          <el-table-column prop="phone" label="手机号" width="130"><template #default="scope">{{ scope.row.phone || '-' }}</template></el-table-column><el-table-column prop="store_name" label="所属门店" min-width="130" />
          <el-table-column prop="entry_date" label="入职日期" width="120"><template #default="scope">{{ scope.row.entry_date || '-' }}</template></el-table-column>
          <el-table-column label="状态" width="90"><template #default="scope"><el-tag :type="Number(scope.row.status) === 1 ? 'success' : 'info'">{{ Number(scope.row.status) === 1 ? '在职' : '已禁用' }}</el-tag></template></el-table-column>
          <el-table-column label="操作" width="220" fixed="right"><template #default="scope"><template v-if="canManageRow(scope.row)"><el-button link type="primary" @click="openStaff(scope.row)">编辑</el-button><el-button link :type="Number(scope.row.status) === 1 ? 'warning' : 'success'" @click="toggleStatus(scope.row)">{{ Number(scope.row.status) === 1 ? '禁用' : '启用' }}</el-button><el-button link type="danger" @click="removeUser(scope.row)">删除</el-button></template><span v-else class="muted">仅查看</span></template></el-table-column>
        </el-table>
      </section>
    </el-tab-pane>

    <el-tab-pane label="员工权限">
      <section class="panel">
        <div class="page-toolbar"><div><b>员工个人权限</b><div class="muted role-help">每个账号独立配置；角色只提供新建账号时的初始权限模板</div></div></div>
        <el-table :data="rows" stripe>
          <el-table-column prop="real_name" label="员工" min-width="130"><template #default="scope"><b>{{ scope.row.real_name || scope.row.username }}</b></template></el-table-column>
          <el-table-column prop="username" label="移动端账号" min-width="150" />
          <el-table-column label="身份角色" width="130"><template #default="scope"><el-tag :type="roleTag(scope.row.role_code)">{{ scope.row.role_name }}</el-tag></template></el-table-column>
          <el-table-column prop="store_name" label="所属门店" min-width="160" />
          <el-table-column label="账号状态" width="110"><template #default="scope"><el-tag :type="Number(scope.row.status) === 1 ? 'success' : 'info'">{{ Number(scope.row.status) === 1 ? '在职' : '已禁用' }}</el-tag></template></el-table-column>
          <el-table-column v-if="auth.role === 'ADMIN'" label="操作" width="130" fixed="right"><template #default="scope"><el-button link type="primary" @click="openPermissions(scope.row)">配置个人权限</el-button></template></el-table-column>
        </el-table>
      </section>
    </el-tab-pane>

    <el-tab-pane label="排班考勤">
      <section class="panel schedule-panel"><div class="page-toolbar"><div class="inline-actions"><el-button :type="mode === 'day' ? 'primary' : ''" @click="mode = 'day'; load()">日视图</el-button><el-button :type="mode === 'week' ? 'primary' : ''" @click="mode = 'week'; load()">周视图</el-button><el-button @click="shift(-1)">上一周期</el-button><el-button @click="shift(1)">下一周期</el-button></div><el-button type="primary" @click="openSchedule(days[0]?.key)">新增排班</el-button></div>
        <div class="schedule-board"><aside><div class="schedule-head">员工</div><div v-for="user in rows" :key="user.user_id" class="staff-chip" draggable="true" @dragstart="dragStart($event, user)">{{ user.real_name || user.username }}</div></aside><div class="schedule-days"><div class="schedule-head day-head"><span v-for="day in days" :key="day.key">{{ day.label }}</span></div><div class="schedule-grid"><div v-for="day in days" :key="day.key" class="schedule-cell" @dragover.prevent @drop="dropUser($event, day.key)" @dblclick="openSchedule(day.key)"><div v-for="item in listFor(day.key)" :key="item.schedule_id" class="shift-card"><b>{{ content(item).shift }}</b><small>{{ rows.find(user => user.user_id === Number(content(item).userId))?.real_name || content(item).userId }}</small><small>{{ content(item).startTime }}-{{ content(item).endTime }}</small></div><span v-if="!listFor(day.key).length" class="muted">拖拽员工到此</span></div></div></div></div></section>
    </el-tab-pane>
  </el-tabs>

  <el-dialog v-model="dialog" :title="editingId ? '编辑员工' : '新增员工'" width="660px" @closed="resetForm">
    <el-form ref="formRef" :model="form" :rules="rules" label-width="96px" status-icon><div class="staff-form-grid">
      <el-form-item label="姓名" prop="realName"><el-input v-model.trim="form.realName" maxlength="50" /></el-form-item><el-form-item label="账号" prop="username"><el-input v-model.trim="form.username" maxlength="20" @input="scheduleUsernameCheck"><template #suffix><span v-if="usernameState.checking" class="checking">校验中</span><span v-else-if="usernameState.available" class="available">可用</span></template></el-input></el-form-item>
      <el-form-item label="密码" prop="password"><el-input v-model="form.password" type="password" show-password :placeholder="editingId ? '留空表示不修改' : '6-20位字母和数字'" /></el-form-item><el-form-item label="确认密码" prop="confirmPassword"><el-input v-model="form.confirmPassword" type="password" show-password /></el-form-item>
      <el-form-item label="角色" prop="roleId"><el-select v-model="form.roleId" style="width:100%"><el-option v-for="role in visibleRoles" :key="role.role_id" :value="role.role_id" :label="staffRoleLabel(role)" /></el-select></el-form-item><el-form-item label="手机号" prop="phone"><el-input v-model.trim="form.phone" maxlength="11" /></el-form-item>
      <el-form-item label="所属门店" prop="storeId"><el-select v-model="form.storeId" style="width:100%"><el-option :value="auth.user?.store_id || auth.user?.storeId || 1" :label="auth.user?.store_name || auth.user?.storeName || '当前门店'" /></el-select></el-form-item><el-form-item label="入职日期" prop="entryDate"><el-date-picker v-model="form.entryDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
    </div><el-form-item label="账号状态"><el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="在职" inactive-text="禁用" /></el-form-item><el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="3" maxlength="500" show-word-limit /></el-form-item></el-form>
    <template #footer><el-button @click="dialog = false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template>
  </el-dialog>

  <el-dialog v-model="permissionDialog" :title="`${permissionUser?.real_name || permissionUser?.username || ''} - 配置个人权限`" width="620px"><div class="permission-toolbar"><span class="muted">权限只对当前员工生效；按模块勾选功能</span><div><el-button size="small" @click="permissionTreeRef?.setCheckedKeys([])">清空</el-button><el-button size="small" @click="selectAllPermissions">全选</el-button></div></div><el-tree ref="permissionTreeRef" :data="permissionTree" node-key="id" show-checkbox default-expand-all :props="{ label: 'label', children: 'children' }" class="permission-tree" /><template #footer><el-button @click="permissionDialog = false">取消</el-button><el-button type="primary" :loading="permissionSaving" @click="savePermissions">保存个人权限</el-button></template></el-dialog>

  <el-dialog v-model="scheduleDialog" title="排班安排" width="460px"><el-form label-width="90px"><el-form-item label="员工"><el-select v-model="scheduleForm.userId"><el-option v-for="user in rows" :key="user.user_id" :value="user.user_id" :label="user.real_name || user.username" /></el-select></el-form-item><el-form-item label="日期"><el-date-picker v-model="scheduleForm.date" value-format="YYYY-MM-DD" /></el-form-item><el-form-item label="班次"><el-select v-model="scheduleForm.shift"><el-option label="早班" value="早班" /><el-option label="晚班" value="晚班" /><el-option label="全天" value="全天" /></el-select></el-form-item><el-form-item label="开始"><el-time-select v-model="scheduleForm.startTime" start="07:00" step="00:30" end="22:00" /></el-form-item><el-form-item label="结束"><el-time-select v-model="scheduleForm.endTime" start="07:00" step="00:30" end="23:30" /></el-form-item><el-form-item label="备注"><el-input v-model="scheduleForm.remark" /></el-form-item></el-form><template #footer><el-button @click="scheduleDialog = false">取消</el-button><el-button type="primary" @click="saveSchedule">保存排班</el-button></template></el-dialog>
</template>

<style scoped>
.staff-form-grid { display:grid; grid-template-columns:1fr 1fr; gap:0 18px; }
.role-help { margin-top:5px; }
.checking { color:#909399; font-size:12px; }
.available { color:#36a878; font-size:12px; }
.permission-toolbar { display:flex; align-items:center; justify-content:space-between; gap:16px; padding-bottom:14px; border-bottom:1px solid #ebeef5; }
.permission-tree { margin-top:12px; max-height:480px; overflow:auto; }
:deep(.permission-tree > .el-tree-node) { border-bottom:1px solid #f0f2f5; padding:5px 0; }
</style>
