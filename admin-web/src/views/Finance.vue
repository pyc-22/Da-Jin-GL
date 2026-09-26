<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import * as XLSX from 'xlsx'
import { financeApi } from '../api/modules'
import { financeBusinessType, formatFinanceBusiness, formatFinanceType, formatMoney, formatPaymentMethod, formatShiftContent, formatTime } from '../utils/format'
import { useAppStore } from '../stores/app'

const period = ref('daily')
const summary = ref([])
const records = ref([])
const shifts = ref([])
const report = ref(null)
const profit = ref([])
const recycle = ref({})
const recycleRows = ref([])
const exportLoading = ref(false)
const app = useAppStore()

function dateText(value) {
  const year = value.getFullYear()
  const month = String(value.getMonth() + 1).padStart(2, '0')
  const day = String(value.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

const financeRange = computed(() => {
  const end = dateText(new Date())
  return period.value === 'monthly' ? { start: `${end.slice(0, 7)}-01`, end } : { start: end, end }
})

const rangeLabel = computed(() => {
  const { start, end } = financeRange.value
  return start === end ? `${start}（日报）` : `${start} 至 ${end}（月报）`
})

const paymentSummary = computed(() => {
  const result = new Map()
  summary.value.filter(row => row.type === 'INCOME').forEach((row) => {
    const key = row.pay_method || '未指定'
    const current = result.get(key) || { pay_method: key, amount: 0, count: 0 }
    current.amount += Number(row.amount || 0)
    current.count += Number(row.count || 0)
    result.set(key, current)
  })
  return [...result.values()]
})

const businessSummary = computed(() => {
  const result = new Map()
  summary.value.forEach((row) => {
    const code = financeBusinessType(row)
    const current = result.get(code) || { business_type: code, type: row.type, amount: 0, count: 0 }
    current.amount += Number(row.amount || 0)
    current.count += Number(row.count || 0)
    result.set(code, current)
  })
  const order = ['SALE_INCOME', 'PROCESSING_INCOME', 'OTHER_INCOME', 'SALE_REFUND', 'RECYCLE_EXPENSE', 'OTHER_EXPENSE']
  return [...result.values()].sort((a, b) => order.indexOf(a.business_type) - order.indexOf(b.business_type))
})

const reportSummary = computed(() => {
  if (Array.isArray(report.value)) {
    return report.value.reduce((total, row) => ({
      orderCount: total.orderCount + Number(row.order_count || 0),
      salesAmount: total.salesAmount + Number(row.amount || 0)
    }), { orderCount: 0, salesAmount: 0 })
  }
  return {
    orderCount: Number(report.value?.order_count || 0),
    salesAmount: Number(report.value?.amount || 0)
  }
})

const incomeTotal = computed(() => summary.value
  .filter(row => row.type === 'INCOME')
  .reduce((total, row) => total + Number(row.amount || 0), 0))
const expenseTotal = computed(() => summary.value
  .filter(row => row.type === 'EXPENSE')
  .reduce((total, row) => total + Number(row.amount || 0), 0))

async function load() {
  if (period.value === 'recycle') {
    const data = await financeApi.recycle({})
    recycle.value = data || {}
    recycleRows.value = data?.records || []
    return
  }
  if (period.value === 'shifts') {
    shifts.value = await financeApi.shifts()
    return
  }

  const params = financeRange.value
  const reportRequest = period.value === 'daily'
    ? financeApi.daily({ date: params.start })
    : financeApi.monthly({ month: params.start.slice(0, 7) })
  const [summaryData, recordData, profitData, reportData] = await Promise.all([
    financeApi.summary(params),
    financeApi.records(params),
    financeApi.grossProfit(params),
    reportRequest
  ])
  summary.value = summaryData || []
  records.value = recordData || []
  profit.value = profitData || []
  report.value = reportData
}

async function exportXlsx() {
  exportLoading.value = true
  try {
    const exportRows = await financeApi.records({ ...financeRange.value, all: true })
    const ws = XLSX.utils.json_to_sheet((Array.isArray(exportRows) ? exportRows : []).map(row => ({
      流水号: row.finance_id,
      收支类型: formatFinanceType(row.type),
      业务类型: formatFinanceBusiness(row),
      原应收: row.processing_original_due_amount == null ? '' : Number(row.processing_original_due_amount),
      优惠: row.processing_promotion_discount == null ? '' : Number(row.processing_promotion_discount),
      实收: Number(row.amount || 0),
      支付方式: formatPaymentMethod(row.pay_method, app.paymentChannels),
      团购平台: row.processing_promotion_channel ? formatPaymentMethod(row.processing_promotion_channel, app.paymentChannels) : '',
      核销号: row.processing_voucher_no || '',
      关联单据: row.related_bill_no || '',
      时间: formatTime(row.create_time)
    })))
    const wb = XLSX.utils.book_new()
    XLSX.utils.book_append_sheet(wb, ws, '收支流水')
    XLSX.writeFile(wb, `财务报表_${financeRange.value.start}_${financeRange.value.end}.xlsx`)
  } finally {
    exportLoading.value = false
  }
}

function exportRecycle() {
  const ws = XLSX.utils.json_to_sheet(recycleRows.value.map(row => ({ ...row, create_time: formatTime(row.create_time) })))
  const wb = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(wb, ws, '回收报表')
  XLSX.writeFile(wb, '回收业务报表.xlsx')
}

onMounted(load)
watch(() => app.eventVersion, load)
</script>

<template>
  <el-tabs v-model="period" @tab-change="load">
    <el-tab-pane label="营业日报" name="daily" />
    <el-tab-pane label="营业月报" name="monthly" />
    <el-tab-pane label="回收业务报表" name="recycle" />
    <el-tab-pane label="交班记录" name="shifts" />
  </el-tabs>

  <div v-if="period !== 'recycle' && period !== 'shifts'" class="grid-2">
    <section class="panel">
      <div class="panel-title">
        <div class="finance-title-group"><span>经营汇总</span><el-tag type="info" effect="plain">{{ rangeLabel }}</el-tag></div>
        <el-button size="small" :loading="exportLoading" @click="exportXlsx">导出 Excel</el-button>
      </div>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="销售订单数">{{ reportSummary.orderCount }}</el-descriptions-item>
        <el-descriptions-item label="收入合计">{{ formatMoney(incomeTotal) }}</el-descriptions-item>
        <el-descriptions-item label="支出合计">{{ formatMoney(expenseTotal) }}</el-descriptions-item>
        <el-descriptions-item label="净收支">{{ formatMoney(incomeTotal - expenseTotal) }}</el-descriptions-item>
      </el-descriptions>

      <div class="panel-title finance-subtitle">收入 / 支出业务明细</div>
      <el-table :data="businessSummary" size="small" empty-text="当前范围暂无收支流水">
        <el-table-column label="业务类型" min-width="110"><template #default="s">{{ formatFinanceBusiness(s.row.business_type) }}</template></el-table-column>
        <el-table-column label="收支方向" width="90"><template #default="s"><el-tag :type="s.row.type === 'EXPENSE' ? 'danger' : 'success'" size="small">{{ formatFinanceType(s.row.type) }}</el-tag></template></el-table-column>
        <el-table-column label="金额" align="right" min-width="110"><template #default="s"><span :class="s.row.type === 'EXPENSE' ? 'amount-expense' : ''">{{ formatMoney(s.row.amount) }}</span></template></el-table-column>
        <el-table-column prop="count" label="笔数" width="70" />
      </el-table>

      <div class="panel-title finance-subtitle">收入按支付方式拆分</div>
      <el-table :data="paymentSummary" size="small" empty-text="当前范围暂无收款">
        <el-table-column label="支付方式"><template #default="s">{{ formatPaymentMethod(s.row.pay_method, app.paymentChannels) }}</template></el-table-column>
        <el-table-column label="金额" align="right"><template #default="s">{{ formatMoney(s.row.amount) }}</template></el-table-column>
        <el-table-column prop="count" label="笔数" />
      </el-table>
    </section>

    <section class="panel">
      <div class="panel-title"><span>毛利分析</span><span class="muted">{{ rangeLabel }}</span></div>
      <el-table :data="profit" empty-text="当前范围暂无销售数据">
        <el-table-column prop="category" label="品类" min-width="120" />
        <el-table-column label="销售额" align="right" min-width="120"><template #default="s">{{ formatMoney(s.row.revenue) }}</template></el-table-column>
        <el-table-column label="成本" align="right" min-width="120"><template #default="s">{{ formatMoney(s.row.cost) }}</template></el-table-column>
        <el-table-column label="毛利" align="right" min-width="120"><template #default="s"><span :class="Number(s.row.gross_profit) < 0 ? 'profit-negative' : 'profit-positive'">{{ formatMoney(s.row.gross_profit) }}</span></template></el-table-column>
        <el-table-column label="毛利率" align="right" min-width="100"><template #default="s">{{ (Number(s.row.gross_margin || 0) * 100).toFixed(2) }}%</template></el-table-column>
      </el-table>
      <div class="muted profit-category-note">成本取自销售时的商品成本快照，商品成本价请在【商品】页编辑商品维护；毛利按一级分类汇总，分类在【商品】页“分类管理”维护。</div>
    </section>
  </div>

  <section v-else-if="period === 'recycle'" class="panel">
    <div class="panel-title">回收业务统计 <el-button size="small" @click="exportRecycle">导出 Excel</el-button></div>
    <el-descriptions :column="4" border>
      <el-descriptions-item label="回收单数">{{ recycle.order_count || 0 }}</el-descriptions-item>
      <el-descriptions-item label="回收量">{{ recycle.weight || 0 }}g</el-descriptions-item>
      <el-descriptions-item label="回收金额">{{ formatMoney(recycle.amount) }}</el-descriptions-item>
      <el-descriptions-item label="扣损">{{ formatMoney(recycle.deduct_loss) }}</el-descriptions-item>
    </el-descriptions>
    <el-table :data="recycleRows" style="margin-top:16px">
      <el-table-column prop="bill_no" label="单号" /><el-table-column prop="material_type" label="类型" />
      <el-table-column prop="weight" label="克重" /><el-table-column prop="purity" label="成色" />
      <el-table-column prop="deduct_loss" label="扣损" /><el-table-column prop="total_amount" label="金额" />
      <el-table-column prop="status" label="状态" /><el-table-column label="时间"><template #default="s">{{ formatTime(s.row.create_time) }}</template></el-table-column>
    </el-table>
  </section>

  <section v-else class="panel">
    <div class="panel-title">交班记录</div>
    <el-table :data="shifts" empty-text="暂无交班记录"><el-table-column prop="shift_id" label="交班单号" width="110" /><el-table-column prop="user_id" label="操作员" width="90" /><el-table-column label="交班内容" min-width="560"><template #default="s">{{ formatShiftContent(s.row.content) }}</template></el-table-column><el-table-column label="确认时间" min-width="170"><template #default="s">{{ formatTime(s.row.create_time) }}</template></el-table-column></el-table>
  </section>

  <section v-if="period !== 'recycle' && period !== 'shifts'" class="panel" style="margin-top:16px">
    <div class="panel-title"><span>收支流水</span><span class="muted">{{ rangeLabel }} · 页面最多显示 500 条，导出为完整结果</span></div>
    <el-table :data="records" empty-text="当前范围暂无收支流水">
      <el-table-column prop="finance_id" label="流水号" width="90" />
      <el-table-column label="业务类型" min-width="115"><template #default="s">{{ formatFinanceBusiness(s.row) }}</template></el-table-column>
      <el-table-column label="收支方向" width="90"><template #default="s"><el-tag :type="s.row.type === 'EXPENSE' ? 'danger' : 'success'">{{ formatFinanceType(s.row.type) }}</el-tag></template></el-table-column>
      <el-table-column label="原应收" align="right" min-width="105"><template #default="s">{{ s.row.processing_original_due_amount == null ? '-' : formatMoney(s.row.processing_original_due_amount) }}</template></el-table-column>
      <el-table-column label="优惠" align="right" min-width="95"><template #default="s">{{ s.row.processing_promotion_discount == null ? '-' : formatMoney(s.row.processing_promotion_discount) }}</template></el-table-column>
      <el-table-column label="实收" align="right" min-width="105"><template #default="s"><span :class="s.row.type === 'EXPENSE' ? 'amount-expense' : ''">{{ formatMoney(s.row.amount) }}</span></template></el-table-column>
      <el-table-column label="支付方式" min-width="100"><template #default="s">{{ formatPaymentMethod(s.row.pay_method, app.paymentChannels) }}</template></el-table-column>
      <el-table-column label="团购平台" min-width="110"><template #default="s">{{ s.row.processing_promotion_channel ? formatPaymentMethod(s.row.processing_promotion_channel, app.paymentChannels) : '-' }}</template></el-table-column>
      <el-table-column prop="processing_voucher_no" label="核销号" min-width="130" />
      <el-table-column prop="related_bill_no" label="关联单据" min-width="180" />
      <el-table-column label="时间" min-width="170"><template #default="s">{{ formatTime(s.row.create_time) }}</template></el-table-column>
    </el-table>
  </section>
</template>

<style scoped>
.finance-title-group { display: flex; align-items: center; gap: 10px; }
.finance-subtitle { margin-top: 22px; margin-bottom: 10px; }
.amount-expense { color: var(--el-color-danger); }
.profit-category-note { margin-top: 12px; line-height: 1.6; }
</style>
