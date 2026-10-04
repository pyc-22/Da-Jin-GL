<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { approvalApi } from '../api/modules'
import { formatApprovalReason, formatMoney, formatTime } from '../utils/format'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const pending = ref([])
const history = ref([])
const selectedType = ref('ALL')
const detailVisible = ref(false)
const detail = ref(null)
const submitting = ref(false)
const typeOptions = [
  { value: 'ALL', label: '全部' },
  { value: 'DISCOUNT', label: '价格审批' },
  { value: 'SALE_PAYMENT_DISCOUNT', label: '成品优惠审批' },
  { value: 'PROCESSING_PAYMENT_DISCOUNT', label: '加工优惠审批' },
  { value: 'REFUND', label: '退货审批' },
  { value: 'RECYCLE', label: '回收审批' },
  { value: 'STOCK_CHECK', label: '盘点审批' },
  { value: 'PROCESSING_REFUND', label: '加工返款审批' }
  ,{ value: 'MEMBER_CLAIM', label: '会员认领' }
]
const typeLabel = (type) => typeOptions.find(item => item.value === type)?.label || type || '-'
const pendingRows = computed(() => selectedType.value === 'ALL' ? pending.value : pending.value.filter(row => row.type === selectedType.value))
const canHandle = row => ['ADMIN', 'MANAGER'].includes(auth.role) && Number(row?.status) === 1 && auth.can('approval:handle') && (row?.type !== 'STOCK_CHECK' || auth.can('stock:check:approve'))

async function load() {
  pending.value = await approvalApi.pending()
  history.value = await approvalApi.history()
}
async function showDetail(row) {
  if (!auth.can('approval:view')) return
  detail.value = await approvalApi.detail(row.approval_id)
  detailVisible.value = true
}
async function approve(id) {
  if (submitting.value) return
  if (!canHandle(pending.value.find(row => Number(row.approval_id) === Number(id)) || detail.value?.approval)) return ElMessage.warning('当前账号没有该类型审批权限')
  submitting.value = true
  try {
    const current = await approvalApi.detail(id)
    const excess = Number(current?.order?.old_material_excess || 0)
    const body = {}
    if (current?.approval?.type === 'REFUND' && excess > 0) {
      await ElMessageBox.confirm(`本单曾向客户返款 ${formatMoney(excess)}。确认已按原返款渠道收回全额款项，并已核对退还旧料？`, '退款资金核对', { type: 'warning', confirmButtonText: '已收回并核对', cancelButtonText: '暂不退款' })
      body.oldMaterialExcessRecovered = true
    }
    await approvalApi.approve(id, body)
    ElMessage.success('审批已通过')
    detailVisible.value = false
    await load()
  } catch (error) {
    if (!['cancel', 'close'].includes(error)) ElMessage.error(error?.message || '审批失败')
  } finally {
    submitting.value = false
  }
}
async function reject(id) {
  if (submitting.value) return
  if (!canHandle(pending.value.find(row => Number(row.approval_id) === Number(id)) || detail.value?.approval)) return ElMessage.warning('当前账号没有该类型审批权限')
  submitting.value = true
  try {
    const { value } = await ElMessageBox.prompt('请输入驳回原因', '驳回审批', { inputPattern: /\S+/, inputErrorMessage: '原因不能为空' })
    await approvalApi.reject(id, value.trim())
    ElMessage.success('已驳回')
    detailVisible.value = false
    await load()
  } catch (error) {
    if (!['cancel', 'close'].includes(error)) ElMessage.error(error?.message || '驳回失败，请重试')
  } finally {
    submitting.value = false
  }
}
function checkRows() {
  const raw = detail.value?.stockCheck?.detail
  if (!raw) return []
  try { return typeof raw === 'string' ? JSON.parse(raw) : raw } catch { return [] }
}
onMounted(load)
</script>

