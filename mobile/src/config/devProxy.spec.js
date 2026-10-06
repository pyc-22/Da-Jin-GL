import { readFileSync } from 'node:fs'
import { beforeAll, describe, expect, it } from 'vitest'

const configText = {}
beforeAll(() => {
  for (const name of ['vite.config.js', 'vite.local.config.js']) {
    configText[name] = readFileSync(new URL(`../../${name}`, import.meta.url), 'utf8')
  }
})

describe('mobile dev proxy contract', () => {
  it('proxies health checks and WebSocket traffic to the local backend', () => {
    for (const name of ['vite.config.js', 'vite.local.config.js']) {
      expect(configText[name]).toContain("'/actuator'")
      expect(configText[name]).toContain("'/ws'")
      expect(configText[name]).toContain('127.0.0.1:18080')
    }
  })
})
