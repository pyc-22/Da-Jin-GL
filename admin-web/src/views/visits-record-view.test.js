import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(resolve(process.cwd(), 'src/views/Visits.vue'), 'utf8')

describe('visit record visibility', () => {
  it('shows the saved record, completion time and a full detail view', () => {
    expect(source).toContain('回访内容')
    expect(source).toContain('scope.row.record')
    expect(source).toContain('完成时间')
    expect(source).toContain('查看详情')
    expect(source).toContain('selectedRecord.record')
  })
})
