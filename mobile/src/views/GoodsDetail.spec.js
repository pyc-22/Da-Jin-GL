// @vitest-environment jsdom
import { beforeEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
const fixture = vi.hoisted(() => ({ quote: null, goods: null, push: vi.fn(), toast: vi.fn(), auth: { role: 'SALES', can: () => true } }))
beforeEach(() => {
  fixture.push.mockReset()
  fixture.toast.mockReset()
  fixture.quote = { salePrice: 700, recyclePrice: 600, price: 999 }
  fixture.goods = { goods_id: 1, name: '照片样本', price_type: 1, weight: 2, images: ['/api/file/goods/test.jpg'] }
})
vi.mock('vue-router', () => ({ useRouter: () => ({ back: vi.fn(), push: fixture.push }), useRoute: () => ({ params: { id: String(fixture.goods.goods_id) }, query: {} }) }))
vi.mock('../api/request.js', () => ({ api: { goodsById: vi.fn(() => Promise.resolve(fixture.goods)) }, http: { defaults: { baseURL: 'https://admin.example.com' } } }))
vi.mock('../stores/app.js', () => ({ useAppStore: () => ({ primaryGold: fixture.quote, loadGold: vi.fn() }) }))
vi.mock('../stores/auth.js', () => ({ useAuthStore: () => fixture.auth }))
vi.mock('../composables/useToast.js', () => ({ useToast: () => ({ toast: fixture.toast }) }))
import GoodsDetail from './GoodsDetail.vue'
it('resolves detail photos against the API server rather than the APK local origin', async () => {
  const wrapper = mount(GoodsDetail)
  await flushPromises()
  expect(wrapper.get('.photo-item img').attributes('src')).toBe('https://admin.example.com/api/file/goods/test.jpg')
  const open = vi.spyOn(window, 'open').mockImplementation(() => {})
  await wrapper.get('.photo-item img').trigger('click')
  expect(open).toHaveBeenCalledWith('https://admin.example.com/api/file/goods/test.jpg', '_blank')
  open.mockRestore(); wrapper.unmount()
})

it('shows the final sale price rather than recycle or legacy quote values', async () => {
  const wrapper = mount(GoodsDetail)
  await flushPromises()
  expect(wrapper.get('.detail-price').text()).toContain('¥1,400.00')
  wrapper.unmount()
})
it('shows a pending quote instead of an invented amount when the sale price is missing', async () => {
  fixture.quote = { recyclePrice: 600, price: 999 }
  const wrapper = mount(GoodsDetail)
  await flushPromises()
  expect(wrapper.get('.detail-price').text()).toContain('待同步金价')
  expect(wrapper.get('.detail-price').text()).not.toContain('1,224.00')
  wrapper.unmount()
})
it('asks for in-store weighing when a gram-priced product has no archived weight', async () => {
  fixture.goods = { goods_id: 2, name: '按克手镯', price_type: 1, available_stock: 5, images: [] }
  const wrapper = mount(GoodsDetail)
  await flushPromises()
  expect(wrapper.get('.detail-price').text()).toContain('到店称重计价')
  expect(wrapper.get('.detail-price').text()).toContain('按克称重')
  wrapper.unmount()
})

it('carries a validated gram weight into the normal sales order form', async () => {
  fixture.goods = { goods_id: 9, name: '足金手镯', status: 1, price_type: 1, available_stock: 5, images: [] }
  const wrapper = mount(GoodsDetail)
  await flushPromises()
  await wrapper.get('.order-weight input').setValue('2.350')
  await wrapper.get('.direct-order-btn').trigger('click')
  expect(fixture.push).toHaveBeenCalledWith({ path: '/sales/order', query: { goodsId: '9', gramWeight: '2.350' } })
  wrapper.unmount()
})

it('prevents direct order when the requested weight exceeds available stock', async () => {
  fixture.goods = { goods_id: 10, name: '库存金料', status: 1, price_type: 1, available_stock: 1, images: [] }
  const wrapper = mount(GoodsDetail)
  await flushPromises()
  await wrapper.get('.order-weight input').setValue('1.200')
  await wrapper.get('.direct-order-btn').trigger('click')
  expect(fixture.push).not.toHaveBeenCalled()
  expect(fixture.toast).toHaveBeenCalledWith('输入克重超过当前可售库存')
  wrapper.unmount()
})
