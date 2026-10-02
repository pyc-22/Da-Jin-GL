<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { goldApi } from '../api/modules'
import { formatMoney } from '../utils/format'
import { autoPricingIssue, pricingPreview, quoteMoney } from '../utils/goldPricing.js'
import { useAppStore } from '../stores/app'
import { useAuthStore } from '../stores/auth'

const app = useAppStore()
const auth = useAuthStore()
const current = ref([])
const history = ref([])
const types = ref([])
const logs = ref([])
const silverSpot = ref({})
const loading = ref(false)
const priceDialog = ref(false)
const typeDialog = ref(false)
const editingType = ref(null)
const savingType = ref(false)
const configError = ref('')
const quoteFor = instrument => current.value.find(row => row.baseInstrument === instrument) || {}
const typePreview = computed(() => { const quote = quoteFor(typeForm.baseInstrument); return { ...typeForm, basePrice: quote.basePrice, marketStatus: quote.marketStatus } })
const configWarning = computed(() => autoPricingIssue(typePreview.value))
const priceForm = reactive({ priceType: '', price: 0 })
const typeForm = reactive({ name: '', code: '', purity: 99.9, sort: 0, pricingMode: 'MANUAL', baseInstrument: 'Au_TD', purityCoefficient: 0.999, markup: 0, recycleDeduction: 0, roundingRule: 'NONE', status: 1 })
const roundingOptions = [{ value: 'NONE', label: '不取整' }, { value: 'TENTH', label: '到角' }, { value: 'YUAN', label: '到元' }, { value: 'TAIL_8', label: '尾数 .8' }, { value: 'TAIL_9', label: '尾数 .9' }]
const statusText = value => ({ OPEN: '开市', CLOSED: '休市', ERROR: '行情失败', FROZEN: '已冻结' }[String(value || '').toUpperCase()] || '未知')
const statusType = value => ({ OPEN: 'success', CLOSED: 'info', ERROR: 'danger', FROZEN: 'warning' }[String(value || '').toUpperCase()] || 'info')
const typeId = row => row?.type_id || row?.type_code || row?.code

