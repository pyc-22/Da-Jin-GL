import { isNativeApp, scanNativeBarcode } from './nativeDevice.js'

const CANCELLED_MESSAGE = '已取消拍照扫码'
const DECODE_FAILED_MESSAGE = '未识别到二维码或条形码'

async function decodeWithZxing(url) {
  const { BrowserMultiFormatReader } = await import('@zxing/browser')
  const reader = new BrowserMultiFormatReader()
  return reader.decodeFromImageUrl(url)
}

export function scanBarcodeFromCamera(options = {}) {
  if (isNativeApp()) return scanNativeBarcode()
  const documentRef = options.documentRef || globalThis.document
  const urlApi = options.urlApi || globalThis.URL
  const decodeImageUrl = options.decodeImageUrl || decodeWithZxing

  if (!documentRef?.createElement || !urlApi?.createObjectURL) {
    return Promise.reject(new Error('当前浏览器不支持拍照扫码'))
  }

  return new Promise((resolve, reject) => {
    const input = documentRef.createElement('input')
    let objectUrl = ''
    let settled = false

    input.type = 'file'
    input.accept = 'image/*'
    input.capture = 'environment'
    input.hidden = true

    const cleanup = () => {
      if (objectUrl) urlApi.revokeObjectURL?.(objectUrl)
      input.remove?.()
    }

    const finish = (error, value) => {
      if (settled) return
      settled = true
      cleanup()
      if (error) reject(error)
      else resolve(value)
    }

    input.addEventListener('cancel', () => finish(new Error(CANCELLED_MESSAGE)), { once: true })
    input.addEventListener('change', async () => {
      const file = input.files?.[0]
      if (!file) {
        finish(new Error(CANCELLED_MESSAGE))
        return
      }

      try {
        objectUrl = urlApi.createObjectURL(file)
        const result = await decodeImageUrl(objectUrl)
        const text = String(result?.getText?.() ?? result?.text ?? result ?? '').trim()
        if (!text) throw new Error(DECODE_FAILED_MESSAGE)
        finish(null, text)
      } catch {
        finish(new Error(DECODE_FAILED_MESSAGE))
      }
    }, { once: true })

    documentRef.body?.appendChild(input)
    try {
      input.click()
    } catch {
      finish(new Error('相机启动失败，请检查浏览器权限'))
    }
  })
}
