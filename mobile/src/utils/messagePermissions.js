import { Capacitor, registerPlugin } from '@capacitor/core'

// 自有 Capacitor 原生桥接；仅本地通知，没有第三方或厂商推送 SDK。
export const messageNative = registerPlugin('DajinMessages')
export const isAndroidMessages = () => Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android'

export async function backgroundSettings() {
  if (!isAndroidMessages()) return { native: false }
  return { native: true, ...await messageNative.getSettings() }
}
export async function requestMessagePermission() {
  if (!isAndroidMessages()) return { native: false }
  return messageNative.requestNotificationPermission()
}
/** 必须由用户点击触发。打开设置不表示已获准，返回 APP 后重新查询。 */
export async function openBackgroundSetting(kind) {
  if (!isAndroidMessages()) return
  return messageNative.openSettings({ kind })
}
