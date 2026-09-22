import { describe, expect, it, vi } from 'vitest'
import { scanBarcodeFromCamera } from './browserBarcode.js'

function createDocument(file) {
  const input = {
    accept: '',
    capture: '',
    files: file ? [file] : [],
    type: '',
    value: '',
    addEventListener: vi.fn((event, handler) => {
      if (event === 'change') input.onChange = handler
    }),
    remove: vi.fn(),
    click: vi.fn(() => input.onChange?.()),
  }

  return {
    input,
    documentRef: {
      createElement: vi.fn(() => input),
      body: { appendChild: vi.fn() },
    },
  }
}

describe('browser barcode camera scan', () => {
  it('opens the rear camera image picker and returns the decoded text', async () => {
    const file = new File(['barcode'], 'barcode.jpg', { type: 'image/jpeg' })
    const { input, documentRef } = createDocument(file)
    const revokeObjectURL = vi.fn()

    const result = await scanBarcodeFromCamera({
      documentRef,
      urlApi: {
        createObjectURL: vi.fn(() => 'blob:barcode'),
        revokeObjectURL,
      },
      decodeImageUrl: vi.fn(async () => ({ getText: () => 'GOLD-001' })),
    })

    expect(input.type).toBe('file')
    expect(input.accept).toBe('image/*')
    expect(input.capture).toBe('environment')
    expect(input.click).toHaveBeenCalledOnce()
    expect(result).toBe('GOLD-001')
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:barcode')
    expect(input.remove).toHaveBeenCalledOnce()
  })

  it('reports cancellation without trying to decode', async () => {
    const { documentRef } = createDocument()
    const decodeImageUrl = vi.fn()

    await expect(scanBarcodeFromCamera({ documentRef, decodeImageUrl }))
      .rejects.toThrow('已取消拍照扫码')
    expect(decodeImageUrl).not.toHaveBeenCalled()
  })

  it('uses a clear message when the photographed code cannot be decoded', async () => {
    const file = new File(['no-code'], 'photo.jpg', { type: 'image/jpeg' })
    const { documentRef } = createDocument(file)

    await expect(scanBarcodeFromCamera({
      documentRef,
      urlApi: {
        createObjectURL: () => 'blob:no-code',
        revokeObjectURL: vi.fn(),
      },
      decodeImageUrl: vi.fn(async () => { throw new Error('NotFoundException') }),
    })).rejects.toThrow('未识别到二维码或条形码')
  })
})
