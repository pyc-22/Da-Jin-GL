<script setup>
import { onMounted, ref, reactive, computed, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { stockApi, goodsApi, goldApi, uploadApi } from '../api/modules'
import CategoryManagementDialog from '../components/CategoryManagementDialog.vue'
import { formatMoney, formatTime } from '../utils/format'
import { useAppStore } from '../stores/app'
const app=useAppStore()
const tab=ref('overview'), overview=ref([]), warnings=ref([]), old=ref([]), records=ref([]), outRecords=ref([]), goods=ref([]), dialog=ref(false), processDialog=ref(false), checkDialog=ref(false), categoryDialog=ref(false)
const overviewTree=computed(()=>overview.value.map(row=>({...row,children:row.children?.length?row.children:undefined})))
const form=reactive({goodsId:null,qty:1,cost:0,billNo:'',expectedVersion:0,remark:''}), processForm=reactive({goodsId:null,qty:1,cost:0,billNo:'',expectedVersion:0,direction:'IN'}), checkForm=reactive({billNo:'',goodsId:null,actual:0})
const manualDialog=ref(false), manualDirection=ref('IN'), manualForm=reactive({goodsId:null,qty:1,cost:0,reason:'损耗',remark:'',expectedVersion:0})
const oldAdjustDialog=ref(false), oldAdjustDirection=ref('IN'), oldAdjustForm=reactive({materialType:'足金999',weight:1,purity:0.999,source:'其他',reason:'其他',remark:''})
const oldMaterialDialog=ref(false), editingOldMaterialId=ref(null), savingOldMaterial=ref(false)
const oldMaterialTable=ref(null)
const defaultOldMaterialTypeRows=[{name:'足金999',status:1},{name:'足金990',status:1},{name:'22K金',status:1},{name:'18K金',status:1},{name:'14K金',status:1},{name:'铂金950',status:1},{name:'铂金900',status:1},{name:'纯银',status:1}]
const oldMaterialTypeRows=ref(defaultOldMaterialTypeRows.map(row=>({...row})))
const oldMaterialTypes=ref(defaultOldMaterialTypeRows.map(row=>row.name))
const oldTypeDialog=ref(false), oldTypeFormDialog=ref(false), editingOldType=ref(null), savingOldTypes=ref(false)
const quickTypeDialog=ref(false), quickTypeName=ref(''), quickTypeTarget=ref('')
const oldTypeForm=reactive({name:'',status:1})
const oldMaterialForm=reactive({materialType:'足金999',weight:0,purityPercent:99.9,value:0})
const goodsLabel = row => {
  const parent = row?.parent_category || row?.parentCategory || '未设置一级分类'
  const child = row?.category || '未设置二级分类'
  return `${parent} / ${child} · ${row?.name || '未命名货品'}（库存 ${row?.stock ?? 0}）`
}
const selectedManualGoods = computed(() => goods.value.find(item => Number(item.goods_id) === Number(manualForm.goodsId)) || null)
const manualMode = ref('existing')
const manualImages = ref([]), manualImageInput = ref(null), manualImageUploading = ref(false)
const categoryList = ref([]); const goldTypes = ref([]); const createSaving = ref(false)
const createForm = reactive({ barcode: '', name: '', parentCategoryId: null, categoryId: null, weight: null, costPrice: 0, salePrice: 0, priceType: 2, goldType: '足金', qty: 1, remark: '' })
const createRoots = computed(() => categoryList.value.filter(c => Number(c.level) === 1))
const createChildren = computed(() => categoryList.value.filter(c => Number(c.level) === 2 && Number(c.parent_id) === Number(createForm.parentCategoryId) && Number(c.status) === 1))
async function ensureCreateOptions() {
  if (!categoryList.value.length) { try { categoryList.value = await goodsApi.categories() || [] } catch { categoryList.value = [] } }
  if (!goldTypes.value.length) { try { goldTypes.value = await goldApi.types() || [] } catch { goldTypes.value = [] } }
}
function onManualModeChange(value) { if (value === 'create') { createForm.parentCategoryId = null; createForm.categoryId = null; ensureCreateOptions() } }
const parseImages=value=>{let rows=value;if(typeof rows==='string'){try{rows=JSON.parse(rows||'[]')}catch{rows=[]}}return Array.isArray(rows)?rows.filter(Boolean):[]}
const rowImages=row=>parseImages(row?.images)
async function selectManualImages(event) {
  const files = [...(event.target.files || [])]
  event.target.value = ''
  if (!files.length) return
  if (manualImages.value.length + files.length > 4) return ElMessage.warning('每次入库最多上传4张照片')
  const invalid = files.find(file => !String(file.type || '').startsWith('image/') || file.size > 5 * 1024 * 1024)
  if (invalid) return ElMessage.warning('仅支持图片文件，单张不能超过5MB')
  manualImageUploading.value = true
  try {
    for (const file of files) {
      const uploaded = await uploadApi.image(file)
      manualImages.value.push(uploaded.url)
    }
    ElMessage.success('照片已上传')
  } catch (error) {
    ElMessage.error(error?.message || '照片上传失败')
  } finally {
    manualImageUploading.value = false
  }
}
function removeManualImage(index) { manualImages.value.splice(index, 1) }
async function submitCreateAndInbound() {
  if (!createForm.name.trim()) return ElMessage.warning('请填写货品名称')
  if (!createForm.categoryId) return ElMessage.warning('请选择二级分类')
  if (!(Number(createForm.qty) > 0)) return ElMessage.warning('入库数量必须大于0')
  if (!(Number(createForm.costPrice) >= 0) || createForm.costPrice === '') return ElMessage.warning('请填写成本价')
  if (!(Number(createForm.salePrice) >= 0) || createForm.salePrice === '') return ElMessage.warning('请填写售价')
  if (Number(createForm.priceType) === 1 && !(Number(createForm.weight) > 0)) return ElMessage.warning('按克商品的克重必须大于0')
  createSaving.value = true
  try {
    const barcode = String(createForm.barcode || '').trim() || `M${Date.now()}`
    await goodsApi.create({ barcode, name: createForm.name.trim(), categoryId: createForm.categoryId, weight: Number(createForm.priceType) === 1 ? Number(createForm.weight || 0) : Number(createForm.weight || 0) || 0.001, costPrice: Number(createForm.costPrice), salePrice: Number(createForm.salePrice), priceType: Number(createForm.priceType), goldType: Number(createForm.priceType) === 1 ? createForm.goldType : null, stock: Number(createForm.qty), images: JSON.stringify(manualImages.value), certificateNo: '' })
    ElMessage.success(`货品已建档并入库 ${createForm.qty} 件（条码 ${barcode}），当前为“库存待上架”，上架后才会出现在收银端`)
    manualMode.value = 'existing'; Object.assign(createForm, { barcode: '', name: '', parentCategoryId: null, categoryId: null, weight: null, costPrice: 0, salePrice: 0, priceType: 2, goldType: '足金', qty: 1, remark: '' })
    manualImages.value = []; manualDialog.value = false
    await load()
  } catch (error) { ElMessage.error(error?.message || '建档入库失败') }
  finally { createSaving.value = false }
}
function applyOldMaterialTypes(rows){
  oldMaterialTypeRows.value=rows||[]
  oldMaterialTypes.value=oldMaterialTypeRows.value.filter(row=>Number(row.status)===1).map(row=>row.name)
  if(oldMaterialTypes.value.length){
    if(!oldMaterialTypes.value.includes(oldAdjustForm.materialType)) oldAdjustForm.materialType=oldMaterialTypes.value[0]
    if(!oldMaterialTypes.value.includes(oldMaterialForm.materialType)) oldMaterialForm.materialType=oldMaterialTypes.value[0]
  }
}
async function loadOldMaterialTypes(){
  try { applyOldMaterialTypes(await stockApi.oldMaterialTypes()) }
  catch(error){ console.warn('旧料类型加载失败:',error?.message||error) }
}
async function syncInventoryTypes(){
  const inventoryTypes=[...new Set(old.value.map(row=>String(row.material_type||'').trim()).filter(Boolean))]
  const missing=inventoryTypes.filter(name=>!oldMaterialTypeRows.value.some(row=>row.name===name))
  if(!missing.length)return
  for(const name of missing){ try { await stockApi.createOldMaterialType({name}) } catch(error){ console.warn('旧料库存类型同步失败:',error?.message||error) } }
  await loadOldMaterialTypes()
}
async function load(){const [overviewRows,warningRows,oldRows,inRows,outRows,goodsResult]=await Promise.all([stockApi.overview(),stockApi.warnings(),stockApi.old(),stockApi.inRecords(),stockApi.outRecords(),goodsApi.list({page:1,size:200,includeInactive:true})]);overview.value=overviewRows||[];warnings.value=warningRows||[];old.value=oldRows||[];records.value=inRows||[];outRecords.value=outRows||[];goods.value=goodsResult?.records||[];await loadOldMaterialTypes();await syncInventoryTypes()}
async function onCategoriesChanged(){await load()}
async function inbound(){const row=goods.value.find(item=>Number(item.goods_id)===Number(form.goodsId));if(!row)return ElMessage.warning('请选择商品');form.expectedVersion=Number(row.version||0);if(!form.remark.trim())return ElMessage.warning('请填写入库原因');await stockApi.in(form);dialog.value=false;ElMessage.success('入库成功');load()}
function openManual(direction){manualDirection.value=direction;manualImages.value=[];Object.assign(manualForm,{goodsId:null,qty:1,cost:0,reason:direction==='IN'?'盘点补录':'损耗',remark:'',expectedVersion:0});manualDialog.value=true}
async function submitManual(){const row=goods.value.find(item=>Number(item.goods_id)===Number(manualForm.goodsId));if(!row)return ElMessage.warning('请选择商品');if(!(Number(manualForm.qty)>0))return ElMessage.warning('数量必须大于0');if(manualDirection.value==='IN'&&(!(manualForm.cost!==null&&manualForm.cost!==''&&Number(manualForm.cost)>=0)||!manualForm.remark.trim()))return ElMessage.warning('入库成本价和备注不能为空');manualForm.expectedVersion=Number(row.version||0);const result=manualDirection.value==='IN'?await stockApi.in({...manualForm,costPrice:Number(manualForm.cost),images:[...manualImages.value],billNo:`MI${Date.now()}`}):await stockApi.out({...manualForm,billNo:`MO${Date.now()}`});manualImages.value=[];manualDialog.value=false;ElMessage.success(result?.approvalRequired?'已提交大额出库审批':'库存调整成功');await load()}
async function submitCheck(){const goodsRow=goods.value.find(item=>Number(item.goods_id)===Number(checkForm.goodsId));if(!goodsRow)return ElMessage.warning('请选择盘点商品');if(Number(checkForm.actual)<0)return ElMessage.warning('实盘数量不能小于0');if(!checkForm.billNo)checkForm.billNo=`PD${Date.now()}`;await stockApi.check({billNo:checkForm.billNo,rows:[{goodsId:goodsRow.goods_id,stock:Number(goodsRow.stock||0),actual:Number(checkForm.actual)}]});checkDialog.value=false;ElMessage.success('盘点单已提交审批')}
async function processMove(){const api=processForm.direction==='IN'?stockApi.processIn:stockApi.processOut;await api(processForm);processDialog.value=false;ElMessage.success(processForm.direction==='IN'?'成品入库成功':'加工出库成功');load()}
function openOldMaterial(row=null){editingOldMaterialId.value=row?.material_id??null;oldMaterialForm.materialType=row?.material_type||oldMaterialTypes.value[0]||'';oldMaterialForm.weight=Number(row?.weight||0);oldMaterialForm.purityPercent=row?Number(row.purity||0)*100:99.9;oldMaterialForm.value=Number(row?.value||0);oldMaterialDialog.value=true}
async function saveOldMaterial(){if(!oldMaterialForm.materialType)return ElMessage.warning('请选择旧料类型');if(!(Number(oldMaterialForm.weight)>0))return ElMessage.warning('克重必须大于0');if(!(Number(oldMaterialForm.purityPercent)>0&&Number(oldMaterialForm.purityPercent)<=100))return ElMessage.warning('成色必须在0到100之间');if(Number(oldMaterialForm.value)<0)return ElMessage.warning('估值不能小于0');savingOldMaterial.value=true;try{const payload={materialType:oldMaterialForm.materialType,weight:Number(oldMaterialForm.weight),purity:Number(oldMaterialForm.purityPercent)/100,value:Number(oldMaterialForm.value)};if(editingOldMaterialId.value)await stockApi.updateOldMaterial(editingOldMaterialId.value,payload);else await stockApi.createOldMaterial(payload);oldMaterialDialog.value=false;ElMessage.success(editingOldMaterialId.value?'旧料记录已更新':'旧料已入库');await load()}finally{savingOldMaterial.value=false}}
function sourceText(source){const value=String(source||'');if(value.startsWith('ORDER:'))return '销售旧金抵扣';if(value.startsWith('TRADEIN:'))return '以旧换新';if(value.startsWith('RECYCLE:'))return '旧料回收';if(value.startsWith('MANUAL:'))return '手工入库';return value||'-'}
function editOldMaterialGroup(row){const details=row.details||[];if(details.length===1)return openOldMaterial(details[0]);if(!details.length)return ElMessage.info('该类型暂无入库明细');oldMaterialTable.value?.toggleRowExpansion(row,true)}
function openOldAdjust(direction){oldAdjustDirection.value=direction;Object.assign(oldAdjustForm,{materialType:oldMaterialTypes.value[0]||'',weight:1,purity:0.999,source:'其他',reason:'其他',remark:''});oldAdjustDialog.value=true}
async function submitOldAdjust(){if(!(Number(oldAdjustForm.weight)>0)||!(Number(oldAdjustForm.purity)>0&&Number(oldAdjustForm.purity)<=1))return ElMessage.warning('请输入有效克重和成色');if(!oldAdjustForm.remark.trim())return ElMessage.warning('请填写备注');if(oldAdjustDirection.value==='IN')await stockApi.oldIn({...oldAdjustForm,weight:Number(oldAdjustForm.weight),purity:Number(oldAdjustForm.purity)});else await stockApi.oldOut({...oldAdjustForm,weight:Number(oldAdjustForm.weight),purity:Number(oldAdjustForm.purity)});oldAdjustDialog.value=false;ElMessage.success(oldAdjustDirection.value==='IN'?'旧料已入库':'旧料已出库');await load()}
function openOldTypeCreate(){editingOldType.value=null;Object.assign(oldTypeForm,{name:'',status:1});oldTypeFormDialog.value=true}
function openQuickType(target){quickTypeTarget.value=target;quickTypeName.value='';quickTypeDialog.value=true}
async function saveQuickType(){
  const name=String(quickTypeName.value||'').trim()
  if(!name)return ElMessage.warning('请输入类型名称')
  if(name.length>50)return ElMessage.warning('类型名称不能超过50个字符')
  if(oldMaterialTypeRows.value.some(row=>row.name===name))return ElMessage.warning('该旧料类型已存在')
  try{
    await stockApi.createOldMaterialType({name})
    await load()
    quickTypeDialog.value=false
    if(quickTypeTarget.value==='adjust')oldAdjustForm.materialType=name
    else oldMaterialForm.materialType=name
    ElMessage.success('类型已创建并选中')
  }catch(error){ElMessage.error(error?.message||'类型创建失败')}
}
function openOldTypeEdit(row){editingOldType.value=row;Object.assign(oldTypeForm,{name:row.name,status:Number(row.status)===1?1:0});oldTypeFormDialog.value=true}
async function saveOldType(){
  const name=String(oldTypeForm.name||'').trim()
  if(!name)return ElMessage.warning('请输入旧料类型名称')
  if(name.length>50)return ElMessage.warning('旧料类型名称不能超过50个字符')
  const duplicate=oldMaterialTypeRows.value.some(row=>row.name===name&&Number(row.type_id)!==Number(editingOldType.value?.type_id))
  if(duplicate)return ElMessage.warning('该旧料类型已存在')
  savingOldTypes.value=true
  try {
    if(editingOldType.value)await stockApi.updateOldMaterialType(editingOldType.value.type_id,{name})
    else await stockApi.createOldMaterialType({name})
    oldTypeFormDialog.value=false
    await load()
    ElMessage.success('旧料类型已保存')
  } catch(error){ElMessage.error(error?.message||'旧料类型保存失败')}
  finally {savingOldTypes.value=false}
}
async function toggleOldType(row){
  const enabled=Number(row.status)===1
  if(enabled&&oldMaterialTypeRows.value.filter(item=>Number(item.status)===1).length===1)return ElMessage.warning('至少保留一种启用类型')
  try { await ElMessageBox.confirm(`${enabled?'禁用':'启用'}“${row.name}”后，收银端${enabled?'将不再':'将重新'}提供该类型。确定继续吗？`,'状态确认',{type:'warning'}) } catch(error){if(error==='cancel'||error==='close'||error?.message==='cancel')return}
  try { await stockApi.oldMaterialTypeStatus(row.type_id,enabled?0:1);await load();ElMessage.success(enabled?'旧料类型已禁用':'旧料类型已启用') } catch(error){ElMessage.error(error?.message||'状态更新失败')}
}
async function removeOldType(row){
  try { await ElMessageBox.confirm(`确定删除旧料类型“${row.name}”吗？删除后不可恢复。`,'二次确认',{type:'warning',confirmButtonText:'删除',cancelButtonText:'取消'}) } catch(error){if(error==='cancel'||error==='close'||error?.message==='cancel')return}
  try { await stockApi.deleteOldMaterialType(row.type_id);await load();ElMessage.success('旧料类型已删除') } catch(error){ElMessage.error(error?.message||'旧料类型删除失败')}
}
function oldTypeRow(name){ return oldMaterialTypeRows.value.find(row=>row.name===String(name||'')) }
function toggleOldTypeByName(name){ const row=oldTypeRow(name); if(row) toggleOldType(row) }
function removeOldTypeByName(name){ const row=oldTypeRow(name); if(row) removeOldType(row) }
const supplierDialog=ref(false), supplierSaving=ref(false), supplierRows=ref([])
const supplierForm=reactive({name:''})
function openSupplierDialog(){supplierForm.name='';supplierDialog.value=true;loadSuppliers()}
async function loadSuppliers(){try{supplierRows.value=await stockApi.suppliers()||[]}catch(error){ElMessage.error(error?.message||'供应商列表加载失败')}}
async function createSupplier(){
  const name=supplierForm.name.trim();if(!name)return ElMessage.warning('请输入供应商名称')
  supplierSaving.value=true
  try{await stockApi.supplierCreate({supplierName:name});supplierForm.name='';await loadSuppliers();ElMessage.success('供应商已新增')}catch(error){ElMessage.error(error?.message||'新增失败')}finally{supplierSaving.value=false}
}
async function renameSupplier(row){
  try{const {value}=await ElMessageBox.prompt(`修改供应商「${row.supplier_name}」名称`,'重命名',{inputValue:row.supplier_name,inputPattern:/\S/,inputErrorMessage:'名称不能为空',confirmButtonText:'保存',cancelButtonText:'取消'});await stockApi.supplierUpdate(row.supplier_id,{supplierName:value.trim()});await loadSuppliers();ElMessage.success('已保存')}catch(error){if(error!=='cancel'&&error!=='close'&&error?.message!=='cancel')ElMessage.error(error?.message||'保存失败')}
}
async function toggleSupplier(row){
  const enabled=Number(row.status)===1
  try{await ElMessageBox.confirm(`${enabled?'停用':'启用'}「${row.supplier_name}」后，入库选择${enabled?'将不再':'将重新'}提供该供应商。确定继续吗？`,'状态确认',{type:'warning'})}catch(error){if(error==='cancel'||error==='close'||error?.message==='cancel')return}
  try{await stockApi.supplierUpdate(row.supplier_id,{status:enabled?0:1});await loadSuppliers();ElMessage.success(enabled?'供应商已停用':'供应商已启用')}catch(error){ElMessage.error(error?.message||'状态更新失败')}
}
onMounted(load)
watch(()=>app.eventVersion,()=>{if(['STOCK_UPDATED','STOCK_IN_COMPLETED','OLD_MATERIAL_UPDATED','OLD_MATERIAL_TYPES_UPDATED','SUPPLIERS_UPDATED','APPROVAL_DECIDED','GOODS_UPDATED'].includes(app.lastEventType))load()})
</script>
<template>
<div class="stock-tabs-header"><el-tabs v-model="tab"><el-tab-pane label="库存总览" name="overview"/><el-tab-pane label="出入库记录" name="records"/><el-tab-pane label="加工业务" name="process"/><el-tab-pane label="旧料库存" name="old"/><el-tab-pane label="预警" name="warning"/></el-tabs><div class="stock-tab-actions" v-if="tab==='overview'"><el-button @click="openManual('OUT')">手动出库</el-button><el-button type="primary" @click="openManual('IN')">手动入库</el-button></div><div class="stock-tab-actions" v-else-if="tab==='old'"><el-button @click="openOldAdjust('OUT')">旧料出库</el-button><el-button type="primary" @click="openOldAdjust('IN')">旧料入库</el-button><el-button @click="openOldMaterial()">新增旧料</el-button><el-button type="primary" plain @click="oldTypeDialog=true">旧料类型管理</el-button></div></div>
<section v-if="tab==='overview'" class="panel"><div class="page-toolbar"><div><div class="panel-title" style="margin:0">全量库存</div><div class="muted">库存包含门店全部货品；“商品”页只展示已上架可售的子集。</div></div><div class="inline-actions"><el-button @click="categoryDialog=true">分类管理</el-button><el-button @click="openSupplierDialog">供应商管理</el-button><el-button @click="checkDialog=true">提交盘点单</el-button></div></div><el-table :data="overviewTree" row-key="category_id" default-expand-all :tree-props="{children:'children'}"><el-table-column prop="category" label="分类" min-width="220"/><el-table-column prop="goods_count" label="货品数" width="100"/><el-table-column prop="sale_goods_count" label="可售货品数" width="120"/><el-table-column prop="quantity" label="库存数量" width="120"/><el-table-column prop="sale_quantity" label="可售库存量" width="120"/><el-table-column prop="amount" label="库存金额" min-width="160"><template #default="scope">{{ formatMoney(scope.row.amount) }}</template></el-table-column></el-table>
<div class="page-toolbar inventory-detail-head"><div><div class="panel-title" style="margin:0">库存货品明细</div><div class="muted">所有入库货品都在这里；上架后才会出现在收银端商品列表。</div></div></div><el-table :data="goods" stripe><el-table-column prop="barcode" label="条码" min-width="140"/><el-table-column prop="name" label="货品名称" min-width="170"/><el-table-column prop="parent_category" label="一级分类" min-width="110"><template #default="scope">{{ scope.row.parent_category || '未设置' }}</template></el-table-column><el-table-column prop="category" label="二级分类" min-width="120"><template #default="scope">{{ scope.row.category || '未设置' }}</template></el-table-column><el-table-column prop="stock" label="库存" width="90"/><el-table-column prop="cost_price" label="成本价" width="110"/><el-table-column label="状态" width="110"><template #default="scope"><el-tag :type="Number(scope.row.status)===1?'success':'info'">{{Number(scope.row.status)===1?'已上架':'库存待上架'}}</el-tag></template></el-table-column><el-table-column label="操作" width="150" fixed="right"><template #default="scope"><el-button link :type="Number(scope.row.status)===1?'warning':'primary'" @click="goodsApi.status(scope.row.goods_id,Number(scope.row.status)===1?0:1).then(load).catch(error=>ElMessage.error(error?.message||'状态更新失败'))">{{Number(scope.row.status)===1?'下架':'上架销售'}}</el-button></template></el-table-column></el-table></section>
<section v-if="tab==='records'" class="panel"><div class="panel-title">采购入库记录</div><el-table :data="records"><el-table-column prop="bill_no" label="单号"/><el-table-column prop="goods_name" label="商品"/><el-table-column prop="qty" label="数量"/><el-table-column prop="cost" label="成本"/><el-table-column label="时间"><template #default="s">{{formatTime(s.row.create_time)}}</template></el-table-column></el-table></section>
<section v-if="tab==='process'" class="panel"><div class="page-toolbar"><div class="muted">加工出库与成品入库共用库存版本校验</div><el-button type="primary" @click="processDialog=true">登记加工业务</el-button></div><el-table :data="[...records.filter(r=>String(r.type).includes('PROCESS')), ...outRecords.filter(r=>String(r.type).includes('PROCESS'))]"><el-table-column prop="bill_no" label="单号"/><el-table-column prop="goods_name" label="商品"/><el-table-column prop="type" label="类型"/><el-table-column prop="qty" label="数量"/><el-table-column label="时间"><template #default="s">{{formatTime(s.row.create_time)}}</template></el-table-column></el-table></section>
<section v-if="tab==='old'" class="panel"><div class="panel-title">旧料 / 板料库存（按类型汇总）</div><el-table ref="oldMaterialTable" :data="old" row-key="material_type" border>
  <el-table-column type="expand"><template #default="scope"><el-table :data="scope.row.details || []" size="small" border><el-table-column prop="material_id" label="入库ID" width="90"/><el-table-column prop="material_type" label="类型" min-width="100"/><el-table-column prop="weight" label="原始克重(g)" min-width="105"/><el-table-column label="成色" width="90"><template #default="detail">{{ (Number(detail.row.purity || 0) * 100).toFixed(2) }}%</template></el-table-column><el-table-column prop="effective_weight" label="有效克重(g)" min-width="105"/><el-table-column label="估值" min-width="105" align="right"><template #default="detail">{{ formatMoney(detail.row.value) }}</template></el-table-column><el-table-column label="来源" min-width="110"><template #default="detail">{{ sourceText(detail.row.source) }}</template></el-table-column><el-table-column label="入库时间" min-width="160"><template #default="detail">{{ formatTime(detail.row.create_time) }}</template></el-table-column><el-table-column label="操作" width="80" fixed="right"><template #default="detail"><el-button link type="primary" @click="openOldMaterial(detail.row)">编辑</el-button></template></el-table-column></el-table></template></el-table-column>
  <el-table-column prop="material_type" label="类型" min-width="140"/><el-table-column label="总克重(g)" prop="total_weight"/><el-table-column label="平均成色"><template #default="scope">{{ (Number(scope.row.average_purity || 0) * 100).toFixed(2) }}%</template></el-table-column><el-table-column label="总价值" align="right"><template #default="scope">{{ formatMoney(scope.row.total_value) }}</template></el-table-column><el-table-column prop="inbound_count" label="入库笔数" width="100"/><el-table-column label="状态" width="90"><template #default="scope"><el-tag :type="Number(oldTypeRow(scope.row.material_type)?.status)===1?'success':'info'">{{Number(oldTypeRow(scope.row.material_type)?.status)===1?'启用':'禁用'}}</el-tag></template></el-table-column><el-table-column label="操作" width="120" fixed="right"><template #default="scope"><el-button link type="primary" @click="editOldMaterialGroup(scope.row)">编辑明细</el-button></template></el-table-column>
</el-table></section>
<section v-if="tab==='warning'" class="panel"><div class="panel-title">库存预警</div><el-table :data="warnings"><el-table-column prop="barcode" label="条码"/><el-table-column prop="name" label="商品"/><el-table-column prop="stock" label="库存"/><el-table-column prop="category" label="分类"/></el-table></section>
<el-dialog v-model="dialog" title="采购入库" width="520px"><el-form label-width="90px"><el-form-item label="商品"><el-select v-model="form.goodsId" filterable><el-option v-for="g in goods" :key="g.goods_id" :value="g.goods_id" :label="goodsLabel(g)"/></el-select></el-form-item><el-form-item label="数量"><el-input-number v-model="form.qty" :min=".001" :precision="3"/></el-form-item><el-form-item label="成本价"><el-input-number v-model="form.cost" :min="0"/></el-form-item><el-form-item label="入库原因"><el-input v-model="form.remark" placeholder="必填，例如采购到货"/></el-form-item></el-form><template #footer><el-button @click="dialog=false">取消</el-button><el-button type="primary" @click="inbound">提交入库</el-button></template></el-dialog>
<el-dialog v-model="manualDialog" :title="manualDirection==='IN'?'手动入库':'手动出库'" width="560px">
  <el-radio-group v-if="manualDirection==='IN'" v-model="manualMode" @change="onManualModeChange" style="margin-bottom:12px">
    <el-radio-button value="existing">已有货品补货</el-radio-button>
    <el-radio-button value="create">新建货品并入库</el-radio-button>
  </el-radio-group>
  <el-form v-if="manualMode==='existing'" label-width="90px">
    <el-form-item label="商品" required><el-select v-model="manualForm.goodsId" filterable><el-option v-for="g in goods" :key="g.goods_id" :value="g.goods_id" :label="goodsLabel(g)"/></el-select><div v-if="selectedManualGoods" class="category-confirm">归属分类：<b>{{ selectedManualGoods.parent_category || '未设置一级分类' }}</b><span> / </span><b>{{ selectedManualGoods.category || '未设置二级分类' }}</b><small>手动入库只增加该货品库存，不会改变分类</small></div></el-form-item>
    <el-form-item label="数量" required><el-input-number v-model="manualForm.qty" :min=".001" :precision="3"/></el-form-item>
    <el-form-item v-if="manualDirection==='IN'" label="成本价"><el-input-number v-model="manualForm.cost" :min="0" :precision="2"/></el-form-item>
    <el-form-item v-else label="出库原因"><el-select v-model="manualForm.reason"><el-option label="损耗" value="损耗"/><el-option label="赠品" value="赠品"/><el-option label="样品" value="样品"/><el-option label="其他" value="其他"/></el-select></el-form-item>
    <el-form-item label="备注" :required="manualDirection==='IN'"><el-input v-model="manualForm.remark" type="textarea" placeholder="说明调整原因"/></el-form-item>
  </el-form>
  <el-form v-else label-width="90px">
    <el-form-item label="条码"><el-input v-model="createForm.barcode" placeholder="可留空，系统自动生成"/></el-form-item>
    <el-form-item label="名称" required><el-input v-model="createForm.name" placeholder="货品名称"/></el-form-item>
    <el-form-item label="一级分类" required><el-select v-model="createForm.parentCategoryId" style="width:100%" @change="createForm.categoryId=null"><el-option v-for="c in createRoots" :key="c.category_id" :value="c.category_id" :label="c.name" :disabled="Number(c.status)!==1"/></el-select></el-form-item>
    <el-form-item label="二级分类" required><el-select v-model="createForm.categoryId" style="width:100%" placeholder="先选一级分类"><el-option v-for="c in createChildren" :key="c.category_id" :value="c.category_id" :label="c.name"/></el-select></el-form-item>
    <el-form-item label="计价方式"><el-radio-group v-model="createForm.priceType"><el-radio :value="1">按克</el-radio><el-radio :value="2">按件</el-radio></el-radio-group></el-form-item>
    <el-form-item v-if="Number(createForm.priceType)===1" label="贵金属类型"><el-select v-model="createForm.goldType" style="width:100%"><el-option v-for="t in goldTypes" :key="t.type_id||t.type_name" :value="t.type_name" :label="t.type_name"/></el-select></el-form-item>
    <el-form-item label="克重(g)"><el-input-number v-model="createForm.weight" :min="0" :precision="3" style="width:100%"/></el-form-item>
    <el-form-item label="成本价" required><el-input-number v-model="createForm.costPrice" :min="0" :precision="2" style="width:100%"/></el-form-item>
    <el-form-item label="售价" required><el-input-number v-model="createForm.salePrice" :min="0" :precision="2" style="width:100%"/></el-form-item>
    <el-form-item label="入库数量" required><el-input-number v-model="createForm.qty" :min="0.001" :precision="3" style="width:100%"/></el-form-item>
    <el-form-item label="备注"><el-input v-model="createForm.remark" type="textarea" placeholder="例如采购到货"/></el-form-item>
  </el-form>
  <div v-if="manualDirection==='IN'" class="manual-photo-field">
    <span class="manual-photo-label">商品照片</span>
    <div class="manual-photo-content">
      <input ref="manualImageInput" class="manual-photo-input" type="file" accept="image/*" multiple @change="selectManualImages"/>
      <div class="manual-photo-actions"><el-button :loading="manualImageUploading" :disabled="manualImages.length>=4" @click="manualImageInput?.click()">选择照片</el-button><span class="muted">最多4张，单张不超过5MB，保存后作为商品图</span></div>
      <div v-if="manualImages.length" class="manual-photo-list"><div v-for="(url,index) in manualImages" :key="url" class="manual-photo-item"><el-image :src="url" :preview-src-list="manualImages" fit="cover" preview-teleported/><el-button link type="danger" @click="removeManualImage(index)">删除</el-button></div></div>
    </div>
  </div>
  <el-alert v-if="manualMode==='create'" type="info" :closable="false" show-icon title="保存后货品建档并计入库存，状态为“库存待上架”，上架后才会出现在收银端商品列表。" />
  <template #footer><el-button @click="manualDialog=false">取消</el-button><el-button v-if="manualMode==='existing'" type="primary" :loading="manualImageUploading" @click="submitManual">提交</el-button><el-button v-else type="primary" :loading="createSaving||manualImageUploading" @click="submitCreateAndInbound">建档并入库</el-button></template>
</el-dialog>
<el-dialog v-model="checkDialog" title="库存盘点" width="420px"><el-form label-width="90px"><el-form-item label="盘点单号"><el-input v-model="checkForm.billNo" placeholder="可留空自动生成"/></el-form-item><el-form-item label="盘点商品"><el-select v-model="checkForm.goodsId" filterable><el-option v-for="g in goods" :key="g.goods_id" :value="g.goods_id" :label="`${g.name}（系统库存 ${g.stock}）`"/></el-select></el-form-item><el-form-item label="实盘数量"><el-input-number v-model="checkForm.actual" :min="0" :precision="3"/></el-form-item></el-form><template #footer><el-button @click="checkDialog=false">取消</el-button><el-button type="primary" @click="submitCheck">提交审批</el-button></template></el-dialog>
<el-dialog v-model="processDialog" title="加工出入库" width="460px"><el-form label-width="90px"><el-form-item label="业务"><el-radio-group v-model="processForm.direction"><el-radio-button label="OUT">加工出库</el-radio-button><el-radio-button label="IN">成品入库</el-radio-button></el-radio-group></el-form-item><el-form-item label="商品"><el-select v-model="processForm.goodsId"><el-option v-for="g in goods" :key="g.goods_id" :value="g.goods_id" :label="g.name"/></el-select></el-form-item><el-form-item label="数量"><el-input-number v-model="processForm.qty" :min=".001" :precision="3"/></el-form-item><el-form-item label="成本价"><el-input-number v-model="processForm.cost" :min="0"/></el-form-item><el-form-item label="版本"><el-input-number v-model="processForm.expectedVersion" :min="0"/></el-form-item></el-form><template #footer><el-button @click="processDialog=false">取消</el-button><el-button type="primary" @click="processMove">提交</el-button></template></el-dialog>
<CategoryManagementDialog v-model="categoryDialog" @changed="onCategoriesChanged" />
<el-dialog v-model="oldMaterialDialog" :title="editingOldMaterialId?'编辑旧料':'新增旧料'" width="480px"><el-form :model="oldMaterialForm" label-width="90px"><el-form-item label="旧料类型" required><div style="display:flex;gap:8px;width:100%"><el-select v-model="oldMaterialForm.materialType" filterable style="flex:1"><el-option v-for="type in oldMaterialTypes" :key="type" :label="type" :value="type"/></el-select><el-button link type="primary" @click="openQuickType('material')">+ 新建类型</el-button></div></el-form-item><el-form-item label="克重" required><el-input-number v-model="oldMaterialForm.weight" :min="0.001" :precision="3" :step="0.1" style="width:100%"/><span class="muted">单位：g</span></el-form-item><el-form-item label="成色" required><el-input-number v-model="oldMaterialForm.purityPercent" :min="0.1" :max="100" :precision="1" :step="0.1" style="width:100%"/><span class="muted">单位：%</span></el-form-item><el-form-item label="估值"><el-input-number v-model="oldMaterialForm.value" :min="0" :precision="2" :step="100" style="width:100%"/></el-form-item><el-alert v-if="oldMaterialForm.weight>0&&oldMaterialForm.purityPercent>0" :closable="false" type="info" show-icon :title="`有效克重：${(Number(oldMaterialForm.weight)*Number(oldMaterialForm.purityPercent)/100).toFixed(3)}g`"/></el-form><template #footer><el-button @click="oldMaterialDialog=false">取消</el-button><el-button type="primary" :loading="savingOldMaterial" @click="saveOldMaterial">{{editingOldMaterialId?'保存修改':'确认入库'}}</el-button></template></el-dialog>
<el-dialog v-model="oldAdjustDialog" :title="oldAdjustDirection==='IN'?'旧料入库':'旧料出库'" width="480px"><el-form label-width="90px"><el-form-item label="旧料类型" required><div style="display:flex;gap:8px;width:100%"><el-select v-model="oldAdjustForm.materialType" filterable style="flex:1"><el-option v-for="type in oldMaterialTypes" :key="type" :label="type" :value="type"/></el-select><el-button link type="primary" @click="openQuickType('adjust')">+ 新建类型</el-button></div></el-form-item><el-form-item label="克重" required><el-input-number v-model="oldAdjustForm.weight" :min=".001" :precision="3" :step=".1"/></el-form-item><el-form-item label="成色" required><el-input-number v-model="oldAdjustForm.purity" :min=".001" :max="1" :precision="4" :step=".001"/></el-form-item><el-form-item v-if="oldAdjustDirection==='IN'" label="来源"><el-select v-model="oldAdjustForm.source"><el-option label="回收" value="回收"/><el-option label="以旧换新" value="以旧换新"/><el-option label="其他" value="其他"/></el-select></el-form-item><el-form-item v-else label="出库原因"><el-select v-model="oldAdjustForm.reason"><el-option label="送厂加工" value="送厂加工"/><el-option label="提纯" value="提纯"/><el-option label="损耗" value="损耗"/><el-option label="其他" value="其他"/></el-select></el-form-item><el-form-item label="备注" required><el-input v-model="oldAdjustForm.remark" type="textarea"/></el-form-item></el-form><template #footer><el-button @click="oldAdjustDialog=false">取消</el-button><el-button type="primary" @click="submitOldAdjust">提交</el-button></template></el-dialog>
<el-dialog v-model="oldTypeDialog" title="旧料类型管理" width="620px" destroy-on-close>
  <div class="page-toolbar"><span class="muted">这里维护的启用类型会同步到收银端旧金、回收和加工入库选择。</span><el-button type="primary" @click="openOldTypeCreate">新增类型</el-button></div>
  <el-table :data="oldMaterialTypeRows" border size="small"><el-table-column type="index" label="#" width="60"/><el-table-column prop="name" label="旧料类型" min-width="200"/><el-table-column label="状态" width="90"><template #default="scope"><el-tag :type="Number(scope.row.status)===1?'success':'info'">{{Number(scope.row.status)===1?'启用':'禁用'}}</el-tag></template></el-table-column><el-table-column label="操作" width="260" fixed="right"><template #default="scope"><el-button link type="primary" @click="openOldTypeEdit(scope.row)">编辑</el-button><el-button link :type="Number(scope.row.status)===1?'warning':'success'" @click="toggleOldType(scope.row)">{{Number(scope.row.status)===1?'禁用':'启用'}}</el-button><el-button link type="danger" @click="removeOldType(scope.row)">删除</el-button></template></el-table-column></el-table>
</el-dialog>
<el-dialog v-model="supplierDialog" title="供应商管理" width="620px" destroy-on-close>
  <div class="page-toolbar"><span class="muted">这里的供应商用于采购入库选择；停用后移动端不再显示，历史入库单不受影响。</span></div>
  <div style="display:flex;gap:8px;margin-bottom:12px"><el-input v-model="supplierForm.name" maxlength="50" placeholder="输入新供应商名称" @keyup.enter="createSupplier"/><el-button type="primary" :loading="supplierSaving" @click="createSupplier">新增</el-button></div>
  <el-table :data="supplierRows" border size="small"><el-table-column type="index" label="#" width="60"/><el-table-column prop="supplier_name" label="供应商" min-width="200"/><el-table-column label="状态" width="90"><template #default="scope"><el-tag :type="Number(scope.row.status)===1?'success':'info'">{{Number(scope.row.status)===1?'启用':'停用'}}</el-tag></template></el-table-column><el-table-column label="操作" width="200" fixed="right"><template #default="scope"><el-button link type="primary" @click="renameSupplier(scope.row)">重命名</el-button><el-button link :type="Number(scope.row.status)===1?'warning':'success'" @click="toggleSupplier(scope.row)">{{Number(scope.row.status)===1?'停用':'启用'}}</el-button></template></el-table-column></el-table>
</el-dialog>
<el-dialog v-model="oldTypeFormDialog" :title="editingOldType?'编辑旧料类型':'新增旧料类型'" width="420px" append-to-body><el-form label-width="90px"><el-form-item label="类型名称" required><el-input v-model="oldTypeForm.name" maxlength="50" @keyup.enter="saveOldType"/></el-form-item></el-form><template #footer><el-button @click="oldTypeFormDialog=false">取消</el-button><el-button type="primary" :loading="savingOldTypes" @click="saveOldType">保存</el-button></template></el-dialog>
<el-dialog v-model="quickTypeDialog" title="新建旧料类型" width="380px" append-to-body><el-form label-width="90px" @submit.prevent="saveQuickType"><el-form-item label="类型名称" required><el-input v-model="quickTypeName" maxlength="50" placeholder="保存后自动选入当前单据" @keyup.enter="saveQuickType"/></el-form-item></el-form><template #footer><el-button @click="quickTypeDialog=false">取消</el-button><el-button type="primary" @click="saveQuickType">保存并选中</el-button></template></el-dialog>
</template>
<style scoped>
.stock-tabs-header { display:flex; align-items:flex-end; gap:16px; }
.stock-tabs-header .el-tabs { flex:1; min-width:0; }
.stock-tab-actions { display:flex; align-items:center; gap:8px; padding-bottom:8px; white-space:nowrap; }
.inventory-detail-head { margin-top: 24px; border-top: 1px solid #ebeef5; padding-top: 18px; }
.category-confirm { margin-top: 8px; padding: 8px 10px; border: 1px solid #dfe7f2; border-radius: 4px; background: #f7faff; color: #52657d; line-height: 1.5; }
.category-confirm b { color: #1f4f82; }
.category-confirm small { display: block; color: #8a98aa; margin-top: 2px; }
.manual-photo-field { display:grid; grid-template-columns:90px 1fr; margin-bottom:18px; }
.manual-photo-label { text-align:right; padding-right:12px; line-height:32px; color:var(--el-text-color-regular); }
.manual-photo-content { min-width:0; }
.manual-photo-input { display:none; }
.manual-photo-actions { display:flex; align-items:center; gap:10px; flex-wrap:wrap; }
.manual-photo-list { display:flex; gap:10px; flex-wrap:wrap; margin-top:10px; }
.manual-photo-item { width:78px; text-align:center; }
.manual-photo-item .el-image { width:72px; height:72px; border:1px solid var(--el-border-color); border-radius:4px; display:block; cursor:zoom-in; }
</style>
