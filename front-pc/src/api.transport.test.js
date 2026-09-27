// @vitest-environment jsdom
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'

let api
let network

beforeEach(async () => {
  vi.resetModules()
  localStorage.clear()
  network = { request: vi.fn(), uploadPhoto: vi.fn() }
  window.dajin = { network }
  api = await import('./api.js')
  api.setApiBase('https://admin.xinchengjinjiang.com')
})

afterEach(() => {
  delete window.dajin
  vi.restoreAllMocks()
})

describe('cashier packaged transport', () => {
  it('sends login through the main process and keeps backend error semantics', async () => {
    network.request.mockResolvedValueOnce({ status: 200, body: JSON.stringify({ code: 200, data: { token: 'session', user: {} } }) })
    await api.login('cashier', 'secret')
    expect(network.request).toHaveBeenCalledWith(expect.objectContaining({
      path: '/api/auth/login', method: 'POST', body: expect.stringContaining('cashier')
    }))
    expect(api.getToken()).toBe('session')

    network.request.mockResolvedValueOnce({ status: 401, body: JSON.stringify({ code: 401, message: '已过期' }) })
    await expect(api.request('/api/member/list')).rejects.toMatchObject({ status: 401, message: '已过期' })
    expect(api.getToken()).toBe('')
  })

  it('sends photo bytes through the main process with the same session', async () => {
    api.setToken('session')
    network.uploadPhoto.mockResolvedValue({ status: 200, body: '{"code":200,"data":{"url":"/uploads/photo.jpg"}}' })
    const file = { name: 'photo.jpg', type: 'image/jpeg', arrayBuffer: async () => Uint8Array.from([1, 2, 3]).buffer }
    const response = await api.uploadBackendPhoto(file, 'PROC-1')
    expect((await response.json()).data.url).toBe('/uploads/photo.jpg')
    expect(network.uploadPhoto).toHaveBeenCalledWith(expect.objectContaining({
      filename: 'photo.jpg', type: 'image/jpeg', orderNo: 'PROC-1', authorization: 'Bearer session',
      bytes: Uint8Array.from([1, 2, 3])
    }))
  })

  it('keeps normal browser requests on fetch', async () => {
    delete window.dajin
    const browserFetch = vi.fn(async () => new Response('{"code":200,"data":42}', { status: 200 }))
    vi.stubGlobal('fetch', browserFetch)
    expect(await api.request('/api/test')).toBe(42)
    expect(browserFetch).toHaveBeenCalledWith('https://admin.xinchengjinjiang.com/api/test', expect.any(Object))
    vi.unstubAllGlobals()
  })
})
