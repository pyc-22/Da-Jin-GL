<template><button class="primary scan-code-button" :disabled="disabled" @click="scan"><span>▣</span>{{ label }}</button></template>
<script setup>
import { defineEmits, defineProps } from 'vue'
import { isNativeApp, scanNativeBarcode } from '../utils/nativeDevice.js'
const props = defineProps({ disabled: Boolean, label: { type: String, default: '扫码添加' } })
const emit = defineEmits(['scan', 'scan-error', 'scan-request'])
async function scan() {
  if (props.disabled) return
  if (isNativeApp()) {
    try { emit('scan', await scanNativeBarcode()) } catch (error) { emit('scan-error', error) }
    return
  }
  if (typeof uni !== 'undefined' && uni.scanCode) uni.scanCode({ scanType: ['barCode', 'qrCode'], success: r => emit('scan', r.result), fail: e => emit('scan-error', e) })
  else emit('scan-request')
}
defineExpose({ scan })
</script>
