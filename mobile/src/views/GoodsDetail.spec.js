// @vitest-environment jsdom
import { expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
vi.mock('vue-router', () => ({ useRouter: () => ({ back: vi.fn() }), useRoute: () => ({ query: { goods: JSON.stringify({ goods_id: 1, name: '照片样本', images: ['/api/file/goods/test.jpg'] }) } }) }))
vi.mock('../api/request.js', () => ({ api: {}, http: { defaults: { baseURL: 'https://admin.example.com' } } }))
vi.mock('../stores/app.js', () => ({ useAppStore: () => ({ primaryGold: { price: 700 }, loadGold: vi.fn() }) }))
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
