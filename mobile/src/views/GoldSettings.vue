<template>
  <div class="shell gold-settings-page">
    <main class="content page">
      <DesignHeader title="金价设置" subtitle="行情、加价与回收扣减同步至全端" eyebrow="PRICING" :back="true" @back="router.back()">
        <button class="outline" :disabled="loading" aria-label="刷新行情" @click="load">刷新</button>
      </DesignHeader>
      <p v-if="!isAdmin" class="error permission-note">仅管理员可查看和修改完整金价配置。</p>
      <p v-else-if="!auth.can('gold:manage')" class="error permission-note">当前账号没有金价管理权限。</p>
      <template v-else>
        <p v-if="remoteChanged" class="pricing-alert" role="alert">配置已在其他终端更新，请先刷新后再保存；当前输入已保留。</p>
        <section class="market-settings-card">
          <div class="market-settings-heading"><div><span class="eyebrow">MARKET</span><h2>行情来源</h2></div><span class="market-live" :class="marketClass">● {{ marketStatusLabel }}</span></div>
          <div class="market-settings-values"><div><small>黄金基准</small><strong>{{ quoteMoney(spotPrice) }}/g</strong></div><div><small>来源</small><strong>{{ sourceLabel }}</strong></div><div><small>报价时间</small><strong>{{ quoteTime || '等待同步' }}</strong></div></div>
          <p class="market-settings-note">{{ marketMessage || '服务端行情将按开市状态自动刷新。' }}</p>
        </section>
        <article v-for="row in types" :key="row.type_id || row.type_code" class="panel gold-type-card">
          <div class="setting-head"><div><span class="eyebrow">{{ row.type_code || 'GOLD' }}</span><h2>{{ row.type_name }}</h2><small>{{ row.baseInstrument === 'Ag_TD' ? '白银 Ag(T+D)' : '黄金 Au(T+D)' }} · 基准 {{ quoteMoney(marketFor(row).basePrice) }}/g</small></div><span class="status" :class="statusClass(marketFor(row).marketStatus)">● {{ statusText(marketFor(row).marketStatus) }}</span></div>
          <div class="quote-line"><div><small>最后卖价</small><b>¥{{ money(row.salePrice) }}/g</b></div><div><small>最后回收价</small><b>¥{{ money(row.recyclePrice) }}/g</b></div></div>
          <div class="setting-block"><div class="setting-label"><strong>定价方式</strong><small>自动跟随行情，手动保留金类独立价格</small></div><div class="seg mode-switch"><button :class="{active: row.pricingMode === 'AUTO'}" @click="row.pricingMode='AUTO'">AUTO 自动</button><button :class="{active: row.pricingMode === 'MANUAL'}" @click="row.pricingMode='MANUAL'">MANUAL 手动</button></div></div>
          <div v-if="row.pricingMode === 'AUTO'" class="setting-grid">
            <div class="setting-control"><span>卖价加价</span><div class="stepper"><button aria-label="减少卖价加价" @click="step(row,'markup',-0.1)">−</button><input v-model.number="row.markup" type="number" min="0" step="0.01"><button aria-label="增加卖价加价" @click="step(row,'markup',0.1)">＋</button></div></div>
            <div class="setting-control"><span>回收扣减</span><div class="stepper"><button aria-label="减少回收扣减" @click="step(row,'recycleDeduction',-0.1)">−</button><input v-model.number="row.recycleDeduction" type="number" min="0" step="0.01"><button aria-label="增加回收扣减" @click="step(row,'recycleDeduction',0.1)">＋</button></div></div>
            <label class="setting-control">成色系数<input v-model.number="row.purityCoefficient" type="number" min="0.0001" max="1" step="0.000001"></label>
            <label class="setting-control">基准品种<select v-model="row.baseInstrument"><option value="Au_TD">黄金 Au(T+D)</option><option value="Ag_TD">白银 Ag(T+D)</option></select></label>
          </div>
          <div v-else class="manual-price-card"><div><span>手动卖价</span><strong>¥{{ money(row.salePrice) }}/g</strong></div><label>卖价<input v-model.number="row.salePrice" type="number" min="0" step="0.01"></label><label>回收价<input v-model.number="row.recyclePrice" type="number" min="0" step="0.01"></label></div>
          <div class="rounding-control"><span>取整规则</span><div class="chips"><button v-for="option in roundingOptions" :key="option.value" :class="{active: row.roundingRule === option.value}" @click="row.roundingRule=option.value">{{ option.label }}</button></div></div>
          <div class="formula-preview"><p v-if="row.pricingMode === 'MANUAL'">手动定价，保留门店设置的卖价与回收价。</p><p v-else-if="!hasQuote(marketFor(row).basePrice)">暂无有效基准行情，现价保持不变</p><template v-else><small>实时公式预览</small><strong>卖价 = {{ money(marketFor(row).basePrice) }} × {{ Number(row.purityCoefficient || 0).toFixed(4) }} + {{ money(row.markup) }} = ¥{{ money(pricingPreview(marketFor(row), false)) }}/g</strong><strong>回收 = {{ money(marketFor(row).basePrice) }} × {{ Number(row.purityCoefficient || 0).toFixed(4) }} − {{ money(row.recycleDeduction) }} = ¥{{ money(pricingPreview(marketFor(row), true)) }}/g</strong></template></div>
          <p v-if="errors[row.type_id || row.type_code] || issue(row)" class="pricing-alert" role="alert">{{ errors[row.type_id || row.type_code] || issue(row) }}</p>
          <div class="meta">{{ marketFor(row).source || '行情来源待同步' }} · {{ marketFor(row).quoteTime || '报价时间待同步' }} · {{ marketFor(row).marketMessage || statusText(marketFor(row).marketStatus) }}</div>
          <div class="actions"><button class="primary full" :disabled="saving === (row.type_id || row.type_code)" @click="save(row)">{{ saving === (row.type_id || row.type_code) ? '保存并同步中…' : '保存并同步全端' }}</button><button v-if="row.autoFrozen || row.marketStatus === 'FROZEN'" class="outline full" @click="resume(row)">恢复自动定价</button></div>
        </article>
        <EmptyState v-if="!types.length" title="暂无可配置金类" message="请先在管理端配置金类与行情来源" />
        <section class="panel logs"><div class="panel-head"><h2>最近价格变更</h2><small>保留最近 100 条</small></div><div v-for="log in logs" :key="log.log_id" class="log-row"><span>{{ log.price_type }} · {{ log.create_time }}</span><small>卖价 {{ money(log.old_sale_price) }} → {{ money(log.new_sale_price) }}；回收 {{ money(log.old_recycle_price) }} → {{ money(log.new_recycle_price) }} · {{ log.source || '—' }}</small></div><EmptyState v-if="!logs.length" title="暂无变更日志" /></section>
      </template>
    </main>
    <Transition name="toast"><div v-if="toast" class="toast">{{ toast }}</div></Transition>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth.js'
