<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, ArrowUp, Edit, Plus, Trash2 } from 'lucide-vue-next'
import { systemApi } from '../api/modules'
import { formatOperationAction, formatOperationModule, formatTime } from '../utils/format'
import { useAppStore } from '../stores/app'

const app = useAppStore()
const store = reactive({ store_name: '', address: '', phone: '', logo: '' })
const configs = ref([])
const logs = ref([])
const templates = ref([])
const channels = ref([])
const backupBusy = ref(false)
const channelDialog = ref(false)
const channelSaving = ref(false)
const editingChannelId = ref(null)
const channelForm = reactive({ channelName: '', channelCode: '', sort: 1, status: 1, icon: '' })

function configKey(item) { return item?.config_key ?? item?.configKey }
const EDITABLE_CONFIG_KEYS = new Set([
  'discount_threshold',
  'recycle_approval_limit',
  'default_commission_rate',
  'gold_metal_types',
  'processing_loss_permille',
  'monthly_sales_target',
  'current_shift_no'
])
const systemConfigs = computed(() => configs.value.filter(item => EDITABLE_CONFIG_KEYS.has(configKey(item))))
const activeChannels = computed(() => channels.value.filter(row => Number(row.status) === 1))

async function load() {
  const [storeInfo, configRows, logRows, templateRows, channelRows] = await Promise.all([
    systemApi.store(), systemApi.config(), systemApi.logs({}), systemApi.templates(), systemApi.channels()
  ])
  Object.assign(store, storeInfo || {})
  configs.value = (configRows || []).map(item => ({
    ...item,
    config_id: item.config_id ?? item.configId,
    config_key: item.config_key ?? item.configKey,
    config_value: item.config_value ?? item.configValue,
    config_group: item.config_group ?? item.configGroup
  }))
  logs.value = logRows || []
  templates.value = templateRows || []
  channels.value = [...(channelRows || [])].sort((a, b) => Number(a.sort || 0) - Number(b.sort || 0) || Number(a.channel_id) - Number(b.channel_id))
  app.setPaymentChannels(channels.value)
}

async function saveStore() {
  await systemApi.updateStore({ storeName: store.store_name, address: store.address, phone: store.phone, logo: store.logo })
  ElMessage.success('门店信息已保存')
}

async function saveConfig(config) {
  const value = String(config.config_value ?? '').trim()
  if (!value) return ElMessage.warning(`请输入“${config.description || configKey(config)}”`)
  await systemApi.updateConfig({ configKey: configKey(config), configValue: value, configGroup: config.config_group, description: config.description })
  ElMessage.success('参数已保存')
}

