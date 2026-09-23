<template>
  <div class="shell">
    <header class="topbar">
      <button class="back" aria-label="返回" @click="router.back()">‹</button>
      <strong>回收登记</strong>
      <span></span>
    </header>

    <main class="content page">
      <section v-if="done" class="result-page" data-testid="success-result">
        <div class="result-icon" :class="{ pending: done.approvalRequired }">
          {{ done.approvalRequired ? '!' : '✓' }}
        </div>
        <h2>{{ done.approvalRequired ? '已提交店长审批' : '回收成功' }}</h2>
        <p class="result-message">
          {{ done.approvalRequired ? '审批通过后将完成付款并计入旧料库存' : '回收款已登记，旧料已计入库存' }}
        </p>
        <div class="result-amount">
          <small>{{ done.approvalRequired ? '预计付款金额' : '回收金额' }}</small>
          <b>{{ money(done.amount ?? submittedSnapshot.amount) }}</b>
        </div>
        <dl class="result-detail">
          <div><dt>回收单号</dt><dd>{{ done.billNo || '—' }}</dd></div>
          <div><dt>旧料类型</dt><dd>{{ submittedSnapshot.materialType }}</dd></div>
          <div><dt>回收克重</dt><dd>{{ weightText(submittedSnapshot.weight) }}</dd></div>
          <div><dt>付款方式</dt><dd>{{ submittedSnapshot.payMethodLabel }}</dd></div>
          <div><dt>处理状态</dt><dd :class="done.approvalRequired ? 'pending-text' : 'success-text'">{{ done.approvalRequired ? '等待审批' : '已完成' }}</dd></div>
        </dl>
        <button class="primary full" type="button" @click="startAnother">继续回收</button>
        <button class="outline full secondary-action" type="button" @click="router.back()">返回</button>
      </section>

      <div v-else data-testid="recycle-form">
        <div class="price-strip">
          <div>
            <small>今日回收价</small>
            <b :class="{ na: !recycleAvailable }">{{ recycleAvailable ? money(recycle) + '/g' : '未配置' }}</b>
          </div>
          <div>
            <small>当前报价</small>
            <b>{{ estimateText }}</b>
          </div>
        </div>

        <p v-if="!recycleAvailable" class="error tip">门店未配置回收金价，请先在金价管理中维护“回收”类型价格。</p>

        <section class="form-card">
          <h3>旧料信息</h3>
          <label class="form-label">
            旧料类型
            <select v-model="materialType" data-testid="material-type" :disabled="typesLoading || !materialTypes.length">
              <option value="" disabled>{{ typesLoading ? '正在加载旧料类型...' : '请选择旧料类型' }}</option>
              <option v-for="type in materialTypes" :key="type.type_id || type.name" :value="type.name">{{ type.name }}</option>
            </select>
          </label>
          <p v-if="typeError" class="error field-tip">{{ typeError }}</p>
          <label class="form-label">
            克重（g）
            <input v-model.number="weight" data-testid="recycle-weight" type="number" min="0" step="0.001" inputmode="decimal" placeholder="请输入实际称重" />
          </label>
          <label class="form-label">
            成色
            <select v-model.number="purity">
              <option v-for="item in purities" :key="item.value" :value="item.value">{{ item.label }}</option>
            </select>
          </label>
          <label class="form-label">
            扣损（%）
            <input v-model.number="lossRate" type="number" min="0" max="99" step="0.1" inputmode="decimal" />
          </label>
        </section>

        <section class="form-card">
          <h3>付款方式</h3>
          <div class="chips">
            <button v-for="method in payOptions" :key="method.code" type="button" :class="{ active: payMethod === method.code }" @click="payMethod = method.code">
              {{ method.label }}
            </button>
          </div>
        </section>

        <section v-if="canQuote" class="quote-card" data-testid="quote-card">
          <div class="quote-heading"><span>回收报价</span><small>请与客户核对</small></div>
          <dl>
            <div><dt>旧料类型</dt><dd>{{ materialType }}</dd></div>
            <div><dt>克重 × 成色</dt><dd>{{ weightText(weight) }} × {{ purityText }}</dd></div>
            <div><dt>今日回收价</dt><dd>{{ money(recycle) }}/g</dd></div>
            <div><dt>扣损</dt><dd>{{ lossRateText }}（{{ money(lossAmount) }}）</dd></div>
          </dl>
          <div class="quote-total"><span>预计付款金额</span><b>{{ money(estimate) }}</b></div>
        </section>

        <p v-if="submitError" class="error submit-error">{{ submitError }}</p>
        <button class="primary full" data-testid="open-confirm" type="button" :disabled="!canSubmit || busy" @click="confirmOpen = true">
          {{ busy ? '提交中...' : '确认报价并回收' }}
        </button>
        <small v-if="recycleAvailable && !canSubmit && !busy" class="muted small">请选择旧料类型并填写大于 0 的克重</small>
        <p class="muted small">回收金额超过门店限额将提交店长审批，审批通过后再付款入账。</p>
      </div>
    </main>

    <div v-if="confirmOpen" class="dialog-mask" data-testid="confirm-dialog" @click.self="confirmOpen = false">
      <section class="confirm-dialog" role="dialog" aria-modal="true" aria-labelledby="recycle-confirm-title">
        <h3 id="recycle-confirm-title">确认回收报价</h3>
        <p>请确认旧料信息与付款金额，提交后将生成正式回收单。</p>
        <dl>
          <div><dt>旧料类型</dt><dd>{{ materialType }}</dd></div>
          <div><dt>克重 / 成色</dt><dd>{{ weightText(weight) }} / {{ purityText }}</dd></div>
          <div><dt>付款方式</dt><dd>{{ payMethodLabel }}</dd></div>
        </dl>
        <div class="confirm-amount"><small>客户应收</small><b>{{ money(estimate) }}</b></div>
        <div class="dialog-actions">
          <button class="outline" type="button" :disabled="busy" @click="confirmOpen = false">返回修改</button>
          <button class="primary" data-testid="confirm-submit" type="button" :disabled="busy" @click="submit">
            {{ busy ? '正在提交...' : '确认回收并入账' }}
          </button>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAppStore } from '../stores/app.js'
