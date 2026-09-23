<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as XLSX from 'xlsx'
import { salesApi } from '../api/modules'
import { formatMoney, formatPaymentMethod, formatTime, orderStatus } from '../utils/format'
import { useAppStore } from '../stores/app'

const rows = ref([])
const app = useAppStore()
const filters = reactive({ keyword: '', status: null })
const detail = ref(null)
const detailVisible = ref(false)

const order = computed(() => detail.value?.order || {})
const items = computed(() => detail.value?.items || [])
const oldMaterials = computed(() => detail.value?.oldMaterials || [])
const payments = computed(() => {
  const rows = detail.value?.payments || []
  if (rows.length) return rows
  if (!order.value.pay_method || !Number(order.value.pay_amount)) return []
  return [{ pay_method: order.value.pay_method, amount: order.value.pay_amount, create_time: order.value.update_time }]
})
const itemTotal = computed(() => items.value.reduce((total, item) => total + Number(item.subtotal || 0), 0))
const oldMaterialTotal = computed(() => oldMaterials.value.reduce((total, item) => total + Number(item.value || 0), 0))
const paymentTotal = computed(() => payments.value.reduce((total, payment) => total + Number(payment.amount || 0), 0))

async function load() {
  rows.value = await salesApi.list(filters)
}

async function show(row) {
  detail.value = await salesApi.detail(row.order_id)
  detailVisible.value = true
}

async function refund(row) {
  const reason = await ElMessageBox.prompt('请填写具体的中文退单原因', '退单申请', {
    inputPlaceholder: '例如：顾客退货，商品未使用',
    inputPattern: /\S+/,
    inputErrorMessage: '退单原因不能为空'
  })
    .then(result => result.value)
    .catch(() => null)
  if (!reason) return
  await salesApi.refund({ orderId: row.order_id, reason })
  ElMessage.success('退单申请已提交')
  detailVisible.value = false
  await load()
}

function printPlaceholder() {
  ElMessage.info('打印功能将在前台收银端执行')
}

function itemSummary({ columns, data }) {
  return columns.map((column, index) => {
    if (index === 0) return '合计'
    if (column.property === 'subtotal') return formatMoney(data.reduce((total, row) => total + Number(row.subtotal || 0), 0))
    return ''
  })
}

function oldMaterialPrice(item) {
  const denominator = Number(item.weight || 0) * Number(item.purity || 0)
  return denominator ? Number(item.value || 0) / denominator : 0
}

function oldMaterialSummary({ columns, data }) {
  return columns.map((column, index) => {
    if (index === 0) return '旧金合计'
    if (column.property === 'value') return formatMoney(data.reduce((total, row) => total + Number(row.value || 0), 0))
    return ''
  })
}

function paymentSummary({ columns, data }) {
  return columns.map((column, index) => {
    if (index === 0) return '实付合计'
    if (column.property === 'amount') return formatMoney(data.reduce((total, row) => total + Number(row.amount || 0), 0))
    return ''
  })
}

function exportXlsx() {
  const output = rows.value.map(row => ({
    订单号: row.order_no,
    实收金额: formatMoney(row.pay_amount),
    支付方式: formatPaymentMethod(row.pay_method, app.paymentChannels),
    收银员: row.cashier || '-',
    状态: orderStatus(row.status).label,
    创建时间: formatTime(row.create_time)
  }))
  const ws = XLSX.utils.json_to_sheet(output)
  const wb = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(wb, ws, '销售订单')
  XLSX.writeFile(wb, `销售订单-${new Date().toISOString().slice(0, 10)}.xlsx`)
}

onMounted(load)
</script>

