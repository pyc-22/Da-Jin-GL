import { describe, expect, it } from 'vitest'
import { createLatestOnlyGuard } from './front-todo'

describe('front todo refresh ordering', () => {
  it('accepts only the newest refresh result', () => {
    const guard = createLatestOnlyGuard()
    const oldRequest = guard.begin()
    const newRequest = guard.begin()

    expect(guard.isCurrent(oldRequest)).toBe(false)
    expect(guard.isCurrent(newRequest)).toBe(true)
  })
})
