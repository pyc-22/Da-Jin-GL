import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const app = readFileSync(resolve(process.cwd(), 'src/App.vue'), 'utf8')

describe('cashier silver prices', () => {
  it('shows independent silver sale and recycle prices', () => {
    expect(app).toContain("goldMap.value['银']")
    expect(app).toContain("goldMap.value['银回收价']")
    expect(app).toContain('银卖价')
    expect(app).toContain('银回收价')
  })
})
