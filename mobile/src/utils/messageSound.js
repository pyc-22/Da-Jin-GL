import { isAndroidMessages, messageNative } from './messagePermissions.js'

let context, buffer, loading
function audioContext() {
  const AudioContext = window.AudioContext || window.webkitAudioContext
  if (!AudioContext) return null
  return context ||= new AudioContext()
}
async function soundBuffer(ctx) {
  if (buffer) return buffer
  if (!loading) loading = fetch('/sounds/new-message.wav').then(response => {
    if (!response.ok) throw new Error('提示音加载失败')
    return response.arrayBuffer()
  }).then(bytes => ctx.decodeAudioData(bytes)).then(value => (buffer = value)).finally(() => { loading = null })
  return loading
}
/** H5 需要一次用户手势解锁音频。Android 使用原生通知音量，不走媒体音量。 */
export function installSoundUnlock() {
  if (isAndroidMessages()) return () => {}
  const unlock = () => {
    const ctx = audioContext()
    if (ctx) { void ctx.resume().catch(() => {}); void soundBuffer(ctx).catch(() => {}) }
  }
  window.addEventListener('pointerdown', unlock)
  window.addEventListener('keydown', unlock)
  return () => { window.removeEventListener('pointerdown', unlock); window.removeEventListener('keydown', unlock) }
}
export async function playMessageSound(owner, stillCurrent = () => true) {
  if (!stillCurrent()) return { played: false, reason: 'account-changed' }
  if (isAndroidMessages()) return messageNative.playSound({ owner })
  const ctx = audioContext()
  if (!ctx) return { played: false, reason: 'audio-unsupported' }
  const decoded = await soundBuffer(ctx)
  if (!stillCurrent()) return { played: false, reason: 'account-changed' }
  if (ctx.state !== 'running') return { played: false, reason: 'user-gesture-required' }
  const source = ctx.createBufferSource()
  source.buffer = decoded; source.connect(ctx.destination); source.start()
  return { played: true }
}
