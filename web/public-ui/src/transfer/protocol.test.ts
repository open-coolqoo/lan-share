import { describe, expect, it, vi } from 'vitest'
import { createControlMessage, createFileOffer, createRecallMessage, createTextMessage, parseControlMessage, randomUUID, sanitizeFileName, sha256Hex } from './protocol'

describe('transfer protocol helpers', () => {
  it('sanitizes path-like file names before publishing metadata', () => {
    expect(sanitizeFileName('../secret/<script>.svg')).toBe('._secret__script_.svg')
  })

  it('never returns an empty file name', () => {
    expect(sanitizeFileName('////')).toBe('____')
    expect(sanitizeFileName('   ')).toBe('unnamed-file')
  })

  it('builds a v4 UUID without crypto.randomUUID on insecure origins', () => {
    const original = Object.getOwnPropertyDescriptor(globalThis.crypto, 'randomUUID')
    Object.defineProperty(globalThis.crypto, 'randomUUID', { configurable: true, value: undefined })
    try {
      expect(randomUUID()).toMatch(
        /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
      )
    } finally {
      if (original) Object.defineProperty(globalThis.crypto, 'randomUUID', original)
      else delete (globalThis.crypto as { randomUUID?: unknown }).randomUUID
    }
  })

  it('creates metadata without reading file contents', () => {
    const file = new File(['hello'], 'note.txt', { type: 'text/plain' })
    const offer = createFileOffer(file, 2)

    expect(offer.name).toBe('note.txt')
    expect(offer.size).toBe(5)
    expect(offer.mime).toBe('text/plain')
    expect(offer.totalChunks).toBe(3)
    expect(offer.fileId).toMatch(/^file_/)
    expect(offer.transferId).toMatch(/^transfer_/)
  })

  it('keeps a zero-byte file as a single transfer unit', () => {
    const file = new File([], 'empty.txt')
    const offer = createFileOffer(file)

    expect(offer.size).toBe(0)
    expect(offer.totalChunks).toBe(1)
  })

  it('round-trips valid control messages', () => {
    const offer = createFileOffer(new File(['hello'], 'note.txt'))
    const message = createControlMessage('file_offer', offer, { offer })

    expect(parseControlMessage(JSON.stringify(message))).toEqual(message)
  })

  it('ignores unknown control message types instead of failing the session', () => {
    expect(
      parseControlMessage(
        JSON.stringify({
          protocolVersion: 1,
          messageId: 'msg_1',
          transferId: 'transfer_1',
          fileId: 'file_1',
          type: 'unknown',
        }),
      ),
    ).toBeNull()
  })

  it('round-trips in-band text messages and rejects oversized or empty text', () => {
    const message = createTextMessage('hello peer')
    expect(parseControlMessage(JSON.stringify(message))).toEqual(message)
    expect(() =>
      parseControlMessage(JSON.stringify({ ...message, text: '' })),
    ).toThrow(/Invalid text message/)
    expect(() =>
      parseControlMessage(JSON.stringify({ ...message, text: 'x'.repeat(64 * 1024 + 1) })),
    ).toThrow(/Invalid text message/)
  })

  it('round-trips a recall message and rejects an unusable target', () => {
    const message = createRecallMessage('file', 'file_1')

    expect(parseControlMessage(JSON.stringify(message))).toEqual(message)
    expect(() => parseControlMessage(JSON.stringify({ ...message, target: 'room' }))).toThrow(/Invalid recall target/)
    expect(() => parseControlMessage(JSON.stringify({ ...message, targetId: '' }))).toThrow(/Invalid recall target identity/)
  })

  it('rejects chunk metadata with a malformed checksum field', () => {
    const offer = createFileOffer(new File(['hello'], 'note.txt'), 5)
    expect(() =>
      parseControlMessage(
        JSON.stringify(createControlMessage('chunk_meta', offer, { index: 0, offset: 0, size: 5, sha256: 'nothex' })),
      ),
    ).toThrow(/Invalid chunk checksum/)
    expect(
      parseControlMessage(
        JSON.stringify(createControlMessage('chunk_meta', offer, { index: 0, offset: 0, size: 5, sha256: 'a'.repeat(64) })),
      ),
    ).toBeTruthy()
  })

  it('computes the sha256 hex digest of a chunk buffer', async () => {
    const digest = await sha256Hex(new TextEncoder().encode('hello').buffer as ArrayBuffer)
    expect(digest).toBe('2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824')
  })

  it('rejects file offers with inconsistent chunk metadata', () => {
    const offer = {
      fileId: 'file_1',
      transferId: 'transfer_1',
      name: 'peer.txt',
      size: 6,
      mime: 'text/plain',
      chunkSize: 3,
      totalChunks: 3,
    }

    expect(() =>
      parseControlMessage(
        JSON.stringify({
          protocolVersion: 1,
          messageId: 'msg_1',
          transferId: offer.transferId,
          fileId: offer.fileId,
          type: 'file_offer',
          offer,
        }),
      ),
    ).toThrow(/Invalid file offer/)
  })

  it('rejects offers whose chunk table would be unreasonably large', () => {
    const offer = {
      fileId: 'file_1',
      transferId: 'transfer_1',
      name: 'peer.txt',
      size: 1_000_000,
      mime: 'text/plain',
      chunkSize: 1,
      totalChunks: 1_000_000,
    }

    expect(() =>
      parseControlMessage(
        JSON.stringify({
          protocolVersion: 1,
          messageId: 'msg_1',
          transferId: offer.transferId,
          fileId: offer.fileId,
          type: 'file_offer',
          offer,
        }),
      ),
    ).toThrow(/Invalid file offer/)
  })

  it('rejects offers with an oversized negotiated chunk size', () => {
    const chunkSize = 32 * 1024 * 1024
    const offer = {
      fileId: 'file_1',
      transferId: 'transfer_1',
      name: 'peer.txt',
      size: chunkSize,
      mime: 'text/plain',
      chunkSize,
      totalChunks: 1,
    }

    expect(() =>
      parseControlMessage(
        JSON.stringify({
          protocolVersion: 1,
          messageId: 'msg_1',
          transferId: offer.transferId,
          fileId: offer.fileId,
          type: 'file_offer',
          offer,
        }),
      ),
    ).toThrow(/Invalid file offer/)
  })
})
