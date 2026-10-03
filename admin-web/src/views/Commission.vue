<script setup>
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as XLSX from 'xlsx'
import { commissionApi } from '../api/modules'
import { useAppStore } from '../stores/app'
import { useAuthStore } from '../stores/auth'
import { formatMoney } from '../utils/format'

const app = useAppStore()
const auth = useAuthStore()
const rules = ref([])
const records = ref([])
const settings = reactive({ salesPercent: 2, processingPercent: 1 })
const savingSettings = ref(false)

async function load() {
  const [ruleRows, recordRows, rateSettings] = await Promise.all([commissionApi.rules(), commissionApi.records({}), commissionApi.settings()])
  rules.value = ruleRows || []
  records.value = recordRows || []
  settings.salesPercent = Number(rateSettings?.defaultCommissionRate ?? 0.02) * 100
  settings.processingPercent = Number(rateSettings?.processingSalesCommissionRate ?? 0.01) * 100
}

async function saveSettings() {
  if (!auth.can('commission:manage')) return
  if (settings.salesPercent < 0 || settings.salesPercent > 100 || settings.processingPercent < 0 || settings.processingPercent > 100) return ElMessage.warning('提成比例必须在 0% 到 100% 之间')
  try {
    savingSettings.value = true
    await commissionApi.updateSettings({ defaultCommissionRate: Number(settings.salesPercent) / 100, processingSalesCommissionRate: Number(settings.processingPercent) / 100 })
    ElMessage.success('提成比例已保存')
    await load()
  } catch (error) { ElMessage.error(error?.message || '提成比例保存失败') } finally { savingSettings.value = false }
}

async function calculate() {
  if (!auth.can('commission:manage')) return
  try { await commissionApi.calculate({}); ElMessage.success('已重新核算'); await load() } catch (error) { ElMessage.error(error?.message || '重新核算失败') }
}

function exportXlsx() {
  const rows = records.value.map(row => ({
    员工: row.real_name || row.employee_name || '-', 月份: row.month,
    成品销售实收: Number(row.sales_amount || 0), 加工工费基数: Number(row.processing_base || 0),
    成品销售提成: Number(row.sales_commission || 0) || Math.max(0, Number(row.commission_amount || 0) - Number(row.processing_commission || 0)),
    加工导购提成: Number(row.processing_commission || 0), 提成合计: Number(row.commission_amount || 0)
  }))
  const ws = XLSX.utils.json_to_sheet(rows); const wb = XLSX.utils.book_new(); XLSX.utils.book_append_sheet(wb, ws, '工资表'); XLSX.writeFile(wb, '提成工资表.xlsx')
}

const ruleDialog = ref(false)
const editingRule = ref(null)
const ruleForm = reactive({ type: 'SALE', rate: 0.02, category: '', minAmount: '', maxAmount: '', description: '', status: 1 })
function openRule(rule = null) {
  editingRule.value = rule
  const condition = rule ? (() => { try { return JSON.parse(rule.condition || '{}') } catch { return {} } })() : {}
  Object.assign(ruleForm, { type: rule?.type || 'SALE', rate: Number(rule?.rate ?? 0.02), category: condition.category || '', minAmount: condition.minAmount ?? '', maxAmount: condition.maxAmount ?? '', description: rule?.description || '', status: Number(rule?.status ?? 1) })
  ruleDialog.value = true
}
async function saveRule() {
  if (!auth.can('commission:manage')) return
  const condition = JSON.stringify(Object.fromEntries(Object.entries({ category: ruleForm.category, minAmount: ruleForm.minAmount, maxAmount: ruleForm.maxAmount }).filter(([, value]) => value !== '' && value != null)))
  try { await (editingRule.value ? commissionApi.update(editingRule.value.rule_id, { type: ruleForm.type, rate: Number(ruleForm.rate), condition, description: ruleForm.description, status: ruleForm.status }) : commissionApi.save({ type: ruleForm.type, rate: Number(ruleForm.rate), condition, description: ruleForm.description })); ruleDialog.value = false; ElMessage.success('提成规则已保存'); await load() } catch (error) { ElMessage.error(error?.message || '提成规则保存失败') }
}
watch(() => app.eventVersion, () => { if (['COMMISSION_UPDATED', 'REPORT_UPDATED'].includes(app.lastEventType)) load() })
onMounted(load)
</script>

