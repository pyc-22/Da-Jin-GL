<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
const props = defineProps({ title:String, columns:Array, load:Function, actions:Array, create:Function, createLabel:{type:String,default:'新增'} })
const rows=ref([]), loading=ref(false), dialog=ref(false), form=ref({}), editing=ref(null)
const visible= computed(()=>props.columns || [])
async function refresh(){loading.value=true;try{const data=await props.load();rows.value=Array.isArray(data)?data:(data?.records||[])}finally{loading.value=false}}
function open(row){editing.value=row||null;form.value={...(row||{})};dialog.value=true}
async function save(){if(!props.create)return;await props.create(form.value,editing.value);dialog.value=false;ElMessage.success('已保存');refresh()}
async function act(a,row){if(a.confirm)await ElMessageBox.confirm(a.confirm);await a.run(row);ElMessage.success(a.success||'操作成功');refresh()}
onMounted(refresh); defineExpose({refresh})
</script>
<template><section class="panel"><div class="page-toolbar"><div class="panel-title" style="margin:0">{{title}}</div><el-button v-if="create" type="primary" @click="open()">{{createLabel}}</el-button></div><el-table :data="rows" v-loading="loading" stripe class="table-wrap"><el-table-column v-for="c in visible" :key="c.key" :prop="c.key" :label="c.label" :min-width="c.width||110"><template #default="scope"><slot :name="c.key" v-bind="scope">{{scope.row[c.key] ?? '-'}}</slot></template></el-table-column><el-table-column v-if="actions?.length" label="操作" width="170" fixed="right"><template #default="scope"><el-button v-for="a in actions" :key="a.label" link type="primary" @click="act(a,scope.row)">{{a.label}}</el-button></template></el-table-column></el-table><el-dialog v-model="dialog" :title="editing?'编辑':'新增'" width="520px"><el-form label-width="100px"><el-form-item v-for="c in visible.filter(x=>x.edit)" :key="c.key" :label="c.label"><el-input v-model="form[c.key]" /></el-form-item></el-form><template #footer><el-button @click="dialog=false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template></el-dialog></section></template>
