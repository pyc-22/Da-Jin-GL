// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe,it,expect } from 'vitest'
import MarketQuoteBar from '../components/MarketQuoteBar.vue'
describe('mobile final price display',()=>{
 it('uses explicit sale and recycle fields without falling back to price',()=>{
  const wrapper=mount(MarketQuoteBar,{props:{gold:{salePrice:810,recyclePrice:750,price:999},silver:{salePrice:12,recyclePrice:9,price:99}}})
  expect(wrapper.text()).toContain('足金卖价¥810.00')
  expect(wrapper.text()).toContain('足金回收价¥750.00')
  expect(wrapper.text()).toContain('银卖价¥12.00')
  expect(wrapper.text()).toContain('银回收价¥9.00')
  expect(wrapper.text()).not.toContain('99.00')
  const empty=mount(MarketQuoteBar,{props:{gold:{price:999}}})
  expect(empty.text()).toContain('—')
  expect(empty.text()).not.toContain('999')
 })
 it('keeps the gold settings guard unchanged',()=>{
  const main=readFileSync(resolve(process.cwd(),'src/main.js'),'utf8')
  expect(main).toContain("meta: { auth: true, roles: ['ADMIN'], permission: 'gold:manage' }")
 })
})