<template>
  <section class="panel commission-settings">
    <div class="page-toolbar"><div><h2 class="panel-title">导购提成比例</h2><p class="muted">修改仅对之后结清/取货的单据生效，历史单据按当时快照比例不变。</p></div><div class="inline-actions"><el-button v-if="auth.can('commission:manage')" :loading="savingSettings" type="primary" @click="saveSettings">保存比例</el-button><el-button v-if="auth.can('commission:manage')" @click="calculate">重新核算</el-button><el-button @click="exportXlsx">导出工资表</el-button></div></div>
    <div class="rate-grid"><div class="rate-item"><span>成品销售提成率</span><strong><el-input-number v-model="settings.salesPercent" :min="0" :max="100" :precision="2" :disabled="!auth.can('commission:manage')"/><em>%</em></strong><small>按成品销售财务流水实收总额计算，默认 2%</small></div><div class="rate-item"><span>加工导购提成率</span><strong><el-input-number v-model="settings.processingPercent" :min="0" :max="100" :precision="2" :disabled="!auth.can('commission:manage')"/><em>%</em></strong><small>按加工取货结清后的工费净额计算，不含加金/料钱，默认 1%</small></div></div>
  </section>
  <section class="panel reserved-rules"><div class="page-toolbar"><div><h2 class="panel-title">提成阶梯/分类规则</h2><p class="muted">当前固定比例继续作为默认值；启用且匹配业务类型、分类和金额区间的规则优先生效。</p></div><el-button v-if="auth.can('commission:manage')" type="primary" @click="openRule()">新增规则</el-button></div><el-table :data="rules"><el-table-column prop="type" label="业务类型"/><el-table-column prop="rate" label="比例"><template #default="s">{{ (Number(s.row.rate) * 100).toFixed(2) }}%</template></el-table-column><el-table-column label="条件"><template #default="s">{{ s.row.condition || '{}' }}</template></el-table-column><el-table-column prop="description" label="说明"/><el-table-column prop="status" label="状态"><template #default="s">{{ Number(s.row.status) === 1 ? '启用' : '停用' }}</template></el-table-column><el-table-column v-if="auth.can('commission:manage')" label="操作"><template #default="s"><el-button link @click="openRule(s.row)">编辑</el-button></template></el-table-column></el-table></section>
  <el-dialog v-model="ruleDialog" title="提成规则" width="520px"><el-form label-width="90px"><el-form-item label="业务类型"><el-select v-model="ruleForm.type"><el-option label="成品销售" value="SALE"/><el-option label="加工导购" value="PROCESSING"/></el-select></el-form-item><el-form-item label="提成比例"><el-input-number v-model="ruleForm.rate" :min="0" :max="1" :precision="4"/></el-form-item><el-form-item label="商品分类"><el-input v-model="ruleForm.category" placeholder="可留空，匹配全部分类"/></el-form-item><el-form-item label="金额区间"><el-input v-model="ruleForm.minAmount" placeholder="最低金额"/><el-input v-model="ruleForm.maxAmount" placeholder="最高金额"/></el-form-item><el-form-item label="说明"><el-input v-model="ruleForm.description"/></el-form-item><el-form-item v-if="editingRule" label="状态"><el-switch v-model="ruleForm.status" :active-value="1" :inactive-value="0"/></el-form-item></el-form><template #footer><el-button @click="ruleDialog=false">取消</el-button><el-button type="primary" @click="saveRule">保存</el-button></template></el-dialog>
  <section class="panel commission-records"><div class="panel-title">提成明细与工资表</div><el-table :data="records"><el-table-column prop="real_name" label="员工"/><el-table-column prop="month" label="月份"/><el-table-column label="成品销售实收" align="right"><template #default="s">{{ formatMoney(s.row.sales_amount) }}</template></el-table-column><el-table-column label="加工工费基数" align="right"><template #default="s">{{ formatMoney(s.row.processing_base) }}</template></el-table-column><el-table-column label="成品销售提成" align="right"><template #default="s">{{ formatMoney(s.row.sales_commission ?? Math.max(0, Number(s.row.commission_amount || 0) - Number(s.row.processing_commission || 0))) }}</template></el-table-column><el-table-column label="加工导购提成" align="right"><template #default="s">{{ formatMoney(s.row.processing_commission) }}</template></el-table-column><el-table-column label="提成合计" align="right"><template #default="s">{{ formatMoney(s.row.commission_amount) }}</template></el-table-column></el-table></section>
</template>

<style scoped>
.commission-settings,.reserved-rules,.commission-records{margin-bottom:16px}.page-toolbar,.inline-actions{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}.panel-title{margin:0}.muted{margin:4px 0 0;color:var(--el-text-color-secondary);font-size:13px}.rate-grid{display:grid;grid-template-columns:repeat(2,minmax(260px,1fr));gap:14px}.rate-item{border:1px solid var(--el-border-color-lighter);border-radius:6px;padding:16px;display:grid;gap:10px}.rate-item>span{font-weight:600}.rate-item strong{display:flex;align-items:center;gap:8px}.rate-item em{font-style:normal}.rate-item small{color:var(--el-text-color-secondary)}@media(max-width:720px){.rate-grid{grid-template-columns:1fr}}
</style>
