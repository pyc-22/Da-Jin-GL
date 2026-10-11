import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(resolve(process.cwd(), 'src/views/Processing.vue'), 'utf8')

describe('admin processing photo authentication', () => {
  it('fetches private photos with Bearer auth and displays an object URL', () => {
    expect(source).toContain("fetch(requestUrl, { headers: { Authorization: `Bearer ${requestToken}` } })")
    expect(source).toContain('URL.createObjectURL(blob)')
    expect(source).toContain('URL.revokeObjectURL(url)')
    expect(source).toContain('onBeforeUnmount(releasePhotoObjectUrls)')
  })

  it('removes legacy token query values and rejects a different backend origin', () => {
    expect(source).toContain("url.searchParams.delete('access_token')")
    expect(source).toContain('if (url.origin !== apiOrigin) return \'\'')
    expect(source).not.toContain('encodeURIComponent(token)')
  })

  it('ignores late responses after dialog changes or component teardown', () => {
    expect(source).toContain('let photoGeneration = 0')
    expect(source).toContain('if (generation !== photoGeneration || auth.token !== requestToken) return')
    expect(source).toContain('if (generation === photoGeneration) photoLoading.delete(raw)')
    expect(source).toContain('photoGeneration += 1')
  })
})
