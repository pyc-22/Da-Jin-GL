import { describe, expect, it } from 'vitest'
import { firstGoodsImage, parseGoodsImages } from './goodsImages.js'

describe('goods image display', () => {
  it('prefers a piece photo and resolves backend-relative paths', () => {
    const goods = { piece_image: '/api/file/piece.jpg', images: '["/api/file/catalog.jpg"]' }
    expect(firstGoodsImage(goods, 'http://localhost:8080')).toBe('http://localhost:8080/api/file/piece.jpg')
  })

  it('falls back to the first catalog photo', () => {
    expect(firstGoodsImage({ images: '["/uploads/ring.jpg","/uploads/side.jpg"]' })).toBe('/uploads/ring.jpg')
  })

  it('returns an empty list for malformed image data', () => {
    expect(parseGoodsImages('{bad json')).toEqual([])
    expect(firstGoodsImage({ images: '' })).toBe('')
  })
})
