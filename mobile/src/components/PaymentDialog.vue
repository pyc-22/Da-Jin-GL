<template>
  <div v-if="show" class="mobile-modal">
    <div class="mobile-modal-card">
      <template v-if="!done">
        <h3>收银结算</h3>
        <p class="muted">订单 {{ orderNo }} · 应收 ¥{{ money(expected) }}</p>
        <div class="pay-grid">
          <button v-for="method in methods" :key="method.code" :class="{active: lines.some(line => line.code === method.code)}" @click="$emit('toggle-method', method)">
            {{ method.label }}
          </button>
        </div>
        <div v-for="line in lines" :key="line.code" class="old-line">
          <span>{{ line.label }}</span>
          <input v-model.number="line.amount" type="number" min="0" step="0.01" />
        </div>
        <div v-if="needPassword" class="pay-password">
          <label>储值支付密码<input :value="password" type="password" inputmode="numeric" maxlength="6" placeholder="请输入6位数字支付密码" @input="$emit('update:password', $event.target.value)" /></label>
        </div>
        <p class="muted">
          已选合计 ¥{{ money(sum) }}
          <template v-if="remainCents > 0"> · 还差 ¥{{ money(remain) }}</template>
          <template v-else-if="remainCents < 0"> · 超出 ¥{{ money(remain) }}</template>
          <template v-else> · 金额核对通过</template>
        </p>
        <p v-if="error" class="error">{{ error }}</p>
        <div class="action-row">
          <button class="outline" @click="$emit('close')">取消</button>
          <button class="primary" :disabled="!canPay || paying" @click="$emit('confirm')">{{ paying ? '结算中...' : '确认收款' }}</button>
        </div>
      </template>
      <template v-else>
        <h3>结算成功</h3>
        <p class="muted">订单 {{ orderNo }} 已完成收款</p>
        <div v-for="line in lines" :key="line.code" class="rank-row">
          <span>{{ line.label }}</span>
          <strong>¥{{ money(line.amount) }}</strong>
        </div>
        <button class="primary full" @click="$emit('finish')">完成</button>
      </template>
    </div>
  </div>
</template>

<script setup>
defineProps({
  show: Boolean,
  done: Boolean,
  orderNo: [String, Number],
  orderId: [String, Number],
  expected: { type: Number, default: 0 },
  methods: { type: Array, default: () => [] },
  lines: { type: Array, default: () => [] },
  needPassword: Boolean,
  password: { type: String, default: '' },
  sum: { type: Number, default: 0 },
  remainCents: { type: Number, default: 0 },
  remain: { type: Number, default: 0 },
  canPay: Boolean,
  paying: Boolean,
  error: { type: String, default: '' },
  money: { type: Function, required: true }
})

defineEmits(['close', 'confirm', 'finish', 'toggle-method', 'update:password'])
</script>
