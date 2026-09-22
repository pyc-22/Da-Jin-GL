import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'

const { auth } = vi.hoisted(() => ({ auth: { token: '', refreshToken: '', logout: vi.fn() } }))
vi.mock('../stores/auth.js', () => ({ useAuthStore: () => auth }))
import { http } from './request.js'

describe('browser API origin', () => {
  it('uses the same-origin proxy when no API base is configured', () => {
    expect(http.defaults.baseURL).toBe('')
  })
})

describe('expired requests across account changes', () => {
  beforeEach(() => { auth.token = 'old-token'; auth.refreshToken = ''; auth.logout.mockClear(); vi.stubGlobal('window', undefined) })
  afterEach(() => vi.unstubAllGlobals())

  it('keeps the new account logged in when an old request returns 401', async () => {
    let rejectRequest, sent
    const request = http.get('/test', { adapter: config => {
      sent = config
      return new Promise((resolve, reject) => { rejectRequest = reject })
    } })
    const result = request.catch(error => error)
    await vi.waitFor(() => expect(sent).toBeTruthy())
    expect(sent.headers.Authorization).toBe('Bearer old-token')
    auth.token = 'new-token'
    rejectRequest({ config: sent, response: { status: 401 } })
    await result
    expect(auth.logout).not.toHaveBeenCalled()
    expect(auth.token).toBe('new-token')
  })

  it('still signs out the current expired session', async () => {
    await expect(http.get('/test', { adapter: config => Promise.reject({ config, response: { status: 401 } }) })).rejects.toBeTruthy()
    expect(auth.logout).toHaveBeenCalledOnce()
  })
})
