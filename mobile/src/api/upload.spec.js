// @vitest-environment jsdom
import { beforeEach, expect, it, vi } from 'vitest'
const mocks = vi.hoisted(() => ({ post: vi.fn() }))
vi.mock('./request.js', () => ({ http: { post: mocks.post } }))
import { uploadImage } from './upload.js'
beforeEach(() => { mocks.post.mockReset() })
it('rejects oversized photos locally and explains server 413 errors', async () => {
  await expect(uploadImage('', { size: 5 * 1024 * 1024 + 1 })).rejects.toThrow('5MB')
  expect(mocks.post).not.toHaveBeenCalled()
  mocks.post.mockRejectedValue(Object.assign(new Error('413'), { response: { status: 413 } }))
  await expect(uploadImage('', new File(['photo'], 'photo.jpg'))).rejects.toThrow('服务器上传限制')
})
it('allows a photo above the old 1 MiB limit and uses the upload timeout', async () => {
  mocks.post.mockResolvedValue({ url: '/api/file/test.jpg' })
  await expect(uploadImage('', new File([new Uint8Array(1100000)], 'photo.jpg'))).resolves.toEqual({ url: '/api/file/test.jpg' })
  expect(mocks.post).toHaveBeenCalledWith('/api/upload', expect.any(FormData), { timeout: 60000 })
})
