import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(resolve(process.cwd(), 'src/views/Stock.vue'), 'utf8')

describe('manual stock inbound photos', () => {
  it('provides image selection, upload and persisted image data in the inbound form', () => {
    expect(source).toContain('商品照片')
    expect(source).toContain('accept="image/*"')
    expect(source).toContain('uploadApi.image')
    expect(source).toMatch(/images\s*:/)
  })
})