import { api } from '../api/request.js'

const router = useRouter()
const app = useAppStore()
const weight = ref('')
const purity = ref(0.999)
const lossRate = ref(0)
const materialType = ref('')
const materialTypes = ref([])
const typesLoading = ref(true)
const typeError = ref('')
const payMethod = ref('CASH')
const recycle = ref(0)
const busy = ref(false)
const confirmOpen = ref(false)
const submitError = ref('')
const done = ref(null)
const submittedSnapshot = ref({})

const purities = [
  { value: 0.999, label: '足金999（99.9%）' },
  { value: 0.99, label: '足金990（99.0%）' },
  { value: 0.916, label: '22K（91.6%）' },
  { value: 0.75, label: '18K（75.0%）' }
]
const payOptions = [
  { code: 'CASH', label: '现金' },
  { code: 'WECHAT', label: '微信' },
  { code: 'ALIPAY', label: '支付宝' }
]

const safeLossRate = computed(() => Math.min(99, Math.max(0, Number(lossRate.value || 0))))
const recycleAvailable = computed(() => Number(recycle.value) > 0)
const grossAmount = computed(() => Number(weight.value || 0) * Number(purity.value || 0) * Number(recycle.value || 0))
const lossAmount = computed(() => grossAmount.value * safeLossRate.value / 100)
const estimate = computed(() => grossAmount.value - lossAmount.value)
const canQuote = computed(() => recycleAvailable.value && Boolean(materialType.value) && Number(weight.value) > 0)
const estimateText = computed(() => canQuote.value ? money(estimate.value) : '—')
const canSubmit = computed(() => canQuote.value && estimate.value > 0)
const purityText = computed(() => `${(Number(purity.value || 0) * 100).toFixed(1)}%`)
const lossRateText = computed(() => `${safeLossRate.value.toFixed(1)}%`)
const payMethodLabel = computed(() => payOptions.find(item => item.code === payMethod.value)?.label || payMethod.value)

