// @vitest-environment jsdom
import { beforeEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
const fixture = vi.hoisted(() => ({ quote: null }))
beforeEach(() => { fixture.quote = { salePrice: 700, recyclePrice: 600, price: 999 } })
vi.mock('vue-router', () => ({ useRouter: () => ({ back: vi.fn() }), useRoute: () => ({ query: { goods: JSON.stringify({ goods_id: 1, name: '照片样本', price_type: 1, weight: 2, images: ['/api/file/goods/test.jpg'] }) } }) }))
vi.mock('../api/request.js', () => ({ api: {}, http: { defaults: { baseURL: 'https://admin.example.com' } } }))
vi.mock('../stores/app.js', () => ({ useAppStore: () => ({ primaryGold: fixture.quote, loadGold: vi.fn() }) }))
vi.mock('../stores/auth.js', () => ({ useAuthStore: () => ({}) }))
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
