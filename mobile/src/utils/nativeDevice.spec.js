// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

const mocks = vi.hoisted(() => ({ native: vi.fn(), scan: vi.fn(), photo: vi.fn(), listener: vi.fn(), minimize: vi.fn() }))
vi.mock('@capacitor/core', () => ({ Capacitor: { isNativePlatform: mocks.native }, registerPlugin: () => ({ scan: mocks.scan }) }))
vi.mock('@capacitor/camera', () => ({ Camera: { getPhoto: mocks.photo }, CameraResultType: { Uri: 'uri' }, CameraSource: { Camera: 'CAMERA' } }))
vi.mock('@capacitor/app', () => ({ App: { addListener: mocks.listener, minimizeApp: mocks.minimize } }))

import { takeNativePhoto, setupNativeNavigation } from './nativeDevice.js'
import { scanBarcodeFromCamera } from './browserBarcode.js'
import ScanCodeButton from '../components/ScanCodeButton.vue'

describe('Android device entry points', () => {
  beforeEach(() => { vi.clearAllMocks(); vi.unstubAllGlobals(); mocks.native.mockReturnValue(true) })

  it('order scan uses native continuous-camera recognition without opening a file picker', async () => {
    mocks.scan.mockResolvedValue({ text: ' PIECE-999-001 ' })
    const createElement = vi.fn()
    await expect(scanBarcodeFromCamera({ documentRef: { createElement } })).resolves.toBe('PIECE-999-001')
    expect(createElement).not.toHaveBeenCalled()
  })

  it('inbound and inventory button can scan successive items and never request manual input', async () => {
    mocks.scan.mockResolvedValueOnce({ text: 'ITEM-1' }).mockResolvedValueOnce({ text: 'ITEM-2' })
    const wrapper = mount(ScanCodeButton)
    await wrapper.trigger('click'); await flushPromises()
    await wrapper.trigger('click'); await flushPromises()
    expect(wrapper.emitted('scan')).toEqual([['ITEM-1'], ['ITEM-2']])
    expect(wrapper.emitted('scan-request')).toBeUndefined()
    wrapper.unmount()
  })

  it('cancel and denied permission do not return a fake barcode or open manual input', async () => {
    const wrapper = mount(ScanCodeButton)
    mocks.scan.mockRejectedValueOnce(new Error('已取消扫码')).mockRejectedValueOnce(new Error('请允许相机权限'))
    await wrapper.trigger('click'); await flushPromises()
    await wrapper.trigger('click'); await flushPromises()
    expect(wrapper.emitted('scan')).toBeUndefined()
    expect(wrapper.emitted('scan-error')).toHaveLength(2)
    expect(wrapper.emitted('scan-request')).toBeUndefined()
    wrapper.unmount()
  })

  it('converts each camera result into a new uploadable image, including the second photo', async () => {
    mocks.photo.mockResolvedValueOnce({ webPath: 'https://local/photo1', format: 'jpeg' }).mockResolvedValueOnce({ webPath: 'https://local/photo2', format: 'jpeg' })
    const fetchPhoto = vi.fn().mockResolvedValue({ ok: true, blob: async () => new Blob(['photo'], { type: 'image/jpeg' }) })
    vi.stubGlobal('fetch', fetchPhoto)
    const first = await takeNativePhoto(), second = await takeNativePhoto()
    expect(first).toBeInstanceOf(File)
    expect(second).toBeInstanceOf(File)
    expect(first).not.toBe(second)
    expect(fetchPhoto.mock.calls.map(args => args[0])).toEqual(['https://local/photo1', 'https://local/photo2'])
    expect(mocks.photo).toHaveBeenCalledWith(expect.objectContaining({ source: 'CAMERA', saveToGallery: false }))
  })

  it('Android back returns within the app and minimizes at the first page', () => {
    const router = { back: vi.fn() }
    setupNativeNavigation(router)
    const handler = mocks.listener.mock.calls[0][1]
    window.history.replaceState({ back: '/sales' }, '')
    handler(); expect(router.back).toHaveBeenCalledOnce()
    window.history.replaceState({ back: null }, '')
    handler(); expect(mocks.minimize).toHaveBeenCalledOnce()
  })
})