async function load() {
  loading.value = true
  try {
    const typeRequest = auth.can('gold:manage') ? goldApi.typesAll() : goldApi.current()
    const [now, past, all, changeLogs, spot] = await Promise.all([goldApi.current(), goldApi.history({ days: 30 }), typeRequest, goldApi.logs({ limit: 200 }), goldApi.silverSpot().catch(() => ({}))])
    current.value = now || []; history.value = past || []; types.value = all || []; logs.value = changeLogs || []
    silverSpot.value = spot || {}
  } catch (error) { ElMessage.error(error?.message || '金价数据加载失败') } finally { loading.value = false }
}
async function applySilverSpot() {
  if (!Number(silverSpot.value.price)) return ElMessage.warning('白银实时参考价暂不可用')
  const silverType = current.value.find(row => String(row.price_type || '').trim() === '银回收价' || String(row.type_code || '').toUpperCase() === 'SILVER_RECYCLE')
  if (!silverType) return ElMessage.warning('请先配置银回收价类型')
  await goldApi.save({ priceType: silverType.price_type, price: Number(silverSpot.value.price) })
  ElMessage.success('白银实时参考价已作为手动价格保存'); await load()
}
function openPrice(row) { Object.assign(priceForm, { priceType: row?.price_type || row?.type_name || '', price: Number(row?.price || 0) }); priceDialog.value = true }
async function savePrice() { if (!priceForm.priceType || Number(priceForm.price) < 0) return ElMessage.warning('请填写有效价格'); await goldApi.save(priceForm); priceDialog.value = false; ElMessage.success('手动价格已保存并同步'); await load() }
function openType(row) {
  editingType.value = row || null
  configError.value = ''
  Object.assign(typeForm, row ? { name: row.type_name || row.name, code: row.type_code || row.code, purity: Number(row.purity || 100), sort: Number(row.sort || 0), pricingMode: row.pricingMode || 'MANUAL', baseInstrument: row.baseInstrument || 'Au_TD', purityCoefficient: Number(row.purityCoefficient || 1), markup: Number(row.markup || 0), recycleDeduction: Number(row.recycleDeduction || 0), roundingRule: row.roundingRule || 'NONE', status: Number(row.status ?? 1) } : { name: '', code: '', purity: 99.9, sort: types.value.length + 1, pricingMode: 'MANUAL', baseInstrument: 'Au_TD', purityCoefficient: 0.999, markup: 0, recycleDeduction: 0, roundingRule: 'NONE', status: 1 })
  typeDialog.value = true
}
function syncCoefficient() { if (typeForm.purity > 0 && typeForm.purity <= 100) typeForm.purityCoefficient = Number(typeForm.purity) / 100 }
async function saveType() {
  if (!String(typeForm.name).trim() || !String(typeForm.code).trim()) return ElMessage.warning('类型名称和编码不能为空')
  if (Number(typeForm.purityCoefficient) <= 0 || Number(typeForm.purityCoefficient) > 1) return ElMessage.warning('成色系数必须在0到1之间')
  if (Number(typeForm.markup) < 0 || Number(typeForm.recycleDeduction) < 0) return ElMessage.warning('加价和回收扣减不能小于0')
  configError.value = configWarning.value
  if (configError.value) return ElMessage.warning(configError.value)
  savingType.value = true
  try {
    const body = { ...typeForm, purity: Number(typeForm.purity), sort: Number(typeForm.sort), purityCoefficient: Number(typeForm.purityCoefficient), markup: Number(typeForm.markup), recycleDeduction: Number(typeForm.recycleDeduction), status: Number(typeForm.status) }
    if (editingType.value) await goldApi.updateType(typeId(editingType.value), body)
    else await goldApi.createType({ ...body, price: 0 })
    typeDialog.value = false; ElMessage.success('金类配置已保存'); await load()
  } catch (error) { configError.value = error?.message || '金类配置保存失败'; ElMessage.error(configError.value) } finally { savingType.value = false }
}
async function toggleType(row) { await goldApi.updateType(typeId(row), { status: Number(row.status) === 1 ? 0 : 1 }); ElMessage.success(Number(row.status) === 1 ? '已禁用' : '已启用'); await load() }
async function removeType(row) { try { await ElMessageBox.confirm(`确定删除“${row.type_name}”吗？已有业务关联时会自动禁用。`, '确认操作', { type: 'warning' }) } catch { return }; await goldApi.deleteType(typeId(row)); ElMessage.success('操作完成'); await load() }
async function resumeAuto(row) { const warning = autoPricingIssue({ ...row, pricingMode: 'AUTO' }); if (warning) { openType(row); typeForm.pricingMode = 'AUTO'; configError.value = warning; return ElMessage.warning(warning) }; try { await goldApi.updateType(typeId(row), { pricingMode: 'AUTO', resumeAuto: true }); ElMessage.success('已恢复自动定价'); await load() } catch (error) { openType(row); typeForm.pricingMode = 'AUTO'; configError.value = error?.message || '恢复失败' } }
onMounted(load)
watch(() => app.eventVersion, () => { if (['GOLD_PRICE_UPDATED', 'GOLD_TYPES_UPDATED'].includes(app.lastEventType)) load() })
</script>

