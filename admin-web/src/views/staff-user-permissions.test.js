import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const view = readFileSync(resolve(process.cwd(), 'src/views/Staff.vue'), 'utf8')
const api = readFileSync(resolve(process.cwd(), 'src/api/modules.js'), 'utf8')

describe('staff-specific permissions', () => {
  it('configures permissions per employee instead of per role', () => {
    expect(view).toContain('员工权限')
    expect(view).toContain('permissionUser')
    expect(view).toContain('staffApi.userPermissions')
    expect(view).toContain('staffApi.saveUserPermissions')
    expect(view).not.toContain('staffApi.rolePermissions')
    expect(api).toContain('/permissions`')
  })
})
