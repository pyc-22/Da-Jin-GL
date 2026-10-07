<template>
<section  class="page">
<div  class="order-sales-select"><label>导购（销售）<select v-model="order.salesId"><option :value="null">无导购（散客）</option><option v-for="person in salespeople" :key="person.user_id" :value="person.user_id">{{ person.real_name || person.username }}</option></select><small v-if="!order.salesId">未选择导购，本单不计销售提成</small></label></div>
<Panel v-if="lastSubmitted?.orderNo" title="订单已提交"><p>{{ lastSubmitted.orderNo }}</p><PriceLockBadge :locked="lastSubmitted.hasGramItems" /><p class="muted">请在前台完成审批或收款，结算以订单保存的价格为准。</p></Panel>
<div class="order-form">
<div class="order-member">
<label>会员选择 · 选填<small v-if="!order.memberId" class="muted">散客（未选会员）</small><input v-model="order.memberKeyword" @input="searchOrderMember" placeholder="姓名或手机号搜索会员"/>
<small v-if="order.memberId" class="member-picked">已选：{{ order.memberName }} · {{ order.memberPhone }} <button class="outline" type="button" @click="clearOrderMember">更换</button>
</small>
<div v-else-if="orderMemberHits.length" class="member-hits">
<button v-for="m in orderMemberHits" :key="m.member_id" type="button" @click="pickOrderMember(m)">{{ m.name }} · {{ m.phone }}</button>
</div>
</label>
</div>
<label>商品条码<input v-model="order.barcode" @keyup.enter="scanOrSearch" placeholder="扫码或输入条码"/>
<small class="muted">扫码或输入后按回车，自动加入下方清单</small>
</label>
<div v-for="(item,i) in order.items" :key="i" class="order-item">
<img v-if="item.image" :src="item.image" class="item-thumb" @click="previewGoods(item.image)"/>
<div v-else class="item-thumb empty">无图</div>
<div class="item-info">
<span class="item-name">{{ item.itemName }}</span>
<small class="muted"><template v-if="isGramItem(item)">{{ Number(item.weight || 0).toFixed(3) }} 克 × {{ money(item.unitPrice) }} 元/克</template><template v-else>按件计价 · 单价 ¥{{ money(item.unitPrice) }}</template>{{ item.goldType ? ' · ' + item.goldType : '' }}</small>
<small :class="Number(item.availableStock) > 0 ? 'muted' : 'error'">可售 {{ Number(item.availableStock || 0).toFixed(isGramItem(item) ? 3 : 0) }}{{ isGramItem(item) ? 'g' : '件' }}{{ Number(item.availableStock || 0) <= 0 ? ' · 已被待收款订单占用' : '' }}</small>
</div>
<div class="item-ops">
<template v-if="isGramItem(item)">
<label class="weight-edit">克重(g)<input v-model.number="item.weight" type="number" min="0.001" step="0.001" @input="item.subtotal = Number(item.weight || 0) * Number(item.unitPrice || 0); item.qty = 1"/>
</label>
</template>
<template v-else>
<input v-model.number="item.qty" type="number" min="1" :max="Math.max(1, Number(item.availableStock || 0))" :disabled="Boolean(item.pieceNos?.length)" @change="normalizeOrderQty(item)"/>
</template>
<strong>¥{{ money((Number(item.subtotal || 0) * (isGramItem(item) ? 1 : Number(item.qty || 1)))) }}</strong>
<button class="del" type="button" @click="order.items.splice(i,1)">删除</button>
</div>
</div>
<button class="outline full" @click="scan">扫码添加商品</button>
<label>工费<input v-model.number="order.laborFee" type="number" min="0"/>
</label>
<label>折扣（填小数，0.85＝85折）<input v-model.number="order.discount" type="number" min="0.01" max="1" step="0.01" placeholder="0.85"/>
<small v-if="order.discount>1 || order.discount<=0" class="error">折扣应填 0.01~1 之间的小数，例如 0.85 表示 85折</small>
<small v-else-if="order.discount < threshold" class="error">低于{{ threshold*10 }}折，将提交审批</small>
</label>
<div class="old-mobile">
<div class="section-label">旧金抵扣</div>
<div v-for="(m,i) in order.oldMetals" :key="m.id" class="old-line">
<span>{{m.materialType}} · {{m.weight}}g · {{(Number(m.purity||0)*100).toFixed(1)}}%</span>
<b>¥{{money(oldGoldDeduction(m.weight, m.purity, m.price||recyclePrice||0))}}</b>
<button @click="order.oldMetals.splice(i,1)">删除</button>
</div>
<div class="old-add">
<select v-model="oldMetal.materialType" aria-label="旧料类型">
<option value="" disabled>选择旧料类型</option>
<option v-for="type in oldMaterialTypes" :key="type.type_id || type.name" :value="type.name">{{ type.name }}</option>
</select>
<input v-model.number="oldMetal.weight" type="number" min="0" step="0.001" placeholder="克重"/>
<input v-model.number="oldMetal.purity" type="number" min="0" max="1" step="0.001" placeholder="成色"/>
<button class="outline" :disabled="!recyclePrice" @click="addOldMetal">添加旧金</button>
</div>
<small v-if="oldMaterialTypeError" class="error">{{ oldMaterialTypeError }}</small>
<small v-if="!recyclePrice" class="error">回收金价不可用，暂不能添加旧金抵扣</small>
<small>抵扣合计 ¥{{money(oldDeduct)}}</small>
</div>
</div>
<p class="lock-notice">提交即锁定当前金价与加价；之后行情变动不影响本单（含未结算单），退款/结算/提成均按锁定价计算。无导购订单不计销售提成。</p>
<Panel title="结算预览"><div class="summary-line"><span>商品合计</span><b>¥{{ money(order.items.reduce((sum,item)=>sum+Number(item.subtotal||0)*(isGramItem(item)?1:Number(item.qty||1)),0)) }}</b></div><div class="summary-line"><span>折扣</span><b>{{ order.discount }}（{{ Number(order.discount)*10 }}折）</b></div><div class="summary-line"><span>旧金抵扣</span><b class="ok">− ¥{{ money(oldDeduct) }}</b></div><div class="summary-line"><span>应收</span><b class="money">¥{{ money(orderTotal) }}</b></div><p class="muted">实收金额由前台完成收款后记录。</p></Panel>
<div class="checkout-bar">
<div class="sum">
<span>应收 · 含工费−旧金抵扣</span>
<b>¥{{ money(orderTotal) }}</b>
</div>
<button class="primary" :disabled="app.offline || !order.items.length" @click="startCheckout">转交前台结算</button>
</div>
<div class="bar-spacer">
</div>
</section>
</template>
<script setup>
import PriceLockBadge from '../../components/PriceLockBadge.vue'
import Panel from '../../components/Panel.vue'
import { inject } from 'vue'
import { roleHomeKey } from './context.js'
import { finenessFactor } from '../../utils/goldPricing.js'
const { lastSubmitted, salespeople, app, section, threshold, oldMaterialTypes, oldMaterialTypeError, oldMetal, order, orderMemberHits, recyclePrice, isGramItem, oldDeduct, orderTotal, money, scan, previewGoods, scanOrSearch, normalizeOrderQty, addOldMetal, searchOrderMember, pickOrderMember, clearOrderMember, startCheckout } = inject(roleHomeKey)
</script>