import { useAppStore } from '../stores/app.js'
import { api } from '../api/request.js'
import { autoPricingIssue, hasQuote, pricingPreview, quoteMoney } from '../utils/goldPricing.js'
import DesignHeader from '../components/DesignHeader.vue'
import EmptyState from '../components/EmptyState.vue'

const router = useRouter(), auth = useAuthStore(), app = useAppStore()
const isAdmin = computed(() => auth.role === 'ADMIN')
const types = ref([]), logs = ref([]), loading = ref(false), saving = ref(null), toast = ref('')
const errors = ref({}), remoteChanged = ref(false), quotes = ref({}), baseline = ref('')
const roundingOptions = [{ value: 'NONE', label: '不取整' }, { value: 'TENTH', label: '到角' }, { value: 'YUAN', label: '到元' }, { value: 'TAIL_8', label: '尾数 .8' }, { value: 'TAIL_9', label: '尾数 .9' }]
const money = value => Number(value || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const statusText = value => ({ OPEN: '开市', CLOSED: '休市', ERROR: '行情异常', FROZEN: '异常冻结' }[String(value || '').toUpperCase()] || '待同步')
const statusClass = value => String(value || '').toLowerCase()
const primary = computed(() => quotes.value.Au_TD || {})
const spotPrice = computed(() => primary.value.basePrice)
const sourceLabel = computed(() => primary.value.source || '行情来源待同步')
const quoteTime = computed(() => primary.value.quoteTime || '')
const marketMessage = computed(() => primary.value.marketMessage || '')
const marketStatusLabel = computed(() => statusText(primary.value.marketStatus))
const marketClass = computed(() => statusClass(primary.value.marketStatus))
const marketFor = row => ({ ...row, ...(quotes.value[row.baseInstrument] || { basePrice: null, marketStatus: 'ERROR' }) })
const issue = row => autoPricingIssue(marketFor(row))
const fields = ['type_id', 'pricingMode', 'baseInstrument', 'purityCoefficient', 'markup', 'recycleDeduction', 'roundingRule', 'salePrice', 'recyclePrice']
const configuration = () => JSON.stringify(types.value.map(row => fields.map(key => row[key])))
watch(configuration, () => { errors.value = {} })
function step(row, key, amount) { row[key] = Math.max(0, Math.round((Number(row[key] || 0) + amount) * 100) / 100) }
function notify(message) { toast.value = message; window.clearTimeout(notify.timer); notify.timer = window.setTimeout(() => { toast.value = '' }, 5000) }
async function load() {
  if (!isAdmin.value || !auth.can('gold:manage') || loading.value) return
  loading.value = true
  try {
    const [rows, history] = await Promise.all([api.goldTypesAll(), api.goldLogs({ limit: 100 })])
    types.value = (rows || []).map(row => ({ ...row })); logs.value = history || []
    quotes.value = Object.fromEntries(types.value.map(row => [row.baseInstrument, Object.fromEntries(['basePrice', 'marketStatus', 'marketMessage', 'source', 'quoteTime'].map(key => [key, row[key]]))]))
    errors.value = {}; remoteChanged.value = false; baseline.value = configuration()
  } catch (e) { notify(e?.message || '金价配置加载失败') }
  finally { loading.value = false }
}
async function save(row, resumeAuto = false) {
  if (!isAdmin.value || !auth.can('gold:manage')) return notify('仅管理员可修改金价配置')
  const id = row.type_id || row.type_code
  const draft = resumeAuto ? { ...row, pricingMode: 'AUTO' } : row
  const warning = remoteChanged.value ? '配置已在其他终端更新，请先刷新后再保存' : issue(draft)
  errors.value[id] = warning
  if (warning) return notify(warning)
  saving.value = id
  try {
    await api.updateGoldType(id, { pricingMode: draft.pricingMode, baseInstrument: row.baseInstrument, purityCoefficient: Number(row.purityCoefficient), markup: Number(row.markup), recycleDeduction: Number(row.recycleDeduction), roundingRule: row.roundingRule, salePrice: Number(row.salePrice), recyclePrice: Number(row.recyclePrice), resumeAuto })
    await app.loadGold(); await load(); notify(resumeAuto ? '已恢复自动定价' : '金价配置已保存并同步全端')
  } catch (e) {
    const message = e?.response?.status === 403 ? '当前账号没有金价管理权限' : (e?.message || '保存失败')
    errors.value[id] = message; notify(message)
  } finally { saving.value = null }
}
const resume = row => save(row, true)
watch(() => app.eventVersion, () => {
  if (!isAdmin.value || !auth.can('gold:manage') || saving.value !== null) return
  if (['GOLD_PRICE_UPDATED', 'GOLD_TYPES_UPDATED'].includes(app.lastEventType)) {
    if (configuration() !== baseline.value) { remoteChanged.value = true; notify('配置已在其他终端更新，当前输入已保留') }
    else load()
  }
})
onMounted(load)
onUnmounted(() => window.clearTimeout(notify.timer))
</script>

<style scoped>
.pricing-alert{padding:var(--s-3);border:1px solid var(--err);border-radius:var(--r-md);color:var(--err);background:var(--bg);font-size:12px;line-height:1.6;overflow-wrap:anywhere}

.gold-settings-page .permission-note{margin:24px 0}.market-settings-card{padding:var(--s-4);background:#2e261b;color:#fff;border-radius:var(--r-lg);box-shadow:var(--shadow-card);margin-bottom:var(--s-3)}.market-settings-heading{display:flex;align-items:center;justify-content:space-between}.market-settings-heading h2{margin:2px 0 0;font-size:18px}.eyebrow{color:#d7b766;font-size:10px;letter-spacing:.12em;font-weight:700}.market-live{padding:4px 9px;border-radius:999px;color:#e4c875;background:rgba(255,255,255,.1);font-size:11px}.market-live.open{color:#9ad2b2;background:rgba(47,125,92,.2)}.market-live.error,.market-live.frozen{color:#f0a39b;background:rgba(192,69,59,.2)}.market-settings-values{display:grid;grid-template-columns:1.1fr 1fr 1fr;gap:var(--s-2);margin-top:var(--s-4)}.market-settings-values div{min-width:0}.market-settings-values small{display:block;color:#b9ad9d;font-size:11px}.market-settings-values strong{display:block;margin-top:4px;font-size:14px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.market-settings-note{margin:var(--s-3) 0 0;color:#b9ad9d;font-size:11px}.gold-type-card{padding:var(--s-4)}.setting-head{display:flex;align-items:flex-start;justify-content:space-between;gap:var(--s-2)}.setting-head h2{margin:3px 0;font-size:18px}.setting-head small,.meta{color:var(--ink-3);font-size:11px}.status{padding:4px 8px;border-radius:999px;background:var(--gold-soft);color:var(--gold-deep);font-size:11px;white-space:nowrap}.status.open{background:#eaf5ef;color:var(--ok)}.status.error,.status.frozen{background:#fff0e8;color:var(--err)}.quote-line{display:grid;grid-template-columns:1fr 1fr;gap:var(--s-2);margin:var(--s-4) 0;padding:var(--s-3);background:var(--gold-soft);border:1px solid var(--line);border-radius:var(--r-md)}.quote-line small,.quote-line b{display:block}.quote-line small{color:var(--ink-3);font-size:11px}.quote-line b{color:var(--gold-deep);font-size:20px;margin-top:3px;font-variant-numeric:tabular-nums}.setting-block,.rounding-control{margin-top:var(--s-4)}.setting-label{display:flex;justify-content:space-between;gap:var(--s-2);align-items:center;margin-bottom:var(--s-2)}.setting-label strong,.rounding-control>span{font-size:var(--f-sm)}.setting-label small{color:var(--ink-3);font-size:10px}.mode-switch{display:flex}.setting-grid{display:grid;grid-template-columns:1fr 1fr;gap:var(--s-3);margin-top:var(--s-3)}.setting-control{display:grid;gap:var(--s-1);color:var(--ink-2);font-size:11px}.setting-control input,.setting-control select,.manual-price-card input{width:100%;min-height:42px;border:1px solid var(--line);border-radius:var(--s-2);padding:0 var(--s-2);background:#fff;color:var(--ink)}.stepper{display:grid;grid-template-columns:40px minmax(0,1fr) 40px;gap:4px}.stepper button{border:1px solid var(--line);border-radius:var(--s-2);background:var(--gold-soft);color:var(--gold-deep);font-size:20px}.stepper input{text-align:center}.manual-price-card{display:grid;grid-template-columns:1fr 1fr;gap:var(--s-2);padding:var(--s-3);margin-top:var(--s-3);background:#fffaf0;border:1px solid #ead7ad;border-radius:var(--r-md)}.manual-price-card>div{grid-column:1 / -1}.manual-price-card span,.manual-price-card strong{display:block}.manual-price-card span{color:var(--ink-3);font-size:11px}.manual-price-card strong{color:var(--gold-deep);font-size:18px}.manual-price-card label{font-size:11px;color:var(--ink-2)}.manual-price-card input{display:block;margin-top:4px}.rounding-control>span{display:block;margin-bottom:var(--s-2)}.rounding-control .chips{margin-bottom:0}.rounding-control .chips button{padding:0 12px}.formula-preview{display:grid;gap:5px;margin-top:var(--s-4);padding:var(--s-3);border-radius:var(--r-md);background:#2e261b;color:#f8e7b7}.formula-preview small{color:#b9ad9d;font-size:10px}.formula-preview strong{font-size:11px;line-height:1.45;font-weight:500;overflow-wrap:anywhere}.meta{margin-top:var(--s-2)}.actions{display:grid;gap:var(--s-2);margin-top:var(--s-4)}.logs{margin-top:var(--s-4)}.logs .panel-head{margin-bottom:var(--s-2)}.logs .panel-head small{color:var(--ink-3);font-size:11px}.log-row{display:grid;gap:3px;padding:var(--s-3) 0;border-top:1px solid var(--line-soft);font-size:11px}.log-row small{color:var(--ink-3);overflow-wrap:anywhere}.toast{position:fixed;left:50%;bottom:calc(84px + env(safe-area-inset-bottom));z-index:30;transform:translateX(-50%);max-width:calc(100vw - 32px);padding:11px 16px;border-radius:999px;background:#2e261b;color:#fff;font-size:12px;box-shadow:var(--shadow-pop);white-space:normal;overflow-wrap:anywhere;border-radius:var(--r-md);width:max-content}.toast-enter-active,.toast-leave-active{transition:opacity .2s,transform .2s}.toast-enter-from,.toast-leave-to{opacity:0;transform:translate(-50%,8px)}
@media(max-width:480px){.market-settings-values{grid-template-columns:1fr 1fr}.market-settings-values div:last-child{grid-column:1 / -1}.setting-grid{grid-template-columns:1fr}.manual-price-card{grid-template-columns:1fr}.manual-price-card>div{grid-column:auto}}
</style>
