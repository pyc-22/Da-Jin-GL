// 自行合成两音提示声，不依赖在线音源。两端使用完全相同的 WAV。
import { mkdir, writeFile } from 'node:fs/promises'
const rate = 22050, length = Math.floor(rate * 0.7)
const wav = Buffer.alloc(44 + length * 2)
wav.write('RIFF'); wav.writeUInt32LE(36 + length * 2, 4); wav.write('WAVEfmt ', 8)
wav.writeUInt32LE(16, 16); wav.writeUInt16LE(1, 20); wav.writeUInt16LE(1, 22)
wav.writeUInt32LE(rate, 24); wav.writeUInt32LE(rate * 2, 28)
wav.writeUInt16LE(2, 32); wav.writeUInt16LE(16, 34); wav.write('data', 36); wav.writeUInt32LE(length * 2, 40)
for (let i = 0; i < length; i++) {
  const t = i / rate
  const tone = (start, frequency) => {
    const x = t - start
    return x < 0 || x > 0.36 ? 0 : Math.sin(2 * Math.PI * frequency * x) * Math.min(1, x / 0.015) * Math.exp(-11 * x)
  }
  wav.writeInt16LE(Math.round(7200 * (tone(0, 880) + tone(0.25, 1174.66))), 44 + i * 2)
}
for (const relative of ['../public/sounds/new-message.wav', '../android/app/src/main/res/raw/new_message.wav']) {
  const file = new URL(relative, import.meta.url)
  await mkdir(new URL('.', file), { recursive: true }); await writeFile(file, wav)
}
