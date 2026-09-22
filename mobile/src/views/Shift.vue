<template>
  <div class="shell">
    <header class="topbar"><div><button class="back" @click="router.back()">&#8249;</button><strong>交班结算</strong></div><div></div></header>
    <div class="content page">
      <div v-if="loading" class="empty">加载中...</div>
      <template v-if="!loading && !done">
        <div class="detail-card">
          <p class="muted">班次号</p>
          <h3>{{ info.shiftNo }}</h3>
        </div>
        <Panel title="收款汇总">
          <div v-for="l in info.lines" :key="l.pay_method" class="rank-row">
            <span>{{ methodLabel(l.pay_method) }}</span>
            <strong>¥{{ money(l.amount) }}</strong>
            <small>{{ l.count }} 笔</small>
          </div>
          <div v-if="!info.lines?.length" class="empty">本班次暂无收款</div>
          <div v-if="info.lines?.length" class="shift-total">
            <span>合计</span><b>¥{{ money(info.total) }}</b>
          </div>
        </Panel>
        <Panel title="现金核对">
          <label class="form-label">系统应收现金
            <b class="shift-system">¥{{ money(cashSystem) }}</b>
          </label>
          <label class="form-label">实际清点现金
            <input v-model.number="cashActual" type="number" min="0" step="0.01" placeholder="输入实际现金金额"/>
          </label>
          <p v-if="cashActual !== null && cashActual !== ''" class="shift-diff" :class="{ok: cashDiff === 0, warn: cashDiff !== 0}">
            差额：{{ cashDiff >= 0 ? '+' : '' }}¥{{ money(cashDiff) }}
          </p>
          <label v-if="cashActual !== null && cashActual !== '' && cashDiff !== 0" class="form-label">差异说明
            <textarea v-model="remark" rows="2" placeholder="请填写差异原因" @input="shiftError=''"/>
          </label>
          <p v-if="shiftError" class="error" style="margin:8px 0 0;color:#c0392b;font-size:12px">{{ shiftError }}</p>
        </Panel>
        <button class="primary full" :disabled="confirming" @click="confirmShift">{{ confirming ? '提交中...' : '确认交班' }}</button>
      </template>
      <template v-if="done">
        <div class="detail-card" style="text-align:center">
          <h3 style="color:#16a34a;margin:0 0 10px">交班成功</h3>
          <p class="muted">班次 {{ result.previousShiftNo }} 已结束</p>
          <div class="rank-row"><span>系统现金</span><b>¥{{ money(result.cashSystem) }}</b></div>
          <div class="rank-row"><span>实点现金</span><b>¥{{ money(result.cashActual) }}</b></div>
          <div class="rank-row"><span>差额</span><b :class="result.cashDifference===0?'ok':'error'">¥{{ money(result.cashDifference) }}</b></div>
          <p class="muted small" style="margin-top:12px">新班次 {{ result.nextShiftNo }} 已开始</p>
          <button class="primary full" style="margin-top:16px" @click="router.push(`/${auth.role.toLowerCase()}`)">返回首页</button>
        </div>
      </template>
    </div>
  </div>
</template>
<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth.js'
import { api } from '../api/request.js'
import Panel from '../components/Panel.vue'
const router = useRouter()
const auth = useAuthStore()
const loading = ref(true)
const info = ref({})
const cashActual = ref(null)
const remark = ref('')
const shiftError = ref('')
const confirming = ref(false)
const done = ref(false)
const result = ref({})
const money = (v) => Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const LABELS = { CASH: '现金', WECHAT: '微信', ALIPAY: '支付宝', BALANCE: '储值', BANK_CARD: '银行卡' }
const methodLabel = (m) => LABELS[m] || m || '未指定'
const cashSystem = computed(() => {
  const line = (info.value.lines || []).find(l => l.pay_method === 'CASH')
  return Number(line?.amount || 0)
})
const cashDiff = computed(() => {
  const actual = Number(cashActual.value || 0)
  return Math.round((actual - cashSystem.value) * 100) / 100
})

onMounted(async () => {
  try { info.value = await api.shiftInfo() || {} } catch { }
  finally { loading.value = false }
})

async function confirmShift() {
  if (cashDiff.value !== 0 && !remark.value.trim()) { shiftError.value = '现金有差异，必须先填写差异说明才能交班'; return }
  shiftError.value = ''
  confirming.value = true
  try {
    const d = await api.shiftConfirm({
      cashActual: Number(cashActual.value || 0),
      remark: remark.value || undefined,
      clientRequestId: `shift-${Date.now()}`
    })
    result.value = d
    done.value = true
  } catch (e) { alert(e.message) }
  finally { confirming.value = false }
}
</script>
<style scoped>
.detail-card{background:#fff;border:1px solid #eceef2;border-radius:12px;padding:18px;margin-bottom:14px}
.detail-card h3{margin:4px 0 0;font-size:15px;color:#374151;word-break:break-all}
.shift-total{display:flex;justify-content:space-between;align-items:center;padding-top:10px;margin-top:6px;border-top:2px solid #f0f1f3;font-size:15px}
.shift-total b{color:#b7791f;font-size:20px}
.shift-system{float:right;color:#b7791f;font-size:16px}
.shift-diff{font-size:14px;font-weight:600;margin:8px 0}
.shift-diff.ok{color:#16a34a}
.shift-diff.warn{color:#d97706}
</style>
