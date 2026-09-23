import { Capacitor, registerPlugin } from '@capacitor/core'
import { Camera, CameraResultType, CameraSource } from '@capacitor/camera'
import { App } from '@capacitor/app'

const scanner = registerPlugin('DajinScanner')
export const isNativeApp = () => Capacitor.isNativePlatform()

export async function scanNativeBarcode() {
  const result = await scanner.scan()
  const value = String(result?.text || '').trim()
  if (!value) throw new Error('已取消扫码')
  return value
}

export async function takeNativePhoto() {
  const photo = await Camera.getPhoto({
    quality: 85, width: 1600, height: 1600, correctOrientation: true,
    resultType: CameraResultType.Uri, source: CameraSource.Camera,
    saveToGallery: false
  })
  if (!photo.webPath) throw new Error('未取得照片，请重新拍照')
  const response = await fetch(photo.webPath)
  if (!response.ok) throw new Error('照片读取失败，请重试')
  const blob = await response.blob()
  return new File([blob], `photo-${Date.now()}.${photo.format || 'jpeg'}`, { type: blob.type || 'image/jpeg' })
}

export function setupNativeNavigation(router) {
  if (!isNativeApp()) return
  App.addListener('backButton', () => {
    if (window.history.state?.back) router.back()
    else App.minimizeApp()
  })
}
