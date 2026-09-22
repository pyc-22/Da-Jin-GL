<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { goodsApi, stockApi } from '../api/modules'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  title: { type: String, default: '商品分类管理' }
})
const emit = defineEmits(['update:modelValue', 'changed'])

const rows = ref([])
const counts = ref(new Map())
const loading = ref(false)
const cleaning = ref(false)
const selectedRootId = ref(null)
const formDialog = ref(false)
const editing = ref(null)
const saving = ref(false)
const form = reactive({ name: '' })
const moveDialog = ref(false)
const moving = ref(null)
const moveTargetId = ref(null)
const moveSaving = ref(false)

const roots = computed(() => rows.value.filter(row => Number(row.level) === 1))
const selectedRoot = computed(() => roots.value.find(row => Number(row.category_id) === Number(selectedRootId.value)) || null)
const children = computed(() => rows.value.filter(row => Number(row.level) === 2 && Number(row.parent_id) === Number(selectedRootId.value)))
const formTitle = computed(() => {
  if (!editing.value) return ''
  if (editing.value.row) return '编辑分类'
  return editing.value.level === 1 ? '新增一级分类' : `新增二级分类（${selectedRoot.value?.name || ''}）`
})

function close() { emit('update:modelValue', false) }
async function load() {
  loading.value = true
  try {
    const [categoryRows, overviewRows] = await Promise.all([goodsApi.categories() || [], stockApi.overview().catch(() => [])])
    rows.value = categoryRows
    const map = new Map()
    for (const row of (Array.isArray(overviewRows) ? overviewRows : [])) {
      map.set(Number(row.category_id), { count: Number(row.goods_count ?? row.quantity ?? 0), amount: Number(row.amount || 0) })
      for (const child of (row.children || [])) map.set(Number(child.category_id), { count: Number(child.goods_count ?? child.quantity ?? 0), amount: Number(child.amount || 0) })
    }
    counts.value = map
    if (selectedRootId.value && !roots.value.some(row => Number(row.category_id) === Number(selectedRootId.value))) selectedRootId.value = null
  } catch (error) {
    ElMessage.error(error?.message || '分类列表加载失败')
  } finally { loading.value = false }
}
const countOf = categoryId => counts.value.get(Number(categoryId))?.count ?? null
const isEmptyCategory = row => (countOf(row.category_id) ?? 0) <= 0
const countText = row => { const info = counts.value.get(Number(row.category_id)); return info && info.count > 0 ? `${info.count} 件 · ¥${Number(info.amount || 0).toFixed(0)}` : '' }
const rootHasNoGoods = row => isEmptyCategory(row) && !rows.value.some(child => Number(child.level) === 2 && Number(child.parent_id) === Number(row.category_id) && !isEmptyCategory(child))
async function cleanupEmpty() {
  const targets = [
    ...rows.value.filter(row => Number(row.level) === 2 && isEmptyCategory(row)),
    ...roots.value.filter(rootHasNoGoods)
  ]
  if (!targets.length) return ElMessage.success('没有可清理的空分类')
  const preview = targets.slice(0, 8).map(row => row.name).join('、')
  try {
    await ElMessageBox.confirm(`发现 ${targets.length} 个无货品的空分类：${preview}${targets.length > 8 ? ' 等' : ''}。有货的分类会自动跳过，确定删除这些空分类吗？`, '清理空分类', { type: 'warning', confirmButtonText: '全部删除', cancelButtonText: '取消' })
  } catch (error) { if (error === 'cancel' || error === 'close' || error?.message === 'cancel') return }
  cleaning.value = true
  let removed = 0, skipped = 0
  try {
    for (const target of targets) {
      try { await goodsApi.deleteCategory(target.category_id); removed++ } catch { skipped++ }
    }
    await load()
    emit('changed', rows.value)
    if (removed) ElMessage.success(`已删除 ${removed} 个空分类${skipped ? `，${skipped} 个因存在关联数据跳过` : ''}`)
    else ElMessage.warning('没有删除任何分类：存在关联数据，请改用禁用')
  } finally { cleaning.value = false }
}
function selectRoot(row) { selectedRootId.value = Number(row.category_id) }
function openCreate(level) {
  if (level === 2 && !selectedRoot.value) return ElMessage.warning('请先在左侧选择一级分类')
  editing.value = { level }
  form.name = ''
  formDialog.value = true
}
function openEdit(row) {
  editing.value = { level: Number(row.level) === 1 ? 1 : 2, row }
  form.name = row.name || ''
  formDialog.value = true
}
async function save() {
  const name = form.name.trim()
  if (!name) return ElMessage.warning('请输入分类名称')
  if (name.length > 100) return ElMessage.warning('分类名称不能超过100个字符')
  saving.value = true
  try {
    if (editing.value.row) {
      await goodsApi.updateCategory(editing.value.row.category_id, { name })
      if (Number(editing.value.row.category_id) === Number(selectedRootId.value)) selectedRootId.value = Number(editing.value.row.category_id)
    } else if (editing.value.level === 1) {
      await goodsApi.createCategory({ name, parentId: 0 })
    } else {
      await goodsApi.createCategory({ name, parentId: selectedRoot.value.category_id })
    }
    formDialog.value = false
    await load()
    emit('changed', rows.value)
    ElMessage.success('分类已保存')
  } catch (error) { ElMessage.error(error?.message || '分类保存失败') }
  finally { saving.value = false }
}
function openMove(row) {
  moving.value = row
  moveTargetId.value = Number(row.parent_id) || null
  moveDialog.value = true
}
async function confirmMove() {
  if (!moving.value) return
  if (!moveTargetId.value) return ElMessage.warning('请选择目标一级分类')
  if (Number(moveTargetId.value) === Number(moving.value.parent_id)) { moveDialog.value = false; return }
  moveSaving.value = true
  try {
    await goodsApi.updateCategory(moving.value.category_id, { parentId: Number(moveTargetId.value) })
    moveDialog.value = false
    await load()
    emit('changed', rows.value)
    ElMessage.success(`“${moving.value.name}”已移动到「${roots.value.find(r => Number(r.category_id) === Number(moveTargetId.value))?.name || ''}」下`)
  } catch (error) { ElMessage.error(error?.message || '分类移动失败') }
  finally { moveSaving.value = false }
}
async function toggle(row) {
  const enabled = Number(row.status) === 1
  try {
    await goodsApi.updateCategory(row.category_id, { status: enabled ? 0 : 1 })
    await load()
    emit('changed', rows.value)
    ElMessage.success(enabled ? '分类已禁用' : '分类已启用')
  } catch (error) { ElMessage.error(error?.message || '分类状态更新失败') }
}
async function remove(row) {
  const hasChildren = roots.value.some(root => Number(root.category_id) === Number(row.category_id))
    && rows.value.some(child => Number(child.level) === 2 && Number(child.parent_id) === Number(row.category_id))
  if (hasChildren) return ElMessage.warning('该分类存在子类，请先处理子类')
  try {
    await ElMessageBox.confirm(`确定删除分类“${row.name}”吗？删除后不可恢复。`, '二次确认', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  } catch (error) { if (error === 'cancel' || error === 'close' || error?.message === 'cancel') return }
  try {
    await goodsApi.deleteCategory(row.category_id)
    if (Number(selectedRootId.value) === Number(row.category_id)) selectedRootId.value = null
    await load()
    emit('changed', rows.value)
    ElMessage.success('分类已删除')
  } catch (error) { ElMessage.error(error?.message || '分类删除失败') }
}

watch(() => props.modelValue, value => { if (value) load() })
defineExpose({ load, openCreate, openEdit, toggle, remove })
</script>

<template>
  <el-dialog :model-value="modelValue" :title="title" width="880px" top="6vh" @update:model-value="emit('update:modelValue', $event)">
    <div class="category-manager" v-loading="loading">
      <div class="category-toolbar"><span class="muted">数量与金额按分类下的货品统计，空分类可一键清理。</span><el-button size="small" :loading="cleaning" @click="cleanupEmpty">清理空分类</el-button></div>
      <section class="category-pane">
        <div class="category-pane-head"><b>一级分类</b><el-button type="primary" size="small" @click="openCreate(1)">+ 新增一级</el-button></div>
        <div v-if="!roots.length" class="category-empty">暂无一级分类，点击右上角新增</div>
        <div v-for="root in roots" :key="root.category_id" class="category-row" :class="{ active: Number(selectedRootId) === Number(root.category_id), empty: rootHasNoGoods(root) }" @click="selectRoot(root)">
          <span class="category-name">{{ root.name }}<small v-if="countText(root)" class="category-count">{{ countText(root) }}</small></span>
          <el-tag v-if="rootHasNoGoods(root)" size="small" type="info">空</el-tag>
          <el-tag size="small" :type="Number(root.status) === 1 ? 'success' : 'info'">{{ Number(root.status) === 1 ? '启用' : '禁用' }}</el-tag>
          <span class="category-actions" @click.stop>
            <el-button link type="primary" size="small" @click="openEdit(root)">编辑</el-button>
            <el-button link :type="Number(root.status) === 1 ? 'warning' : 'success'" size="small" @click="toggle(root)">{{ Number(root.status) === 1 ? '禁用' : '启用' }}</el-button>
            <el-button link type="danger" size="small" @click="remove(root)">删除</el-button>
          </span>
        </div>
      </section>
      <section class="category-pane">
        <div class="category-pane-head">
          <b>{{ selectedRoot ? `${selectedRoot.name} 的子类` : '二级子类' }}</b>
          <el-tooltip :disabled="Boolean(selectedRoot)" content="请先在左侧选择一级分类" placement="top">
            <span><el-button type="primary" size="small" :disabled="!selectedRoot" @click="openCreate(2)">+ 新增二级</el-button></span>
          </el-tooltip>
        </div>
        <div v-if="!selectedRoot" class="category-empty">请先在左侧选择一级分类，即可查看和新增二级子类</div>
        <template v-else>
          <div v-if="!children.length" class="category-empty">该一级分类下暂无子类</div>
          <div v-for="child in children" :key="child.category_id" class="category-row child-row" :class="{ empty: isEmptyCategory(child) }">
            <span class="category-name">{{ child.name }}<small v-if="countText(child)" class="category-count">{{ countText(child) }}</small></span>
            <el-tag v-if="isEmptyCategory(child)" size="small" type="info">空</el-tag>
            <el-tag size="small" :type="Number(child.status) === 1 ? 'success' : 'info'">{{ Number(child.status) === 1 ? '启用' : '禁用' }}</el-tag>
            <span class="category-actions">
              <el-button link type="primary" size="small" @click="openEdit(child)">编辑</el-button>
              <el-button link type="primary" size="small" @click="openMove(child)">移动</el-button>
              <el-button link :type="Number(child.status) === 1 ? 'warning' : 'success'" size="small" @click="toggle(child)">{{ Number(child.status) === 1 ? '禁用' : '启用' }}</el-button>
              <el-button link type="danger" size="small" @click="remove(child)">删除</el-button>
            </span>
          </div>
        </template>
      </section>
    </div>
    <el-dialog v-model="moveDialog" :title="`移动二级分类“${moving?.name || ''}”`" width="420px" append-to-body>
      <el-form label-width="110px" @submit.prevent="confirmMove">
        <el-form-item label="当前所属"><el-input :model-value="roots.find(r => Number(r.category_id) === Number(moving?.parent_id))?.name || '-'" disabled /></el-form-item>
        <el-form-item label="移动到" required>
          <el-select v-model="moveTargetId" placeholder="选择目标一级分类" style="width:100%">
            <el-option v-for="root in roots.filter(r => Number(r.category_id) !== Number(moving?.parent_id))" :key="root.category_id" :value="Number(root.category_id)" :label="root.name" :disabled="Number(root.status) !== 1" />
          </el-select>
        </el-form-item>
        <el-alert type="info" :closable="false" show-icon title="移动后，该子类下的全部货品会跟着出现在目标一级分类的库存里，收银端分类同步变化。" />
      </el-form>
      <template #footer><el-button @click="moveDialog = false">取消</el-button><el-button type="primary" :loading="moveSaving" @click="confirmMove">确认移动</el-button></template>
    </el-dialog>
    <el-dialog v-model="formDialog" :title="formTitle" width="420px" append-to-body>
      <el-form label-width="90px" @submit.prevent="save">
        <el-form-item label="分类名称" required><el-input v-model="form.name" maxlength="100" placeholder="请输入分类名称" @keyup.enter="save" /></el-form-item>
        <el-form-item label="分类编码"><el-input :model-value="editing?.row?.category_code || '自动生成'" disabled /></el-form-item>
      </el-form>
      <template #footer><el-button @click="formDialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>
  </el-dialog>
</template>

<style scoped>
.category-manager { display: grid; grid-template-columns: 1fr 1.2fr; gap: 14px; }
.category-toolbar { grid-column: 1 / -1; display: flex; justify-content: flex-end; align-items: center; gap: 10px; }
.category-toolbar .muted { color: #909399; font-size: 12px; }
.category-count { display: inline-block; margin-left: 8px; color: #b7791f; font-size: 12px; }
.category-row.empty .category-name { color: #c0c4cc; }
.category-pane { border: 1px solid #e4e7ed; border-radius: 6px; min-height: 320px; max-height: 430px; overflow-y: auto; padding: 10px; }
.category-pane-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.category-row { display: flex; align-items: center; gap: 8px; padding: 8px 10px; border-radius: 5px; cursor: pointer; }
.category-row:hover { background: #f5f7fa; }
.category-row.active { background: var(--el-color-primary-light-9); }
.category-row.child-row { cursor: default; }
.category-name { flex: 1; }
.category-actions { display: inline-flex; }
.category-empty { color: #909399; font-size: 13px; text-align: center; padding: 30px 0; }
</style>
