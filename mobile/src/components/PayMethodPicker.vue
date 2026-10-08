<script setup>
// 收款/返款方式选择：门店渠道 code 各店不同，必须用门店自己的渠道列表，不能写死代码
defineProps({
  open: { type: Boolean, default: false },
  title: { type: String, default: '选择收款方式' },
  methods: { type: Array, default: () => [] },
  modelValue: { type: String, default: '' },
  hint: { type: String, default: '' }
})
const emit = defineEmits(['update:open', 'pick'])
</script>

<template>
  <div v-if="open" class="picker-mask" @click.self="emit('update:open', false)">
    <div class="picker-sheet" role="dialog" :aria-label="title">
      <header><b>{{ title }}</b><button type="button" @click="emit('update:open', false)">关闭</button></header>
      <button v-for="method in methods" :key="method.code" type="button" :class="{ active: method.code === modelValue }" @click="emit('pick', method.code)">
        <span>{{ method.label || method.code }}</span><i v-if="method.code === modelValue">✓</i>
      </button>
      <p v-if="!methods.length" class="picker-empty">没有可用的收款方式，请先在管理端「支付方式」里启用</p>
      <p v-else-if="hint" class="picker-empty">{{ hint }}</p>
    </div>
  </div>
</template>