function resetChannel() {
  editingChannelId.value = null
  Object.assign(channelForm, { channelName: '', channelCode: '', sort: channels.value.length + 1, status: 1, icon: '' })
}
function openChannel(row) {
  resetChannel()
  if (row) {
    editingChannelId.value = row.channel_id
    Object.assign(channelForm, { channelName: row.channel_name || '', channelCode: row.channel_code || '', sort: Number(row.sort || 0), status: Number(row.status) === 1 ? 1 : 0, icon: row.icon || '' })
  }
  channelDialog.value = true
}
async function saveChannel() {
  const editing = Boolean(editingChannelId.value)
  const name = channelForm.channelName.trim()
  const code = channelForm.channelCode.trim().toUpperCase()
  if (!name) return ElMessage.warning('请输入支付方式名称')
  if (!/^[A-Z][A-Z0-9_]{1,49}$/.test(code)) return ElMessage.warning('编码须为2-50位大写字母、数字或下划线，且以字母开头')
  channelSaving.value = true
  try {
    const payload = { channelName: name, channelCode: code, sort: Number(channelForm.sort), status: Number(channelForm.status), icon: channelForm.icon.trim() || null }
    if (editing) await systemApi.updateChannel(editingChannelId.value, payload)
    else await systemApi.createChannel(payload)
    channelDialog.value = false
    await load()
    ElMessage.success(editing ? '支付方式已更新' : '支付方式已新增')
  } catch (error) { ElMessage.error(error?.message || '支付方式保存失败') }
  finally { channelSaving.value = false }
}
async function toggleChannel(row) {
  const enabled = Number(row.status) === 1
  try {
    await systemApi.updateChannel(row.channel_id, { status: enabled ? 0 : 1 })
    await load()
    ElMessage.success(enabled ? '支付方式已停用' : '支付方式已启用')
  } catch (error) { ElMessage.error(error?.message || '支付方式状态更新失败') }
}
async function removeChannel(row) {
  try {
    await ElMessageBox.confirm(`确定删除“${row.channel_name}”吗？已有历史流水仍会保留编码。`, '删除支付方式', { type: 'warning', confirmButtonText: '确定删除', cancelButtonText: '取消' })
    await systemApi.deleteChannel(row.channel_id)
    await load()
    ElMessage.success('支付方式已删除')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(error?.message || '支付方式删除失败')
  }
}
async function moveChannel(row, offset) {
  const index = channels.value.findIndex(item => item.channel_id === row.channel_id)
  const target = channels.value[index + offset]
  if (!target) return
  try {
    const reordered = channels.value.slice()
    reordered.splice(index, 1)
    reordered.splice(index + offset, 0, row)
    for (const [position, channel] of reordered.entries()) {
      const nextSort = position + 1
      if (Number(channel.sort) !== nextSort) await systemApi.updateChannel(channel.channel_id, { sort: nextSort })
    }
    await load()
  } catch (error) { ElMessage.error(error?.message || '支付方式排序失败') }
}
async function backup() {
  if (backupBusy.value) return
  backupBusy.value = true
  try {
    const result = await systemApi.backup()
    ElMessage.success(`备份完成：${result.fileName}`)
  } catch (error) { ElMessage.error(error?.message || '备份失败') }
  finally { backupBusy.value = false }
}

onMounted(load)
watch(() => app.eventVersion, () => {
  if (['CONFIG_UPDATED', 'STORE_UPDATED', 'PAY_CHANNELS_UPDATED'].includes(app.lastEventType)) load()
})
</script>

