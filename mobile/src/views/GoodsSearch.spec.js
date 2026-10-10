// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({ push: vi.fn(), goods: vi.fn(), scanGoods: vi.fn(), gold: [] }))
vi.mock('vue-router', () => ({ useRouter: () => ({ back: vi.fn(), push: mocks.push }) }))
vi.mock('../api/request.js', () => ({ api: { goods: mocks.goods, scanGoods: mocks.scanGoods }, http: { defaults: { baseURL: '' } } }))
vi.mock('../utils/storage.js', () => ({ getStorage: () => '[]', setStorage: vi.fn() }))
vi.mock('../stores/app.js', () => ({ useAppStore: () => ({ gold: mocks.gold, primaryGold: { salePrice: 900 } }) }))
vi.mock('../utils/nativeDevice.js', () => ({ isNativeApp: () => false, scanNativeBarcode: vi.fn() }))

import GoodsSearch from './GoodsSearch.vue'

describe('mobile goods search', () => {
  beforeEach(() => {
    mocks.push.mockReset()
    mocks.goods.mockReset().mockResolvedValue({ records: [
      { goods_id: 9, name: '足金戒指', category: '戒指', barcode: 'G9', price_type: 2, sale_price: 1200, stock: 2 }
    ] })
    mocks.scanGoods.mockReset()
    mocks.gold = []
  })

  it('renders search results as a responsive card grid and opens the selected item detail', async () => {
    const wrapper = mount(GoodsSearch)
    await wrapper.get('input').setValue('足金戒指')
    await wrapper.findAll('button').find(button => button.text() === '搜索').trigger('click')
    await flushPromises()

    expect(wrapper.get('.goods-grid').classes()).toContain('goods-grid')
    expect(wrapper.get('.goods-search-card').text()).toContain('足金戒指')
    await wrapper.get('.goods-search-card').trigger('click')
    expect(mocks.push).toHaveBeenCalledWith('/goods/9')
    wrapper.unmount()
  })
})
