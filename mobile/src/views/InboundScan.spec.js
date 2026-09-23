import { getStorage, setStorage } from '../utils/storage.js'
// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import InboundScan from './InboundScan.vue'

const mocks = vi.hoisted(() => ({
  goodsByBarcode: vi.fn(),
  goodsCategories: vi.fn(),
  createInbound: vi.fn(),
  replace: vi.fn(),
  uploadImage: vi.fn()
}))

vi.mock('../api/request.js', () => ({
  api: {
    goodsByBarcode: mocks.goodsByBarcode,
    goodsCategories: mocks.goodsCategories,
    createInbound: mocks.createInbound
  },
  http: { defaults: { baseURL: 'http://localhost:8080' } }
}))
vi.mock('../api/upload.js', () => ({ uploadImage: mocks.uploadImage }))
vi.mock('../stores/auth.js', () => ({ useAuthStore: () => ({ user: { real_name: '测试员' } }) }))
vi.mock('../stores/app.js', () => ({ useAppStore: () => ({ offline: false, primaryGold: { price_type: '足金', price: 600 } }) }))
vi.mock('vue-router', () => ({ useRouter: () => ({ replace: mocks.replace, push: vi.fn(), back: vi.fn() }) }))

function seedDraft(items = []) {
  setStorage('dajin-inbound-meta', JSON.stringify({ inboundType: 'profit', storeId: 1, storeName: '一店' }))
  setStorage('dajin-inbound-draft', JSON.stringify(items))
}

function mountPage() {
  return mount(InboundScan, { global: { directives: { permission: () => {} } } })
}

async function enterBarcode(wrapper, barcode) {
  await wrapper.findAll('button').find(button => button.text().includes('手动输入条码')).trigger('click')
  await wrapper.get('.barcode-input').setValue(barcode)
  await wrapper.findAll('button').find(button => button.text().includes('查询货品')).trigger('click')
  await flushPromises()
}

describe('InboundScan', () => {
  beforeEach(() => {
    localStorage.clear(); localStorage.setItem('dajin-user', JSON.stringify({store_id:1,user_id:7}))
    delete globalThis.uni
    mocks.replace.mockReset()
    mocks.goodsByBarcode.mockReset()
    mocks.goodsCategories.mockReset().mockResolvedValue([{ category_id: 2, name: '戒指', level: 2, status: 1 }])
    mocks.createInbound.mockReset()
    mocks.uploadImage.mockReset()
    vi.spyOn(window, 'confirm').mockReturnValue(true)
  })

  afterEach(() => {
    delete globalThis.uni
    vi.restoreAllMocks()
  })

  it('queries a manually entered barcode and does not add a duplicate twice', async () => {
    seedDraft()
    mocks.goodsByBarcode.mockResolvedValue({
      goods_id: 11, barcode: 'CODE-001', name: '足金戒指', category_id: 2,
      category: '戒指', weight: 5.123, sale_price: 3888, certificate_no: 'CERT-1', images: '["/api/file/ring.jpg"]'
    })
    const wrapper = mountPage()
    await flushPromises()

    await enterBarcode(wrapper, 'CODE-001')
    expect(wrapper.text()).toContain('足金戒指')
    expect(wrapper.text()).toContain('5.123g')
    expect(wrapper.text()).toContain('¥3,888.00')

    await wrapper.get('.topbar .back').trigger('click')
    await enterBarcode(wrapper, 'CODE-001')
    expect(mocks.goodsByBarcode).toHaveBeenCalledTimes(1)
    expect(wrapper.get('.inbound-summary').text()).toContain('总件数 1')
  })

  it('opens manual material entry on no match and blocks a missing cost price', async () => {
    seedDraft()
    mocks.goodsByBarcode.mockRejectedValue(new Error('商品不存在'))
    const wrapper = mountPage()
    await flushPromises()

    await enterBarcode(wrapper, 'UNKNOWN-001')
    expect(wrapper.text()).toContain('手动物料录入')
    const form = wrapper.get('.mobile-modal-card')
    const inputs = form.findAll('input')
    await inputs[0].setValue('新到戒指')
    await form.get('select').setValue('2')
    await inputs[2].setValue('6.8')
    await inputs[4].setValue('5200')
    await form.findAll('button').find(button => button.text().includes('加入清单')).trigger('click')
    expect(form.text()).toContain('请输入有效成本价')

    await inputs[3].setValue('3600')
    await form.findAll('button').find(button => button.text().includes('加入清单')).trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('新到戒指')
  })

  it('keeps the photo action on repeated visits and caps the slots at four', async () => {
    seedDraft([{ key: 'goods-1', goodsId: 1, barcode: 'PHOTO-1', name: '拍照货品', categoryName: '戒指', goldWeight: 1, labelPrice: 100, costPrice: 50, quantity: 6, pieces: [] }])

    for (let visit = 0; visit < 3; visit++) {
      const wrapper = mountPage()
      await flushPromises()
      await wrapper.findAll('button').find(button => button.text().includes('按件拍照')).trigger('click')
      expect(wrapper.findAll('.ph-add')).toHaveLength(4)
      expect(wrapper.text()).toContain('实物照片最多4张')
      wrapper.unmount()
    }
  })

  it('shows the 2MB validation message instead of caching an oversized photo as offline data', async () => {
    seedDraft([{ key: 'goods-1', goodsId: 1, barcode: 'PHOTO-2', name: '超大照片货品', categoryName: '戒指', goldWeight: 1, labelPrice: 100, costPrice: 50, quantity: 1, pieces: [] }])
    const saveFile = vi.fn()
    globalThis.uni = {
      chooseImage: ({ success }) => success({ tempFilePaths: ['/tmp/huge.jpg'] }),
      getImageInfo: ({ success }) => success({ width: 4000, height: 3000 }),
      compressImage: ({ success }) => success({ tempFilePath: '/tmp/huge-compressed.jpg' }),
      getFileInfo: ({ success }) => success({ size: 3 * 1024 * 1024 }),
      saveFile,
      vibrateShort: vi.fn()
    }
    const wrapper = mountPage()
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text().includes('按件拍照')).trigger('click')
    await wrapper.get('.ph-add').trigger('click')
    await new Promise(resolve => setTimeout(resolve, 0))
    await flushPromises()

    expect(wrapper.text()).toContain('照片压缩后仍超过2MB，请重拍')
    expect(saveFile).not.toHaveBeenCalled()
  })
})
