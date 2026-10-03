import { describe, expect, it } from 'vitest'
import { reportRange } from './reportRange'

describe('reportRange', () => {
  const now = new Date(2026, 9, 3)
  it.each([
    ['today', '2026-10-03', '2026-10-03'],
    ['yesterday', '2026-10-02', '2026-10-02'],
    ['week', '2026-09-28', '2026-10-03'],
    ['month', '2026-10-01', '2026-10-03'],
    ['lastMonth', '2026-09-01', '2026-09-30']
  ])('resolves %s using local calendar dates', (type, from, to) => {
    expect(reportRange(type, '', '', now)).toEqual({ from, to })
  })
  it('preserves a custom inclusive range', () => {
    expect(reportRange('custom', '2026-08-01', '2026-08-20', now)).toEqual({ from: '2026-08-01', to: '2026-08-20' })
  })
})