<template>
  <section class="panel">
    <div class="page-toolbar">
      <div class="filters">
        <el-input v-model="filters.keyword" placeholder="订单号 / 支付方式" clearable />
        <el-select v-model="filters.status" clearable placeholder="状态" style="width: 120px">
          <el-option :value="1" label="已完成" />
          <el-option :value="2" label="已退单" />
          <el-option :value="3" label="待审批" />
        </el-select>
        <el-button type="primary" @click="load">查询</el-button>
      </div>
      <el-button @click="exportXlsx">导出 Excel</el-button>
    </div>

    <el-table :data="rows" stripe>
      <el-table-column prop="order_no" label="订单号" min-width="190" />
      <el-table-column label="实收金额" align="right" min-width="115">
        <template #default="scope"><span class="order-money">{{ formatMoney(scope.row.pay_amount) }}</span></template>
      </el-table-column>
      <el-table-column label="支付方式" min-width="130">
        <template #default="scope">{{ formatPaymentMethod(scope.row.pay_method, app.paymentChannels) }}</template>
      </el-table-column>
      <el-table-column label="收银员" min-width="105">
        <template #default="scope">{{ scope.row.cashier || '-' }}</template>
      </el-table-column>
      <el-table-column label="状态" min-width="96">
        <template #default="scope"><el-tag :type="orderStatus(scope.row.status).type">{{ orderStatus(scope.row.status).label }}</el-tag></template>
      </el-table-column>
      <el-table-column label="时间" min-width="176">
        <template #default="scope">{{ formatTime(scope.row.create_time) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="145" fixed="right">
        <template #default="scope">
          <el-button link type="primary" @click="show(scope.row)">详情</el-button>
          <el-button v-if="Number(scope.row.status) === 1" link type="danger" @click="refund(scope.row)">退单申请</el-button>
        </template>
      </el-table-column>
    </el-table>
  </section>

  <el-dialog v-model="detailVisible" title="订单详情" width="600px" class="order-detail-dialog">
    <template v-if="detail">
      <section class="order-detail-section">
        <h3 class="order-detail-section-title">订单基本信息</h3>
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="订单号">{{ order.order_no || '-' }}</el-descriptions-item>
          <el-descriptions-item label="订单状态"><el-tag :type="orderStatus(order.status).type">{{ orderStatus(order.status).label }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="收银员">{{ order.cashier_name || order.cashier || '-' }}</el-descriptions-item>
          <el-descriptions-item label="销售顾问">{{ order.sales_name || '-' }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatTime(order.create_time) }}</el-descriptions-item>
          <el-descriptions-item label="更新时间">{{ formatTime(order.update_time) }}</el-descriptions-item>
          <el-descriptions-item label="商品总金额"><span class="order-money">{{ formatMoney(order.total_amount) }}</span></el-descriptions-item>
          <el-descriptions-item label="折扣">{{ Number(order.discount || 1) * 10 }} 折</el-descriptions-item>
          <el-descriptions-item label="工费"><span class="order-money">{{ formatMoney(order.labor_fee) }}</span></el-descriptions-item>
          <el-descriptions-item label="旧金抵扣"><span class="order-money">-{{ formatMoney(order.old_material_deduct) }}</span></el-descriptions-item>
          <el-descriptions-item label="实付金额" :span="2"><span class="order-money">{{ formatMoney(order.pay_amount) }}</span></el-descriptions-item>
        </el-descriptions>
      </section>

      <section class="order-detail-section">
        <h3 class="order-detail-section-title">商品明细</h3>
        <el-table :data="items" size="small" show-summary :summary-method="itemSummary">
          <el-table-column prop="item_name" label="商品名称" min-width="120" />
          <el-table-column label="克重" width="70" align="right"><template #default="scope">{{ Number(scope.row.weight || 0).toFixed(3) }}g</template></el-table-column>
          <el-table-column label="单价" width="90" align="right"><template #default="scope">{{ formatMoney(scope.row.unit_price) }}</template></el-table-column>
          <el-table-column label="工费" width="86" align="right"><template #default="scope">{{ formatMoney(scope.row.labor_fee) }}</template></el-table-column>
          <el-table-column prop="qty" label="数量" width="62" align="right" />
          <el-table-column prop="subtotal" label="小计" width="96" align="right"><template #default="scope">{{ formatMoney(scope.row.subtotal) }}</template></el-table-column>
        </el-table>
        <div class="order-total-note">商品小计：<span class="order-money">{{ formatMoney(itemTotal) }}</span></div>
      </section>

      <section v-if="oldMaterials.length" class="order-detail-section">
        <h3 class="order-detail-section-title">旧金抵扣</h3>
        <el-table :data="oldMaterials" size="small" show-summary :summary-method="oldMaterialSummary">
          <el-table-column type="index" label="序号" width="58" />
          <el-table-column label="克重" width="85" align="right"><template #default="scope">{{ Number(scope.row.weight || 0).toFixed(3) }}g</template></el-table-column>
          <el-table-column label="成色" width="78" align="right"><template #default="scope">{{ (Number(scope.row.purity || 0) * 100).toFixed(2) }}%</template></el-table-column>
          <el-table-column label="回收金价" min-width="102" align="right"><template #default="scope">{{ formatMoney(oldMaterialPrice(scope.row)) }}</template></el-table-column>
          <el-table-column prop="value" label="抵扣金额" min-width="102" align="right"><template #default="scope">{{ formatMoney(scope.row.value) }}</template></el-table-column>
        </el-table>
        <div class="order-total-note">旧金合计：<span class="order-money">{{ formatMoney(oldMaterialTotal) }}</span></div>
      </section>

      <section class="order-detail-section">
        <h3 class="order-detail-section-title">支付明细</h3>
        <el-table :data="payments" size="small" show-summary :summary-method="paymentSummary">
          <el-table-column label="支付方式" min-width="130"><template #default="scope">{{ formatPaymentMethod(scope.row.pay_method, app.paymentChannels) }}</template></el-table-column>
          <el-table-column prop="amount" label="支付金额" min-width="110" align="right"><template #default="scope">{{ formatMoney(scope.row.amount) }}</template></el-table-column>
          <el-table-column label="支付时间" min-width="170"><template #default="scope">{{ formatTime(scope.row.create_time) }}</template></el-table-column>
        </el-table>
        <div class="order-total-note">实付合计：<span class="order-money">{{ formatMoney(paymentTotal || order.pay_amount) }}</span></div>
      </section>
    </template>
    <template #footer>
      <el-button @click="detailVisible = false">关闭</el-button>
      <el-button @click="printPlaceholder">打印</el-button>
      <el-button v-if="Number(order.status) === 1" type="danger" @click="refund(order)">退单申请</el-button>
    </template>
  </el-dialog>
</template>
