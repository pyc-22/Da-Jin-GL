import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const home = readFileSync(resolve(process.cwd(), 'src/views/RoleHome.vue'), 'utf8')
const store = readFileSync(resolve(process.cwd(), 'src/stores/app.js'), 'utf8')
const main = readFileSync(resolve(process.cwd(), 'src/main.js'), 'utf8')

describe('mobile silver prices', () => {
  it('shows independent sale and recycle prices while gating pricing details to admins', () => {
    expect(store).toContain('silverSale:')
    expect(store).toContain('silverRecycle:')
    expect(store).toContain("=== 'SILVER_RECYCLE'")
    expect(home).toContain('money(salePrice(app.silverSale))')
    expect(home).toContain('money(recyclePriceFor(app.silverRecycle))')
    expect(home).toContain('银卖价')
    expect(home).toContain('银回收价')
    expect(home).toContain('足金 ¥{{ money(salePrice(app.primaryGold)) }}/g<template v-if="isAdmin">')
    expect(home).toContain('<small class="market-config">{{ pricingLabel(app.silverSale) }}</small>')
    expect(home).toContain("const managerFunctions = computed(() => isAdmin.value ? managerFunctionCatalog : managerFunctionCatalog.filter(item => item.key !== 'gold-settings'))")
    expect(home).toContain("const salesFunctions = computed(() => salesFunctionCatalog.filter(item => item.key !== 'gold-settings'))")
    expect(main).toContain("meta: { auth: true, roles: ['ADMIN'], permission: 'gold:manage' }")
  })
})
