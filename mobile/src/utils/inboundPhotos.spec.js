import { describe, expect, it } from 'vitest'
import { MAX_INBOUND_PHOTOS, isInboundPhotoSizeError, normalizeInboundPhotoSlots } from './inboundPhotos.js'

describe('inbound photos', () => {
  it('caps one item at four photo slots', () => {
    expect(normalizeInboundPhotoSlots(10)).toEqual(['', '', '', ''])
    expect(normalizeInboundPhotoSlots(3, ['a', 'b', 'c', 'd'])).toEqual(['a', 'b', 'c'])
    expect(MAX_INBOUND_PHOTOS).toBe(4)
  })

  it('distinguishes an oversized image from a network upload failure', () => {
    expect(isInboundPhotoSizeError(new Error('照片压缩后仍超过2MB，请重拍'))).toBe(true)
    expect(isInboundPhotoSizeError(new Error('Network Error'))).toBe(false)
  })
})
