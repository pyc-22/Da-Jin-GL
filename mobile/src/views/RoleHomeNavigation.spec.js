import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const home = readFileSync(resolve(process.cwd(), 'src/views/RoleHome.vue'), 'utf8')

describe('role home navigation', () => {
  it('changes sections from the bottom navigation, not content swipes', () => {
    expect(home).toContain('@click="section=tab.key"')
    expect(home).not.toContain('horizontalSwipeDirection')
    expect(home).not.toMatch(/function touchEnd[^\n]+section\.value=/)
  })
})