<template>
  <section class="panel" v-loading="loading">
    <div class="page-toolbar"><div><strong>金银卖价与回收价</strong><p class="muted">自动定价只更新 AUTO 金类；单据创建时锁定当时价格。</p></div><el-button v-if="auth.can('gold:manage')" type="primary" @click="openType()">新增金类</el-button></div>
    <div class="reference-bar"><span>白银实时回收参考 · Ag(T+D) · 元/克 <b>{{ Number(silverSpot.price) ? formatMoney(silverSpot.price) : '—' }}</b></span><small>{{ statusText(silverSpot.market_status) }} · {{ silverSpot.source || '来源待同步' }} · {{ silverSpot.quote_time || '报价时间待同步' }}<span v-if="silverSpot.message" class="block">{{ silverSpot.message }}</span></small><el-button v-if="auth.can('gold:manage')" link type="warning" @click="applySilverSpot">按行情更新银回收价</el-button></div>
    <el-table :data="current" stripe>
      <el-table-column prop="price_type" label="金类" min-width="100" />
      <el-table-column label="卖价 /g" width="120"><template #default="s">{{ formatMoney(s.row.salePrice) }}</template></el-table-column>
      <el-table-column label="回收价 /g" width="120"><template #default="s">{{ formatMoney(s.row.recyclePrice) }}</template></el-table-column>
      <el-table-column label="基准价 /g" width="120"><template #default="s">{{ quoteMoney(s.row.basePrice) }}</template></el-table-column>
      <el-table-column label="定价" width="90"><template #default="s"><el-tag :type="s.row.pricingMode === 'AUTO' ? 'success' : 'info'">{{ s.row.pricingMode === 'AUTO' ? '自动' : '手动' }}</el-tag></template></el-table-column>
      <el-table-column label="行情" min-width="180"><template #default="s"><el-tag :type="statusType(s.row.marketStatus)">{{ statusText(s.row.marketStatus) }}</el-tag> {{ s.row.source || '—' }}<small class="block">{{ s.row.quoteTime || '—' }}</small><small class="block">{{ s.row.marketMessage }}</small><small v-if="auth.can('gold:manage') && s.row.pricingWarning" class="warning-note" role="alert">{{ s.row.pricingWarning }}</small></template></el-table-column>
      <el-table-column label="配置" min-width="150"><template #default="s">+{{ formatMoney(s.row.markup) }} / -{{ formatMoney(s.row.recycleDeduction) }}<small class="block">{{ s.row.baseInstrument }} · {{ Number(s.row.purityCoefficient || 0).toFixed(4) }}</small></template></el-table-column>
      <el-table-column v-if="auth.can('gold:manage')" label="操作" width="220"><template #default="s"><el-button link type="primary" @click="openType(s.row)">配置</el-button><el-button link @click="openPrice(s.row)">手动价格</el-button><el-button v-if="s.row.autoFrozen || s.row.marketStatus === 'FROZEN'" link type="warning" @click="resumeAuto(s.row)">恢复自动</el-button><el-button link type="danger" @click="removeType(s.row)">删除</el-button></template></el-table-column>
    </el-table>
    <div v-if="current.some(row => row.marketMessage)" class="warning-note">{{ current.find(row => row.marketMessage)?.marketMessage }}</div>
    <div v-if="!current.length" class="empty-table">暂无金类价格</div>
  </section>

  <section class="panel section-gap"><div class="panel-title">金类配置</div><el-table :data="types" stripe><el-table-column prop="type_name" label="名称"/><el-table-column prop="type_code" label="编码"/><el-table-column label="成色"><template #default="s">{{ Number(s.row.purity || 0).toFixed(1) }}%</template></el-table-column><el-table-column label="模式"><template #default="s">{{ s.row.pricingMode === 'AUTO' ? '自动' : '手动' }}</template></el-table-column><el-table-column prop="sort" label="排序" width="80"/><el-table-column label="状态" width="90"><template #default="s"><el-tag :type="Number(s.row.status) === 1 ? 'success' : 'info'">{{ Number(s.row.status) === 1 ? '启用' : '禁用' }}</el-tag></template></el-table-column><el-table-column v-if="auth.can('gold:manage')" label="操作" width="190"><template #default="s"><el-button link type="primary" @click="openType(s.row)">编辑</el-button><el-button link @click="toggleType(s.row)">{{ Number(s.row.status) === 1 ? '禁用' : '启用' }}</el-button><el-button link type="danger" @click="removeType(s.row)">删除</el-button></template></el-table-column></el-table></section>
  <section class="panel section-gap"><div class="panel-title">最近价格变更日志</div><el-table :data="logs" stripe max-height="420"><el-table-column prop="create_time" label="时间" width="170"/><el-table-column prop="price_type" label="金类" width="100"/><el-table-column label="基准 /g"><template #default="s">{{ quoteMoney(s.row.base_price) }} · {{ s.row.base_instrument || '—' }}</template></el-table-column><el-table-column label="卖价变化"><template #default="s">{{ formatMoney(s.row.old_sale_price) }} → {{ formatMoney(s.row.new_sale_price) }}</template></el-table-column><el-table-column label="回收变化"><template #default="s">{{ formatMoney(s.row.old_recycle_price) }} → {{ formatMoney(s.row.new_recycle_price) }}</template></el-table-column><el-table-column prop="source" label="来源" width="100"/></el-table></section>

  <el-dialog v-model="priceDialog" title="手动设置门店价格" width="420px"><el-form label-width="90px"><el-form-item label="金类"><el-select v-model="priceForm.priceType"><el-option v-for="row in types" :key="typeId(row)" :label="row.type_name" :value="row.type_name"/></el-select></el-form-item><el-form-item label="价格"><el-input-number v-model="priceForm.price" :min="0" :precision="2" /></el-form-item></el-form><template #footer><el-button @click="priceDialog=false">取消</el-button><el-button type="primary" @click="savePrice">保存</el-button></template></el-dialog>
  <el-dialog v-model="typeDialog" :title="editingType ? '编辑金类配置' : '新增金类配置'" width="560px"><el-form label-width="110px"><el-form-item label="名称"><el-input v-model="typeForm.name" /></el-form-item><el-form-item label="编码"><el-input v-model="typeForm.code" /></el-form-item><el-form-item label="成色"><el-input-number v-model="typeForm.purity" :min="0.1" :max="100" :precision="3" @change="syncCoefficient" /></el-form-item><el-form-item label="定价方式"><el-radio-group v-model="typeForm.pricingMode"><el-radio value="MANUAL">手动</el-radio><el-radio value="AUTO">自动</el-radio></el-radio-group></el-form-item><el-form-item label="基准品种"><el-select v-model="typeForm.baseInstrument"><el-option label="黄金 Au(T+D)" value="Au_TD"/><el-option label="白银 Ag(T+D)" value="Ag_TD"/></el-select></el-form-item><el-form-item label="成色系数"><el-input-number v-model="typeForm.purityCoefficient" :min="0.0001" :max="1" :precision="6" /></el-form-item><el-form-item label="卖价加价"><el-input-number v-model="typeForm.markup" :min="0" :precision="2" /></el-form-item><el-form-item label="回收扣减"><el-input-number v-model="typeForm.recycleDeduction" :min="0" :precision="2" /></el-form-item><el-form-item label="取整规则"><el-select v-model="typeForm.roundingRule"><el-option v-for="item in roundingOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item><el-form-item label="排序"><el-input-number v-model="typeForm.sort" :min="0" :precision="0" /></el-form-item></el-form><p v-if="typeForm.pricingMode === 'AUTO'" class="muted">基准 {{ quoteMoney(typePreview.basePrice) }}/g · 预览卖价 {{ quoteMoney(pricingPreview(typePreview)) }}/g · 回收 {{ quoteMoney(pricingPreview(typePreview, true)) }}/g</p><p v-if="configError || configWarning" class="warning-note" role="alert">{{ configError || configWarning }}</p><p class="muted">卖价 = 基准价 × 成色系数 + 加价；回收价 = 基准价 × 成色系数 − 回收扣减。行情休市、异常或冻结时不自动重算。</p><template #footer><el-button @click="typeDialog=false">取消</el-button><el-button type="primary" :loading="savingType" @click="saveType">保存配置</el-button></template></el-dialog>
</template>

<style scoped>
.section-gap { margin-top: 16px; }
.block { display: block; color: #8491a3; margin-top: 3px; }
.warning-note { margin-top: 12px; padding: 10px 12px; color: #8a5a00; background: #fff8e8; border: 1px solid #f0d18a; border-radius: 6px; }
.reference-bar { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; margin: 10px 0; padding: 10px 12px; background: #f7f9fc; border: 1px solid #dce2eb; border-radius: 6px; }
.reference-bar b { color: #44546a; margin-left: 8px; }
.reference-bar small { color: #8491a3; }
.muted { margin: 5px 0 0; color: #8491a3; font-size: 12px; }
</style>
