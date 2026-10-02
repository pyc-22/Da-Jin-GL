<template>
<section  class="page">
<PageTitle title="货品管理">
<button class="outline" @click="router.push('/goods/search')">搜索货品</button>
</PageTitle>
<Panel :title="goodsTabTitle">
<div class="chips">
<button v-for="t in goodsTabs" :key="t.key" :class="{active: goodsTab===t.key}" @click="goodsTab=t.key">{{ t.label }}</button>
</div>
<template v-if="goodsTab==='overview'">
<div class="kpi-grid">
<Kpi label="库存总金额" :value="`¥${money(stockRoots.reduce((s,r)=>s+Number(r.amount||0),0))}`"/>
<Kpi label="库存总量" :value="stockRoots.map(categoryStockText).join(' · ') || '0件'"/>
</div>
<div class="chips">
<button :class="{active: wallCat===null}" @click="pickWallCat(null)">全部</button>
<button v-for="root in stockRoots" :key="root.category_id" :class="{active: wallCat===root.category_id}" @click="pickWallCat(root.category_id)">{{ root.category }} {{ categoryStockText(root) }}</button>
</div>
<div v-if="wallLoading" class="empty">加载中…</div>
<div v-else-if="!wallGoods.length" class="empty">暂无在库货品</div>
<div v-for="g in wallGoods" :key="g.goods_id" class="goods-card">
<img v-if="goodsImage(g.piece_image || g.images)" :src="goodsImage(g.piece_image || g.images)" class="item-thumb big" @click="previewGoods(goodsImage(g.piece_image || g.images))"/>
<div v-else class="item-thumb big empty">无图</div>
<div class="item-info">
<span class="item-name">{{ g.name }}</span>
<small class="muted">{{ g.barcode || '无条码' }}{{ g.weight ? ' · ' + Number(g.weight).toFixed(2) + 'g' : '' }}{{ g.gold_type ? ' · ' + g.gold_type : '' }}{{ Number(g.status)===1 ? '' : ' · 未上架' }}</small>
<small class="muted">{{ g.parent_category ? g.parent_category + ' / ' + (g.category||'') : (g.category||'') }}</small>
</div>
<div class="item-ops">
<strong>库存 {{ goodsStockText(g) }}</strong>
<small>¥{{ money(g.sale_price) }}</small>
</div>
</div>
</template>
<template v-else-if="goodsTab==='warning'">
<div v-for="w in warnings" :key="'gw'+w.goods_id" class="goods-card">
<img v-if="goodsImage(w.piece_image || w.images)" :src="goodsImage(w.piece_image || w.images)" class="item-thumb big" @click="previewGoods(goodsImage(w.piece_image || w.images))"/>
<div v-else class="item-thumb big empty">无图</div>
<div class="item-info">
<span class="item-name">{{ w.name || w.goods_name }}</span>
<small class="muted">{{ w.barcode || '无条码' }}{{ w.weight ? ' · ' + Number(w.weight).toFixed(2) + 'g' : '' }}{{ w.gold_type ? ' · ' + w.gold_type : '' }}</small>
<small class="muted">{{ w.category || '' }}</small>
</div>
<div class="item-ops">
<strong class="error">剩余 {{ goodsStockText(w) }}</strong>
<small>¥{{ money(w.sale_price) }}</small>
</div>
</div>
<div v-if="!warnings.length" class="empty">库存充足</div>
</template>
<template v-else-if="goodsTab==='old'">
<div v-for="m in oldMaterials.slice(0,8)" :key="'om'+m.material_type" class="rank-row">
<span>{{ m.material_type }}</span>
<strong>{{ m.total_weight||0 }}g</strong>
<small>¥{{ money(m.total_value) }} · {{ m.inbound_count||0 }}笔</small>
</div>
<div v-if="!oldMaterials.length" class="empty">暂无旧料</div>
</template>
<template v-else>
<div v-for="c in stockChecks.slice(0,6)" :key="'ck'+c.check_id" class="rank-row">
<span>{{ c.bill_no }}</span>
<b :class="c.status===3?'ok':(c.status===4?'error':'')">{{ c.status===3?'已通过':(c.status===4?'已驳回':'待处理') }}</b>
<small>{{ (c.create_time||'').slice(0,10) }}</small>
</div>
<div v-if="!stockChecks.length" class="empty">暂无盘点记录</div>
</template>
</Panel>
</section>
</template>
<script setup>
import { inject } from 'vue'
import { roleHomeKey } from './context.js'
import Kpi from '../../components/Kpi.vue'
import Panel from '../../components/Panel.vue'
import PageTitle from '../../components/PageTitle.vue'
const { router, section, warnings, stockRoots, oldMaterials, stockChecks, goodsTab, goodsTabs, goodsTabTitle, categoryStockText, goodsStockText, money, goodsImage, previewGoods, wallCat, wallGoods, wallLoading, pickWallCat } = inject(roleHomeKey)
</script>