<template>
  <el-tabs>
    <el-tab-pane label="待审批">
      <section class="panel">
        <el-radio-group v-model="selectedType" style="margin-bottom: 16px">
          <el-radio-button v-for="item in typeOptions" :key="item.value" :label="item.value">{{ item.label }}</el-radio-button>
        </el-radio-group>
        <el-table :data="pendingRows">
          <el-table-column prop="approval_id" label="编号" width="80"/>
          <el-table-column label="类型"><template #default="scope">{{ typeLabel(scope.row.type) }}</template></el-table-column>
          <el-table-column prop="biz_id" label="关联单据"/>
          <el-table-column label="金额"><template #default="scope">{{ formatMoney(scope.row.amount) }}</template></el-table-column>
      <el-table-column label="申请原因"><template #default="scope">{{ formatApprovalReason(scope.row) }}</template></el-table-column>
          <el-table-column label="申请时间" min-width="180"><template #default="scope">{{ formatTime(scope.row.create_time) }}</template></el-table-column>
          <el-table-column label="操作" width="190"><template #default="scope"><el-button v-if="auth.can('approval:view')" link @click="showDetail(scope.row)">详情</el-button><el-button v-if="canHandle(scope.row)" :disabled="submitting" link type="success" @click="approve(scope.row.approval_id)">通过</el-button><el-button v-if="canHandle(scope.row)" :disabled="submitting" link type="danger" @click="reject(scope.row.approval_id)">驳回</el-button></template></el-table-column>
        </el-table>
      </section>
    </el-tab-pane>
    <el-tab-pane label="审批历史">
      <section class="panel">
        <el-table :data="history">
          <el-table-column prop="approval_id" label="编号"/>
          <el-table-column label="类型"><template #default="scope">{{ typeLabel(scope.row.type) }}</template></el-table-column>
          <el-table-column prop="status" label="状态"/>
          <el-table-column prop="approve_remark" label="备注"/>
          <el-table-column label="处理时间" min-width="180"><template #default="scope">{{ formatTime(scope.row.approve_time) }}</template></el-table-column>
        </el-table>
      </section>
    </el-tab-pane>
  </el-tabs>
  <el-dialog v-model="detailVisible" title="审批详情" width="640px">
    <el-descriptions v-if="detail?.approval" :column="2" border>
      <el-descriptions-item label="审批类型">{{ typeLabel(detail.approval.type) }}</el-descriptions-item>
      <el-descriptions-item label="关联单据">{{ detail.approval.biz_id }}</el-descriptions-item>
      <el-descriptions-item label="申请金额">{{ formatMoney(detail.approval.amount) }}</el-descriptions-item>
      <el-descriptions-item label="申请时间">{{ formatTime(detail.approval.create_time) }}</el-descriptions-item>
      <el-descriptions-item label="申请原因" :span="2">{{ formatApprovalReason(detail.approval) }}</el-descriptions-item>
    </el-descriptions>
    <template v-if="detail?.approval?.type === 'STOCK_CHECK'">
      <el-divider content-position="left">盘点商品明细</el-divider>
      <el-descriptions :column="2" border style="margin-bottom: 16px">
        <el-descriptions-item label="盘点单号">{{ detail.stockCheck?.bill_no || '-' }}</el-descriptions-item>
        <el-descriptions-item label="盘点人">{{ detail.stockCheck?.operator_name || '-' }}</el-descriptions-item>
        <el-descriptions-item label="盘点范围">{{ detail.stockCheck?.scope_name || '-' }}</el-descriptions-item>
        <el-descriptions-item label="盘点时间">{{ formatTime(detail.stockCheck?.create_time) }}</el-descriptions-item>
        <el-descriptions-item label="盘点备注" :span="2">{{ detail.stockCheck?.remark || '-' }}</el-descriptions-item>
      </el-descriptions>
      <el-table :data="checkRows()" border>
        <el-table-column prop="barcode" label="条码" min-width="120"/>
        <el-table-column prop="name" label="商品" min-width="120"><template #default="scope">{{ scope.row.name || `商品 ${scope.row.goodsId}` }}</template></el-table-column>
        <el-table-column label="分类" min-width="130"><template #default="scope">{{ [scope.row.parentCategoryName, scope.row.categoryName].filter(Boolean).join(' / ') || '-' }}</template></el-table-column>
        <el-table-column prop="stock" label="系统库存"/>
        <el-table-column prop="actual" label="实盘数量"/>
        <el-table-column label="数量差异"><template #default="scope">{{ scope.row.difference ?? (Number(scope.row.actual || 0) - Number(scope.row.stock || 0)) }}</template></el-table-column>
        <el-table-column label="金额差异"><template #default="scope">{{ formatMoney(scope.row.differenceAmount || 0) }}</template></el-table-column>
      </el-table>
    </template>
    <template v-if="['SALE_PAYMENT_DISCOUNT', 'PROCESSING_PAYMENT_DISCOUNT'].includes(detail?.approval?.type)">
      <el-divider content-position="left">优惠收款明细</el-divider>
      <el-descriptions :column="2" border style="margin-bottom: 16px">
        <el-descriptions-item label="原应收">{{ formatMoney(detail.payment?.originalDue) }}</el-descriptions-item>
        <el-descriptions-item label="本次/累计实收">{{ formatMoney(detail.payment?.actualPaid) }}</el-descriptions-item>
        <el-descriptions-item label="优惠金额">{{ formatMoney(detail.payment?.discount) }}</el-descriptions-item>
        <el-descriptions-item label="实际折扣">{{ detail.payment?.discountRate == null ? '-' : `${(Number(detail.payment.discountRate) * 100).toFixed(2)}折` }}</el-descriptions-item>
        <el-descriptions-item label="收款原因" :span="2">{{ detail.payment?.reason || '顾客优惠' }}</el-descriptions-item>
      </el-descriptions>
    </template>
    <template v-if="detail?.approval?.type === 'PROCESSING_REFUND'">
      <el-divider content-position="left">加工返款明细</el-divider>
      <el-descriptions :column="2" border style="margin-bottom: 16px">
        <el-descriptions-item label="加工单号">{{ detail.processingOrder?.order_no || detail.approval?.biz_id || '-' }}</el-descriptions-item>
        <el-descriptions-item label="返款金额">{{ formatMoney(detail.refund?.amount ?? detail.approval?.amount) }}</el-descriptions-item>
        <el-descriptions-item label="整单应收">{{ formatMoney(detail.processingOrder?.settlement_due_amount ?? detail.processingOrder?.due_amount) }}</el-descriptions-item>
        <el-descriptions-item label="已收金额">{{ formatMoney(detail.processingOrder?.actual_paid_amount ?? detail.processingOrder?.paid_amount) }}</el-descriptions-item>
        <el-descriptions-item label="申请原因" :span="2">{{ detail.refund?.reason || formatApprovalReason(detail.approval) }}</el-descriptions-item>
      </el-descriptions>
    </template>
    <template #footer><el-button @click="detailVisible = false">关闭</el-button><el-button v-if="detail?.approval?.status === 1 && canHandle(detail?.approval)" type="success" :disabled="submitting" @click="approve(detail.approval.approval_id)">通过</el-button><el-button v-if="detail?.approval?.status === 1 && canHandle(detail?.approval)" type="danger" :disabled="submitting" @click="reject(detail.approval.approval_id)">驳回</el-button></template>
  </el-dialog>
</template>