const money = value => `¥${Number(value || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
const weightText = value => `${Number(value || 0).toLocaleString('zh-CN', { minimumFractionDigits: 3, maximumFractionDigits: 3 })}g`

onMounted(() => {
  loadRecyclePrice()
  loadMaterialTypes()
})

async function loadRecyclePrice() {
  try {
    await app.loadGold()
    const rows = Array.isArray(app.gold) ? app.gold : []
    const hit = rows.find(item => String(item.price_type || item.priceType || item.name || '').includes('回收'))
    recycle.value = Number(hit?.price || 0)
  } catch {
    recycle.value = 0
  }
}

async function loadMaterialTypes() {
  typesLoading.value = true
  typeError.value = ''
  try {
    const data = await api.oldMaterialTypes()
    const rows = Array.isArray(data) ? data : (data?.records || [])
    materialTypes.value = rows
      .filter(item => Number(item.status ?? 1) === 1 && String(item.name || '').trim())
      .sort((left, right) => Number(left.sort ?? left.type_id ?? 0) - Number(right.sort ?? right.type_id ?? 0))
    materialType.value = materialTypes.value[0]?.name || ''
    if (!materialTypes.value.length) typeError.value = '暂无启用的旧料类型，请先在管理端配置'
  } catch (error) {
    materialTypes.value = []
    materialType.value = ''
    typeError.value = error?.message || '旧料类型加载失败，请检查网络连接'
  } finally {
    typesLoading.value = false
  }
}

async function submit() {
  if (!canSubmit.value || busy.value) return
  busy.value = true
  submitError.value = ''
  submittedSnapshot.value = {
    materialType: materialType.value,
    weight: Number(weight.value),
    purity: Number(purity.value),
    lossRate: safeLossRate.value,
    payMethod: payMethod.value,
    payMethodLabel: payMethodLabel.value,
    amount: estimate.value
  }
  try {
    const result = await api.recycleCreate({
      weight: submittedSnapshot.value.weight,
      purity: submittedSnapshot.value.purity,
      materialType: submittedSnapshot.value.materialType,
      recyclePrice: Number(recycle.value),
      deductLossRate: submittedSnapshot.value.lossRate / 100,
      payMethod: submittedSnapshot.value.payMethod
    })
    done.value = result || { amount: submittedSnapshot.value.amount, approvalRequired: false }
    confirmOpen.value = false
  } catch (error) {
    confirmOpen.value = false
    submitError.value = error?.message || '回收登记失败，请检查后重试'
  } finally {
    busy.value = false
  }
}

function startAnother() {
  done.value = null
  submittedSnapshot.value = {}
  weight.value = ''
  purity.value = 0.999
  lossRate.value = 0
  payMethod.value = 'CASH'
  submitError.value = ''
}
</script>

<style scoped>
.price-strip{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-bottom:12px}.price-strip>div{background:var(--gold-soft);border:1px solid #efd9a9;border-radius:8px;padding:12px;text-align:center}.price-strip small{display:block;color:var(--ink-3);font-size:11px}.price-strip b{display:block;color:var(--gold-deep);font-size:16px;margin-top:4px}.price-strip b.na{color:var(--ink-3);font-weight:400}.error.tip{margin:0 0 8px;font-size:12px;color:#c0392b}.form-card{margin-top:12px}.form-card h3{font-size:15px;margin:0 0 8px}.form-label{display:block;font-size:12px;color:var(--ink-2);margin:10px 0}.form-label input,.form-label select{display:block;width:100%;margin-top:4px;min-height:44px;border:1px solid #dfe3e8;border-radius:8px;padding:0 10px;background:#fff;color:var(--ink-1);box-sizing:border-box}.form-label select:disabled{background:#f5f6f8;color:var(--ink-3)}.field-tip{margin:-4px 0 8px;font-size:12px}.full{width:100%;min-height:46px;margin-top:12px}.small{display:block;margin-top:8px}.quote-card{margin-top:12px;border:1px solid #e8cf94;border-radius:8px;background:#fffaf0;overflow:hidden}.quote-heading{display:flex;align-items:center;justify-content:space-between;padding:12px 14px;border-bottom:1px solid #f0dfb9}.quote-heading span{font-size:16px;font-weight:700;color:var(--ink-1)}.quote-heading small{color:#956a18}.quote-card dl,.confirm-dialog dl,.result-detail{margin:0;padding:8px 14px}.quote-card dl div,.confirm-dialog dl div,.result-detail div{display:flex;align-items:center;justify-content:space-between;gap:16px;min-height:34px}.quote-card dt,.confirm-dialog dt,.result-detail dt{font-size:12px;color:var(--ink-3)}.quote-card dd,.confirm-dialog dd,.result-detail dd{margin:0;text-align:right;color:var(--ink-1);font-size:13px;font-weight:600}.quote-total{display:flex;align-items:flex-end;justify-content:space-between;padding:12px 14px;background:#fff3d8}.quote-total span{font-size:13px;font-weight:600}.quote-total b{color:var(--gold-deep);font-size:25px;line-height:1}.submit-error{margin:12px 0 0;padding:10px 12px;background:#fff2f0;border:1px solid #ffd1cc;border-radius:8px;font-size:12px}.dialog-mask{position:fixed;inset:0;z-index:1000;display:flex;align-items:flex-end;justify-content:center;background:rgba(17,24,39,.48);padding:16px}.confirm-dialog{width:min(100%,480px);background:#fff;border-radius:8px;padding:18px;box-sizing:border-box}.confirm-dialog h3{margin:0;font-size:18px}.confirm-dialog>p{margin:8px 0 4px;color:var(--ink-3);font-size:13px;line-height:1.6}.confirm-dialog dl{padding:8px 0}.confirm-amount{padding:12px;text-align:center;background:var(--gold-soft);border:1px solid #efd9a9;border-radius:8px}.confirm-amount small{display:block;color:var(--ink-3)}.confirm-amount b{display:block;margin-top:4px;color:var(--gold-deep);font-size:28px}.dialog-actions{display:grid;grid-template-columns:1fr 1.4fr;gap:8px;margin-top:14px}.dialog-actions button{min-height:46px}.result-page{padding:28px 4px;text-align:center}.result-icon{display:grid;place-items:center;width:64px;height:64px;margin:0 auto 14px;border-radius:50%;background:#1f8f55;color:#fff;font-size:34px;font-weight:700}.result-icon.pending{background:#d68a12}.result-page h2{margin:0;color:var(--ink-1);font-size:22px}.result-message{margin:8px auto 18px;color:var(--ink-3);font-size:13px;line-height:1.6}.result-amount{padding:18px 12px;background:var(--gold-soft);border:1px solid #efd9a9;border-radius:8px}.result-amount small{display:block;color:var(--ink-3)}.result-amount b{display:block;margin-top:6px;color:var(--gold-deep);font-size:30px}.result-detail{margin:12px 0;padding:10px 14px;background:#fff;border:1px solid #e6e8eb;border-radius:8px;text-align:left}.result-detail div+div{border-top:1px solid #f0f1f3}.success-text{color:#1f8f55!important}.pending-text{color:#b66c00!important}.secondary-action{margin-top:8px}
@media (min-width:600px){.dialog-mask{align-items:center}.confirm-dialog{padding:22px}}
</style>