<template>
  <div class="grid-2">
    <section class="panel">
      <div class="panel-title">门店信息</div>
      <el-form label-width="90px">
        <el-form-item label="门店名称"><el-input v-model="store.store_name" /></el-form-item>
        <el-form-item label="地址"><el-input v-model="store.address" /></el-form-item>
        <el-form-item label="电话"><el-input v-model="store.phone" /></el-form-item>
        <el-form-item label="Logo"><el-input v-model="store.logo" placeholder="图片地址（可选）" /></el-form-item>
        <el-button type="primary" @click="saveStore">保存门店信息</el-button>
      </el-form>
    </section>

    <section class="panel">
      <div class="panel-title system-panel-title"><span>系统参数</span><el-button size="small" :loading="backupBusy" @click="backup">手动备份</el-button></div>
      <el-form v-for="config in systemConfigs" :key="config.config_id || config.config_key" inline label-position="left">
        <el-form-item :label="config.description || config.config_key">
          <el-input v-model="config.config_value" :disabled="config.config_key === 'current_shift_no'" @blur="saveConfig(config)" />
        </el-form-item>
      </el-form>
    </section>
  </div>

  <section class="panel settings-section">
    <div class="panel-title"><span>支付方式</span><el-button type="primary" size="small" @click="openChannel()"><Plus :size="14" />新增支付方式</el-button></div>
    <div class="settings-note">启用后会实时同步到收银端；收银端重新登录时也会自动重新拉取。</div>
    <el-table :data="channels" row-key="channel_id">
      <el-table-column label="排序" width="150">
        <template #default="scope"><div class="sort-actions"><span>{{ scope.row.sort }}</span><el-button text circle :disabled="scope.$index === 0" title="上移" @click="moveChannel(scope.row, -1)"><ArrowUp :size="15" /></el-button><el-button text circle :disabled="scope.$index === channels.length - 1" title="下移" @click="moveChannel(scope.row, 1)"><ArrowDown :size="15" /></el-button></div></template>
      </el-table-column>
      <el-table-column prop="channel_name" label="支付方式" min-width="150" />
      <el-table-column prop="channel_code" label="内部编码" min-width="150" />
      <el-table-column label="状态" width="100"><template #default="scope"><el-tag :type="Number(scope.row.status) === 1 ? 'success' : 'info'">{{ Number(scope.row.status) === 1 ? '启用' : '停用' }}</el-tag></template></el-table-column>
      <el-table-column label="操作" width="230" fixed="right"><template #default="scope"><el-button link type="primary" @click="openChannel(scope.row)"><Edit :size="14" />编辑</el-button><el-button link :type="Number(scope.row.status) === 1 ? 'warning' : 'success'" @click="toggleChannel(scope.row)">{{ Number(scope.row.status) === 1 ? '停用' : '启用' }}</el-button><el-button link type="danger" @click="removeChannel(scope.row)"><Trash2 :size="14" />删除</el-button></template></el-table-column>
    </el-table>
    <el-empty v-if="!activeChannels.length" description="当前没有启用的支付方式，收银端将无法完成收款" />
  </section>

  <section class="panel settings-section">
    <div class="panel-title">打印模板</div>
    <el-table :data="templates">
      <el-table-column prop="name" label="模板名称" />
      <el-table-column prop="type" label="模板类型" />
      <el-table-column prop="is_default" label="默认"><template #default="scope">{{ Number(scope.row.is_default) === 1 ? '是' : '否' }}</template></el-table-column>
    </el-table>
  </section>

  <section class="panel settings-section">
    <div class="panel-title">操作日志</div>
    <el-table :data="logs">
      <el-table-column label="时间" min-width="170"><template #default="scope">{{ formatTime(scope.row.create_time) }}</template></el-table-column>
      <el-table-column label="模块" width="130"><template #default="scope">{{ formatOperationModule(scope.row.module) }}</template></el-table-column>
      <el-table-column label="动作" width="120"><template #default="scope">{{ formatOperationAction(scope.row.action) }}</template></el-table-column>
      <el-table-column prop="content" label="操作内容" min-width="280" />
      <el-table-column prop="ip" label="来源地址" width="140" />
    </el-table>
  </section>

  <el-dialog v-model="channelDialog" :title="editingChannelId ? '编辑支付方式' : '新增支付方式'" width="500px" @closed="resetChannel">
    <el-form label-width="100px">
      <el-form-item label="支付方式" required><el-input v-model.trim="channelForm.channelName" maxlength="50" placeholder="例如：云闪付" /></el-form-item>
      <el-form-item label="内部编码" required><el-input v-model.trim="channelForm.channelCode" maxlength="50" placeholder="例如：UNIONPAY" /></el-form-item>
      <el-form-item label="排序"><el-input-number v-model="channelForm.sort" :min="0" :max="9999" controls-position="right" /></el-form-item>
      <el-form-item label="状态"><el-switch v-model="channelForm.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用" /></el-form-item>
      <el-form-item label="图标标识"><el-input v-model.trim="channelForm.icon" maxlength="255" placeholder="可选，收银端暂无对应图标时使用通用图标" /></el-form-item>
    </el-form>
    <template #footer><el-button @click="channelDialog = false">取消</el-button><el-button type="primary" :loading="channelSaving" @click="saveChannel">保存</el-button></template>
  </el-dialog>
</template>

<style scoped>
.settings-note { color: #8491a3; font-size: 12px; margin: -8px 0 12px; }
.sort-actions { display: flex; align-items: center; gap: 3px; }
.sort-actions span { width: 26px; text-align: center; }
.settings-section { margin-top: 16px; }
</style>
