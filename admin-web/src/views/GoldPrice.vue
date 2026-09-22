<script setup>
import { nextTick, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { goldApi } from '../api/modules'
import { formatMoney, formatTime } from '../utils/format'
import { useAppStore } from '../stores/app'
const app=useAppStore()

const current = ref([])
const history = ref([])
const types = ref([])
const priceDialog = ref(false)
const typeDialog = ref(false)
const editingType = ref(null)
const typeFormRef = ref()
const savingType = ref(false)
const priceForm = reactive({ priceType: '', price: 0 })
const typeForm = reactive({ name: '', code: '', purity: 99.9, price: 0, sort: 0 })
const requiredText = label => ({ validator: (_rule, value, callback) => String(value || '').trim() ? callback() : callback(new Error(`请输入${label}`)), trigger: 'blur' })
const typeRules = {
  name: [requiredText('类型名称')],
  code: [requiredText('类型编码')],
  purity: [{ validator: (_rule, value, callback) => Number(value) > 0 && Number(value) <= 100 ? callback() : callback(new Error('成色必须在0到100之间')), trigger: 'change' }],
  price: [{ validator: (_rule, value, callback) => Number(value) >= 0 ? callback() : callback(new Error('价格不能小于0')), trigger: 'change' }],
  sort: [{ validator: (_rule, value, callback) => Number.isInteger(Number(value)) && Number(value) >= 0 ? callback() : callback(new Error('排序必须是非负整数')), trigger: 'change' }]
}

async function load() {
  const [now, past, all] = await Promise.all([goldApi.current(), goldApi.history({ days: 30 }), goldApi.typesAll()])
  current.value = now || []; history.value = past || []; types.value = all || []
}
function openPrice(row) { Object.assign(priceForm, { priceType: row?.price_type || row?.type_name || '', price: Number(row?.price || 0) }); priceDialog.value = true }
async function savePrice() { if (!priceForm.priceType) return ElMessage.warning('请选择贵金属类型'); await goldApi.save(priceForm); priceDialog.value = false; ElMessage.success('金价已保存并推送'); await load() }
function openType(row) { editingType.value = row || null; Object.assign(typeForm, row ? { name: row.type_name, code: row.type_code, purity: Number(row.purity), price: Number(row.price ?? row.current_price ?? 0), sort: Number(row.sort || 0) } : { name: '', code: '', purity: 99.9, price: 0, sort: types.value.length + 1 }); typeDialog.value = true; nextTick(() => typeFormRef.value?.clearValidate()) }
async function saveType() {
  try { await typeFormRef.value.validate() } catch { return }
  savingType.value = true
  try {
    if (editingType.value) await goldApi.updateType(editingType.value.type_id, { ...typeForm })
    else await goldApi.createType({ ...typeForm })
    typeDialog.value = false
    ElMessage.success(editingType.value ? '类型已更新' : '类型已新增')
    await load()
  } catch (error) {
    if (!error?.messageShown) ElMessage.error(error?.message || '贵金属类型保存失败')
  } finally {
    savingType.value = false
  }
}
async function removeType(row) {
  try { await ElMessageBox.confirm(`确定删除“${row.type_name}”吗？有历史或商品关联时系统会自动禁用。`, '确认操作', { type: 'warning' }) } catch { return }
  const result = await goldApi.deleteType(row.type_id)
  ElMessage.success(result?.disabled ? '该类型已有业务关联，已禁用' : '类型已删除'); await load()
}
async function toggleType(row) { await goldApi.updateType(row.type_id, { status: Number(row.status) === 1 ? 0 : 1 }); ElMessage.success(Number(row.status) === 1 ? '类型已禁用' : '类型已启用'); await load() }
onMounted(load)
const spot = ref({})
const silverSpot = ref({})
const spotLoading = ref(false)
const silverSpotLoading = ref(false)
async function loadSpot() {
  spotLoading.value = true
  try { spot.value = await goldApi.spot() || {} } catch { spot.value = { price: null, message: '行情获取失败，请手动填写金价' } } finally { spotLoading.value = false }
}
async function applySpot() {
  if (!Number(spot.value.price)) return
  try { await ElMessageBox.confirm(`将「足金」卖价更新为行情参考价 ${formatMoney(spot.value.price)}/g？确认后同步收银台与手机端。`, '按行情更新足金价', { type: 'warning' }) } catch { return }
  await goldApi.save({ priceType: '足金', price: Number(spot.value.price) })
  ElMessage.success('足金价已按行情更新并推送'); await load()
}
async function loadSilverSpot() {
  silverSpotLoading.value = true
  try { silverSpot.value = await goldApi.silverSpot() || {} } catch { silverSpot.value = { price: null, message: '白银行情获取失败，请保留门店手动价格' } } finally { silverSpotLoading.value = false }
}
async function applySilverSpot() {
  if (!Number(silverSpot.value.price)) return
  const silverType = current.value.find(row => String(row.price_type || '').trim() === '银回收价' || String(row.type_code || row.code || '').toUpperCase() === 'SILVER_RECYCLE')
  if (!silverType) return ElMessage.warning('请先新增并启用“银回收价”类型')
  const priceType = silverType.price_type
  try { await ElMessageBox.confirm(`将「${priceType}」更新为白银实时回收参考价 ${formatMoney(silverSpot.value.price)}/g？确认后同步收银台与手机端。`, '按行情更新银回收价', { type: 'warning' }) } catch { return }
  await goldApi.save({ priceType, price: Number(silverSpot.value.price) })
  ElMessage.success('银回收价已按实时行情更新并推送'); await load()
}
onMounted(loadSpot)
onMounted(loadSilverSpot)
watch(()=>app.eventVersion,()=>{if(['GOLD_PRICE_UPDATED','GOLD_TYPES_UPDATED'].includes(app.lastEventType)){load();loadSpot();loadSilverSpot()}})
</script>
<template>
  <section class="panel">
    <div class="spot-grid">
      <div class="spot-bar">
        <div class="spot-copy">
          <div class="muted">黄金实时行情参考（{{ spot.name || '上海黄金 Au9999' }}）· 元/克</div>
          <div><strong>{{ Number(spot.price) ? formatMoney(spot.price) : '—' }}<small v-if="Number(spot.price)">/g</small></strong><small class="muted spot-meta">{{ Number(spot.price) ? `${spot.source || ''} · ${spot.time || ''}` : (spot.message || '加载中...') }}</small></div>
        </div>
        <div class="spot-actions"><el-button @click="loadSpot" :loading="spotLoading">刷新黄金</el-button><el-button v-if="Number(spot.price)" type="warning" @click="applySpot">按行情更新足金价</el-button></div>
      </div>
      <div class="spot-bar silver-spot">
        <div class="spot-copy">
          <div class="muted">白银实时回收参考（{{ silverSpot.name || '白银延期 Ag(T+D)' }}）· 已换算为元/克</div>
          <div><strong>{{ Number(silverSpot.price) ? formatMoney(silverSpot.price) : '—' }}<small v-if="Number(silverSpot.price)">/g</small></strong><small class="muted spot-meta">{{ Number(silverSpot.price) ? `${silverSpot.source || ''} · ${silverSpot.time || ''}` : (silverSpot.message || '加载中...') }}</small></div>
        </div>
        <div class="spot-actions"><el-button @click="loadSilverSpot" :loading="silverSpotLoading">刷新白银</el-button><el-button v-if="Number(silverSpot.price)" type="warning" @click="applySilverSpot">按行情更新银回收价</el-button></div>
      </div>
    </div>
    <div class="page-toolbar"><div class="muted">启用类型会同步到收银台和手机端计价选择</div><el-button type="primary" @click="openType()">新增类型</el-button></div>
    <div class="gold-card"><div v-for="row in current" :key="row.type_id || row.price_type" class="panel gold-item"><span>{{ row.price_type }}</span><strong>{{ formatMoney(row.price) }}<small>/g</small></strong><el-button link type="primary" @click="openPrice(row)">修改价格</el-button></div><div v-if="!current.length" class="empty-table">暂无启用的金价类型</div></div>
  </section>
  <section class="panel" style="margin-top:16px"><div class="panel-title">贵金属类型管理</div><el-table :data="types" stripe><el-table-column prop="type_name" label="类型名称"/><el-table-column prop="type_code" label="类型编码"/><el-table-column label="成色"><template #default="s">{{ Number(s.row.purity).toFixed(1) }}%</template></el-table-column><el-table-column label="当前价格"><template #default="s">{{ formatMoney(s.row.price ?? s.row.current_price) }}</template></el-table-column><el-table-column prop="sort" label="排序" width="80"/><el-table-column label="状态" width="90"><template #default="s"><el-tag :type="Number(s.row.status) === 1 ? 'success' : 'info'">{{ Number(s.row.status) === 1 ? '启用' : '禁用' }}</el-tag></template></el-table-column><el-table-column label="操作" width="220"><template #default="s"><el-button link type="primary" @click="openType(s.row)">编辑</el-button><el-button link :type="Number(s.row.status) === 1 ? 'warning' : 'success'" @click="toggleType(s.row)">{{ Number(s.row.status) === 1 ? '禁用' : '启用' }}</el-button><el-button link type="danger" @click="removeType(s.row)">删除</el-button></template></el-table-column></el-table></section>
  <section class="panel" style="margin-top:16px"><div class="panel-title">金价历史</div><el-table :data="history" stripe><el-table-column prop="date" label="日期"/><el-table-column prop="price_type" label="类型"/><el-table-column label="价格"><template #default="s">{{ formatMoney(s.row.price) }}</template></el-table-column><el-table-column label="更新时间"><template #default="s">{{ formatTime(s.row.create_time) }}</template></el-table-column></el-table></section>
  <el-dialog v-model="priceDialog" title="修改金价" width="420px"><el-form label-width="90px"><el-form-item label="金价类型"><el-select v-model="priceForm.priceType"><el-option v-for="row in current" :key="row.type_id || row.price_type" :label="row.price_type" :value="row.price_type"/></el-select></el-form-item><el-form-item label="价格"><el-input-number v-model="priceForm.price" :min="0" :precision="2"/></el-form-item></el-form><template #footer><el-button @click="priceDialog=false">取消</el-button><el-button type="primary" @click="savePrice">保存并推送</el-button></template></el-dialog>
  <el-dialog v-model="typeDialog" :title="editingType ? '编辑贵金属类型' : '新增贵金属类型'" width="480px"><el-form ref="typeFormRef" :model="typeForm" :rules="typeRules" label-width="90px" status-icon><el-form-item label="类型名称" prop="name" required><el-input v-model="typeForm.name" placeholder="例如：钯金"/></el-form-item><el-form-item label="类型编码" prop="code" required><el-input v-model="typeForm.code" placeholder="例如：PALLADIUM"/></el-form-item><el-form-item label="成色" prop="purity"><el-input-number v-model="typeForm.purity" :min="0.1" :max="100" :precision="1"/></el-form-item><el-form-item label="初始价格" prop="price"><el-input-number v-model="typeForm.price" :min="0" :precision="2"/></el-form-item><el-form-item label="排序" prop="sort"><el-input-number v-model="typeForm.sort" :min="0" :precision="0"/></el-form-item></el-form><template #footer><el-button @click="typeDialog=false">取消</el-button><el-button type="primary" :loading="savingType" @click="saveType">保存</el-button></template></el-dialog>
</template>

<style scoped>
.spot-grid { display:grid; gap:10px; margin-bottom:14px; }
.spot-bar { display:flex; align-items:center; justify-content:space-between; gap:16px; flex-wrap:wrap; padding:12px 16px; border:1px solid #efd9a9; border-radius:8px; background:#faf6ee; }
.spot-bar.silver-spot { border-color:#d8dee8; background:#f7f9fc; }
.spot-copy { min-width:280px; flex:1; font-size:13px; }
.spot-copy strong { color:#a66b2c; font-size:20px; }
.silver-spot .spot-copy strong { color:#44546a; }
.spot-copy strong small { font-size:12px; }
.spot-meta { margin-left:8px; }
.spot-actions { display:flex; gap:8px; flex-wrap:wrap; }
@media (max-width: 720px) { .spot-actions { width:100%; } .spot-actions .el-button { flex:1; margin-left:0; } .spot-meta { display:block; margin:4px 0 0; } }
</style>
