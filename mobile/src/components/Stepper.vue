<template>
  <div class="stepper">
    <button type="button" :aria-label="'减少' + label" :disabled="disabled || Number(modelValue) <= min" @click="move(-1)">−</button>
    <input type="number" inputmode="decimal" :aria-label="label" :value="modelValue" :min="min" :max="max" :step="step" :disabled="disabled" @input="input" @change="normalize" />
    <button type="button" :aria-label="'增加' + label" :disabled="disabled || Number(modelValue) >= max" @click="move(1)">＋</button>
  </div>
</template>
<script setup>
const props = defineProps({ modelValue: [Number, String], label: { type: String, required: true }, min: { type: Number, default: -Infinity }, max: { type: Number, default: Infinity }, step: { type: Number, default: 1 }, disabled: Boolean })
const emit = defineEmits(['update:modelValue', 'change'])
function bounded(value) { return Math.min(props.max, Math.max(props.min, value)) }
function move(direction) {
  const value = bounded(Number((Number(props.modelValue || 0) + direction * props.step).toFixed(8)))
  emit('update:modelValue', value); emit('change', value)
}
function input(event) { emit('update:modelValue', event.target.value === '' ? '' : Number(event.target.value)) }
function normalize(event) {
  if (event.target.value === '') return
  const value = bounded(Number(event.target.value))
  event.target.value = value
  emit('update:modelValue', value); emit('change', value)
}
</script>
