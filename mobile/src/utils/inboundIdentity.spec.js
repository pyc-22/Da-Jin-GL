import { describe, expect, it } from 'vitest'
import { inboundItemKey, parseInboundScanPayload, sameInboundIdentity } from './inboundIdentity.js'

describe('inbound identity', () => {
  it('keeps a legacy plain barcode compatible', () => {
    expect(parseInboundScanPayload('697000000001')).toEqual({ barcode: '697000000001', pieceNo: '' })
  })

  it('separates a product barcode from a physical piece number', () => {
    expect(parseInboundScanPayload(JSON.stringify({
      goodsBarcode: 'G999-RING',
      pieceNo: 'G999-RING-20260916-000001',
      name: '足金999戒指'
    }))).toMatchObject({
      barcode: 'G999-RING',
      pieceNo: 'G999-RING-20260916-000001',
      name: '足金999戒指'
    })
  })

  it('keeps legacy serial-only labels as product barcodes', () => {
    expect(parseInboundScanPayload('{"serial":"LEGACY-001"}')).toMatchObject({ barcode: 'LEGACY-001', pieceNo: '' })
  })

  it('uses the piece number as the duplicate identity when present', () => {
    const rows = [{ barcode: 'G999-RING', pieceNo: 'PIECE-001' }]
    expect(sameInboundIdentity(rows[0], { barcode: 'G999-RING', pieceNo: 'PIECE-001' })).toBe(true)
    expect(sameInboundIdentity(rows[0], { barcode: 'G999-RING', pieceNo: 'PIECE-002' })).toBe(false)
    expect(inboundItemKey(rows[0])).toBe('piece-PIECE-001')
  })
})
