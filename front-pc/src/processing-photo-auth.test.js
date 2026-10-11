import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(resolve(process.cwd(), 'src/App.vue'), 'utf8')

describe('cashier processing photo lifecycle', () => {
  it('fetches private photos with a fixed Bearer token and rejects late responses', () => {
    expect(source).toContain('headers: { Authorization: `Bearer ${requestToken}` }')
    expect(source).toContain('if (generation !== photoGeneration || getToken() !== requestToken) return')
    expect(source).toContain('if (generation === photoGeneration) photoLoading.delete(path)')
    expect(source).toContain('photoGeneration += 1')
    expect(source).toContain('URL.revokeObjectURL(url)')
  })
})
