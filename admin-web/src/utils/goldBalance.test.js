import { describe, expect, it } from 'vitest'
import { goldBalance } from './goldBalance'

describe('gold balance prefill', () => {
  it('follows 成品实重 − 融后金重', () => {
    const short = goldBalance({ melt: 9.5, finished: 10 })
    expect(short.balance).toBe(0.5)
    expect(short.topUp).toBe(0.5)
    expect(short.hint).toContain('客户需补金 0.500g')

    const surplus = goldBalance({ melt: 15, finished: 12 })
    expect(surplus.balance).toBe(-3)
    expect(surplus.topUp).toBe(0)
    expect(surplus.hint).toContain('没有补金')

    const noMelt = goldBalance({ melt: 0, finished: 1.25 })
    expect(noMelt.balance).toBe(1.25)
    expect(noMelt.topUp).toBe(1.25)
    expect(goldBalance({ melt: 40, finished: 40 })).toMatchObject({ balance: 0, topUp: 0 })
  })

  it('stays silent until both melt and finished weights are known', () => {
    expect(goldBalance({ melt: null, finished: 12 })).toEqual({ balance: null, topUp: 0, hint: '' })
    expect(goldBalance({ melt: 15, finished: null })).toEqual({ balance: null, topUp: 0, hint: '' })
    expect(goldBalance()).toEqual({ balance: null, topUp: 0, hint: '' })
  })
})
