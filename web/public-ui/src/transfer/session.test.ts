import { describe, expect, it, vi } from 'vitest'
import { TransferLimits, TransferSession, TransferStatus } from './session'

class FakeDataChannel {
  binaryType = ''
  bufferedAmount = 0
  bufferedAmountLowThreshold = 0
  readyState = 'open'
  onmessage: ((event: { data: unknown }) => void) | null = null
  onerror: (() => void) | null = null
  sent: unknown[] = []
  private listeners = new Map<string, Set<() => void>>()

  send(value: unknown) {
    this.sent.push(value)
  }

  addEventListener(type: string, handler: () => void) {
    const handlers = this.listeners.get(type) ?? new Set()
    handlers.add(handler)
    this.listeners.set(type, handlers)
  }

  removeEventListener(type: string, handler: () => void) {
    this.listeners.get(type)?.delete(handler)
  }

  listenerCount(type: string) {
    return this.listeners.get(type)?.size ?? 0
  }
}

function createSession(limits: TransferLimits | (() => TransferLimits) = { maxFileSize: 10, chunkSize: 4 }, options: { autoAcceptRemoteOffers?: boolean; deferAutoAcceptedRemoteOfferVisibility?: boolean } = {}) {
  const channel = new FakeDataChannel()
  const statuses: Array<{ status: TransferStatus; reason?: string }> = []
  const progress = vi.fn()
  const onLocalOffer = vi.fn()
  const onRemoteOffer = vi.fn()
  const onComplete = vi.fn()
  const onError = vi.fn()
  const onChannelFailure = vi.fn()
  const onTextMessage = vi.fn()
  const onRecalled = vi.fn()
  const session = new TransferSession(channel as unknown as RTCDataChannel, {
    limits,
    autoAcceptRemoteOffers: options.autoAcceptRemoteOffers,
    deferAutoAcceptedRemoteOfferVisibility: options.deferAutoAcceptedRemoteOfferVisibility,
    onLocalOffer,
    onRemoteOffer,
    onStatus: (_fileId, status, reason) => statuses.push({ status, reason }),
    onProgress: (_fileId, nextProgress) => progress(nextProgress),
    onComplete,
    onError: (_fileId, reason) => onError(reason),
    onChannelFailure,
    onTextMessage,
    onRecalled,
  })
  return { channel, session, statuses, progress, onLocalOffer, onRemoteOffer, onComplete, onError, onChannelFailure, onTextMessage, onRecalled }
}

function control(type: string, offer: TestOffer, extra: Record<string, unknown> = {}) {
  return JSON.stringify({
    protocolVersion: 1,
    messageId: `msg_${crypto.randomUUID()}`,
    transferId: offer.transferId,
    fileId: offer.fileId,
    type,
    ...extra,
  })
}

type TestOffer = {
  fileId: string
  transferId: string
  name: string
  size: number
  mime: string
  chunkSize: number
  totalChunks: number
}

describe('TransferSession limits', () => {
  it('rejects a local file above the server connection limit before sending an offer', () => {
    const { channel, session } = createSession({ maxFileSize: 3, chunkSize: 2 })

    expect(() => session.publishOffer(new File(['abcd'], 'large.txt'))).toThrow(/too large/)
    expect(channel.sent).toHaveLength(0)
  })

  it('uses the server chunk size in published file metadata', () => {
    const { channel, session } = createSession({ maxFileSize: 20, chunkSize: 3 })

    const offer = session.publishOffer(new File(['abcdefg'], 'note.txt'))

    expect(offer.chunkSize).toBe(3)
    expect(offer.totalChunks).toBe(3)
    expect(channel.sent).toHaveLength(1)
  })

  it('reads the latest server limits through the getter used by the app shell', () => {
    let limits = { maxFileSize: 3, chunkSize: 2 }
    const { channel, session } = createSession(() => limits)

    expect(() => session.publishOffer(new File(['abcd'], 'large.txt'))).toThrow(/too large/)
    expect(channel.sent).toHaveLength(0)

    limits = { maxFileSize: 20, chunkSize: 3 }
    const offer = session.publishOffer(new File(['abcdefg'], 'note.txt'))

    expect(offer.chunkSize).toBe(3)
    expect(channel.sent).toHaveLength(1)
  })

  it('publishes multiple selected files as separate offers', () => {
    const { channel, session, statuses } = createSession({ maxFileSize: 20, chunkSize: 3 })

    const offers = session.publishOffers([
      new File(['abc'], 'first.txt'),
      new File(['defg'], 'second.txt'),
    ])

    expect(offers).toHaveLength(2)
    expect(offers[0].fileId).not.toBe(offers[1].fileId)
    expect(channel.sent).toHaveLength(2)
    expect(channel.sent.map((message) => JSON.parse(String(message)).type)).toEqual(['file_offer', 'file_offer'])
    expect(statuses.filter((entry) => entry.status === 'waiting_accept')).toHaveLength(2)
  })

  it('reports channel-level failures to the app shell', () => {
    const { channel, onError, onChannelFailure } = createSession({ maxFileSize: 20, chunkSize: 3 })

    channel.onerror?.()

    expect(onError).toHaveBeenCalledWith('File channel failed.')
    expect(onChannelFailure).toHaveBeenCalledWith('File channel failed.')
  })

  it('reports structured progress when a remote offer is accepted', () => {
    const { channel, session, progress } = createSession({ maxFileSize: 20, chunkSize: 3 })
    const offer = {
      fileId: 'file_1',
      transferId: 'transfer_1',
      name: 'peer.txt',
      size: 6,
      mime: 'text/plain',
      chunkSize: 3,
      totalChunks: 2,
    }
    channel.onmessage?.({ data: control('file_offer', offer, { offer }) })

    session.acceptRemoteOffer()

    expect(progress).toHaveBeenCalledWith(expect.objectContaining({
      percent: 0,
      bytesDone: 0,
      bytesTotal: 6,
    }))
  })

  it('queues batch remote downloads behind the active download', async () => {
    const { channel, session, statuses, onComplete } = createSession({ maxFileSize: 20, chunkSize: 3 })
    const first = {
      fileId: 'file_1',
      transferId: 'transfer_1',
      name: 'first.txt',
      size: 1,
      mime: 'text/plain',
      chunkSize: 3,
      totalChunks: 1,
    }
    const second = {
      fileId: 'file_2',
      transferId: 'transfer_2',
      name: 'second.txt',
      size: 1,
      mime: 'text/plain',
      chunkSize: 3,
      totalChunks: 1,
    }
    channel.onmessage?.({ data: control('file_offer', first, { offer: first }) })
    channel.onmessage?.({ data: control('file_offer', second, { offer: second }) })

    session.acceptRemoteOffers([first.fileId, second.fileId])

    expect(channel.sent.map((message) => JSON.parse(String(message)).type)).toEqual(['file_accept'])
    expect(JSON.parse(String(channel.sent.at(-1))).fileId).toBe(first.fileId)

    channel.onmessage?.({ data: control('chunk_meta', first, { index: 0, offset: 0, size: 1 }) })
    channel.onmessage?.({ data: new Uint8Array([1]).buffer })
    channel.onmessage?.({ data: control('file_complete', first, { bytesSent: 1 }) })

    await vi.waitFor(() => expect(onComplete).toHaveBeenCalledWith(expect.objectContaining({ offer: first })))
    expect(channel.sent.map((message) => JSON.parse(String(message)).type)).toEqual(['file_accept', 'file_accept'])
    expect(JSON.parse(String(channel.sent.at(-1))).fileId).toBe(second.fileId)
    expect(statuses.filter((entry) => entry.status === 'transferring')).toHaveLength(2)
  })

  it('auto-accepts remote offers when configured by the session', () => {
    const { channel, statuses, progress } = createSession({ maxFileSize: 20, chunkSize: 3 }, { autoAcceptRemoteOffers: true })
    const offer = {
      fileId: 'file_auto_accept',
      transferId: 'transfer_auto_accept',
      name: 'auto-accept.txt',
      size: 6,
      mime: 'text/plain',
      chunkSize: 3,
      totalChunks: 2,
    }

    channel.onmessage?.({ data: control('file_offer', offer, { offer }) })

    expect(channel.sent.map((message) => JSON.parse(String(message)).type)).toEqual(['file_accept'])
    expect(JSON.parse(String(channel.sent.at(-1))).fileId).toBe(offer.fileId)
    expect(statuses).toContainEqual({ status: 'offered', reason: undefined })
    expect(statuses).toContainEqual({ status: 'transferring', reason: undefined })
    expect(progress).toHaveBeenCalledWith(expect.objectContaining({
      percent: 0,
      bytesDone: 0,
      bytesTotal: 6,
    }))
  })

  it('keeps auto-accepted offers hidden until the received file is complete', async () => {
    const { channel, statuses, progress, onRemoteOffer, onComplete } = createSession(
      { maxFileSize: 20, chunkSize: 3 },
      { autoAcceptRemoteOffers: true, deferAutoAcceptedRemoteOfferVisibility: true },
    )
    const offer = {
      fileId: 'file_auto_accept',
      transferId: 'transfer_auto_accept',
      name: 'auto-accept.txt',
      size: 3,
      mime: 'text/plain',
      chunkSize: 3,
      totalChunks: 1,
    }

    channel.onmessage?.({ data: control('file_offer', offer, { offer }) })

    expect(channel.sent.map((message) => JSON.parse(String(message)).type)).toEqual(['file_accept'])
    expect(onRemoteOffer).not.toHaveBeenCalled()
    expect(statuses).toContainEqual({ status: 'offered', reason: undefined })
    expect(statuses).toContainEqual({ status: 'transferring', reason: undefined })
    expect(progress).toHaveBeenCalledWith(expect.objectContaining({
      percent: 0,
      bytesDone: 0,
      bytesTotal: 3,
    }))

    channel.onmessage?.({ data: control('chunk_meta', offer, { index: 0, offset: 0, size: 3 }) })
    channel.onmessage?.({ data: new TextEncoder().encode('abc').buffer })
    channel.onmessage?.({ data: control('file_complete', offer, { bytesSent: 3 }) })

    await vi.waitFor(() => expect(onComplete).toHaveBeenCalledWith(expect.objectContaining({ offer })))
    expect(onRemoteOffer).toHaveBeenCalledTimes(1)
    expect(onRemoteOffer).toHaveBeenCalledWith(expect.objectContaining({
      offer,
      status: 'complete',
      progress: expect.objectContaining({
        percent: 100,
        bytesDone: 3,
        bytesTotal: 3,
      }),
    }))
  })

  it('skips completed remote offers when batch downloading', async () => {
    const { channel, session, onComplete } = createSession({ maxFileSize: 20, chunkSize: 3 })
    const offer = {
      fileId: 'file_1',
      transferId: 'transfer_1',
      name: 'peer.txt',
      size: 1,
      mime: 'text/plain',
      chunkSize: 3,
      totalChunks: 1,
    }
    channel.onmessage?.({ data: control('file_offer', offer, { offer }) })
    session.acceptRemoteOffer(offer.fileId)
    channel.onmessage?.({ data: control('chunk_meta', offer, { index: 0, offset: 0, size: 1 }) })
    channel.onmessage?.({ data: new Uint8Array([1]).buffer })
    channel.onmessage?.({ data: control('file_complete', offer, { bytesSent: 1 }) })

    await vi.waitFor(() => expect(onComplete).toHaveBeenCalledWith(expect.objectContaining({ offer })))
    channel.sent.splice(0)

    session.acceptRemoteOffers([offer.fileId])

    expect(channel.sent).toHaveLength(0)
  })

  it('rejects direct remote accept after a file is already complete', () => {
    const { channel, session } = createSession({ maxFileSize: 20, chunkSize: 3 })
    const offer = {
      fileId: 'file_1',
      transferId: 'transfer_1',
      name: 'peer.txt',
      size: 1,
      mime: 'text/plain',
      chunkSize: 3,
      totalChunks: 1,
    }
    channel.onmessage?.({ data: control('file_offer', offer, { offer }) })
    session.acceptRemoteOffer(offer.fileId)
    channel.onmessage?.({ data: control('chunk_meta', offer, { index: 0, offset: 0, size: 1 }) })
    channel.onmessage?.({ data: new Uint8Array([1]).buffer })
    channel.onmessage?.({ data: control('file_complete', offer, { bytesSent: 1 }) })

    expect(() => session.acceptRemoteOffer(offer.fileId)).toThrow(/not available/)
  })

  it('rejects a remote offer above the server connection limit before requesting bytes', () => {
    const { channel, session, statuses } = createSession({ maxFileSize: 3, chunkSize: 2 })
    const offer = {
      fileId: 'file_large',
      transferId: 'transfer_large',
      name: 'large.txt',
      size: 4,
      mime: 'text/plain',
      chunkSize: 2,
      totalChunks: 2,
    }
    channel.onmessage?.({ data: control('file_offer', offer, { offer }) })

    session.acceptRemoteOffer()

    const rejectMessage = JSON.parse(String(channel.sent.at(-1)))
    expect(rejectMessage).toEqual(expect.objectContaining({
      type: 'file_reject',
      fileId: offer.fileId,
      transferId: offer.transferId,
    }))
    expect(rejectMessage.reason).toMatch(/limit/)
    expect(statuses.at(-1)).toEqual({
      status: 'failed',
      reason: 'File exceeds this connection limit (3 B).',
    })
  })

  it('rejects inconsistent remote file metadata before exposing an offer', async () => {
    const { channel, onError } = createSession({ maxFileSize: 20, chunkSize: 3 })
    const offer = {
      fileId: 'file_bad',
      transferId: 'transfer_bad',
      name: 'bad.txt',
      size: 6,
      mime: 'text/plain',
      chunkSize: 3,
      totalChunks: 3,
    }

    channel.onmessage?.({ data: control('file_offer', offer, { offer }) })

    await vi.waitFor(() => expect(onError).toHaveBeenCalledWith('Invalid file offer.'))
  })

  it('sends a transfer_cancel control message when the user cancels', () => {
    const { channel, session, statuses } = createSession({ maxFileSize: 20, chunkSize: 3 })
    session.publishOffer(new File(['abcdefg'], 'note.txt'))

    session.cancelTransfer('user_cancel')

    const cancelMessage = JSON.parse(String(channel.sent.at(-1)))
    expect(cancelMessage.type).toBe('transfer_cancel')
    expect(cancelMessage.reason).toBe('user_cancel')
    expect(statuses.at(-1)).toEqual({ status: 'canceled', reason: undefined })
  })

  it('treats a peer transfer_cancel as a silent cancellation', () => {
    const { channel, statuses } = createSession({ maxFileSize: 20, chunkSize: 3 })
    const offer = {
      fileId: 'file_1',
      transferId: 'transfer_1',
      name: 'peer.txt',
      size: 6,
      mime: 'text/plain',
      chunkSize: 3,
      totalChunks: 2,
    }
    channel.onmessage?.({ data: control('file_offer', offer, { offer }) })

    channel.onmessage?.({ data: control('transfer_cancel', offer, { reason: 'user_cancel' }) })

    expect(statuses.at(-1)).toEqual({ status: 'canceled', reason: undefined })
  })

  it('cancels pending offers when the data channel session is closed', () => {
    const { channel, session, statuses } = createSession({ maxFileSize: 20, chunkSize: 3 })
    const localOffer = session.publishOffer(new File(['abc'], 'note.txt'))
    const remoteOffer = {
      fileId: 'file_peer',
      transferId: 'transfer_peer',
      name: 'peer.txt',
      size: 3,
      mime: 'text/plain',
      chunkSize: 3,
      totalChunks: 1,
    }
    channel.onmessage?.({ data: control('file_offer', remoteOffer, { offer: remoteOffer }) })

    session.close()

    expect(statuses).toContainEqual({ status: 'canceled', reason: undefined })
    expect(statuses.filter((entry) => entry.status === 'canceled')).toHaveLength(2)
    expect(channel.sent.map((message) => JSON.parse(String(message)).type)).toEqual(['file_offer'])
    session.cancelTransfer(localOffer.fileId)
    expect(channel.sent.map((message) => JSON.parse(String(message)).type)).toEqual(['file_offer'])
  })

  it('keeps pending offers visible as failed when the data channel is interrupted', () => {
    const { channel, session, statuses } = createSession({ maxFileSize: 20, chunkSize: 3 })
    session.publishOffer(new File(['abc'], 'note.txt'))
    const remoteOffer = {
      fileId: 'file_peer',
      transferId: 'transfer_peer',
      name: 'peer.txt',
      size: 3,
      mime: 'text/plain',
      chunkSize: 3,
      totalChunks: 1,
    }
    channel.onmessage?.({ data: control('file_offer', remoteOffer, { offer: remoteOffer }) })

    session.close({ status: 'failed', reason: 'File channel interrupted.' })

    expect(statuses.filter((entry) => entry.status === 'failed')).toHaveLength(2)
    expect(statuses).not.toContainEqual({ status: 'canceled', reason: undefined })
    expect(channel.sent.map((message) => JSON.parse(String(message)).type)).toEqual(['file_offer'])
  })

  it('keeps completed transfers out of the failed sweep when the session is replaced', async () => {
    const { channel, session, statuses, onComplete } = createSession({ maxFileSize: 20, chunkSize: 3 })
    const done = {
      fileId: 'file_done',
      transferId: 'transfer_done',
      name: 'done.txt',
      size: 1,
      mime: 'text/plain',
      chunkSize: 3,
      totalChunks: 1,
    }
    channel.onmessage?.({ data: control('file_offer', done, { offer: done }) })
    session.acceptRemoteOffers([done.fileId])
    channel.onmessage?.({ data: control('chunk_meta', done, { index: 0, offset: 0, size: 1 }) })
    channel.onmessage?.({ data: new Uint8Array([1]).buffer })
    channel.onmessage?.({ data: control('file_complete', done, { bytesSent: 1 }) })
    await vi.waitFor(() => expect(onComplete).toHaveBeenCalledWith(expect.objectContaining({ offer: done })))

    // A later channel swap fails what is still in flight, but a transfer that
    // already completed must not be rewritten to failed after the fact.
    session.publishOffer(new File(['abc'], 'pending.txt'))
    session.close({ status: 'failed', reason: 'File channel interrupted.' })

    expect(statuses.filter((entry) => entry.status === 'failed')).toHaveLength(1)
    expect(statuses).toContainEqual({ status: 'complete', reason: undefined })
  })

  it('ignores late control messages after the data channel session is closed', () => {
    const { channel, session, statuses, onRemoteOffer } = createSession({ maxFileSize: 20, chunkSize: 3 })
    const lateOffer = {
      fileId: 'file_late',
      transferId: 'transfer_late',
      name: 'late.txt',
      size: 3,
      mime: 'text/plain',
      chunkSize: 3,
      totalChunks: 1,
    }

    session.close()
    channel.onmessage?.({ data: control('file_offer', lateOffer, { offer: lateOffer }) })

    expect(onRemoteOffer).not.toHaveBeenCalled()
    expect(statuses).toEqual([])
  })

  it('sends a transfer_cancel control message when canceling a completed downloaded file', async () => {
    const { channel, session, statuses, onComplete } = createSession({ maxFileSize: 20, chunkSize: 3 })
    const offer = {
      fileId: 'file_1',
      transferId: 'transfer_1',
      name: 'peer.txt',
      size: 3,
      mime: 'text/plain',
      chunkSize: 3,
      totalChunks: 1,
    }
    channel.onmessage?.({ data: control('file_offer', offer, { offer }) })
    session.acceptRemoteOffer(offer.fileId)
    channel.onmessage?.({ data: control('chunk_meta', offer, { index: 0, offset: 0, size: 3 }) })
    channel.onmessage?.({ data: new TextEncoder().encode('abc').buffer })
    channel.onmessage?.({ data: control('file_complete', offer, { bytesSent: 3 }) })

    await vi.waitFor(() => expect(onComplete).toHaveBeenCalledWith(expect.objectContaining({ offer })))
    channel.sent.splice(0)

    session.cancelTransfer(offer.fileId)

    const cancelMessage = JSON.parse(String(channel.sent.at(-1)))
    expect(cancelMessage.type).toBe('transfer_cancel')
    expect(cancelMessage.fileId).toBe(offer.fileId)
    expect(statuses.at(-1)).toEqual({ status: 'canceled', reason: undefined })
  })

  it('applies peer transfer_cancel after a remote file was downloaded', async () => {
    const { channel, session, statuses, onComplete } = createSession({ maxFileSize: 20, chunkSize: 3 })
    const offer = {
      fileId: 'file_1',
      transferId: 'transfer_1',
      name: 'peer.txt',
      size: 3,
      mime: 'text/plain',
      chunkSize: 3,
      totalChunks: 1,
    }
    channel.onmessage?.({ data: control('file_offer', offer, { offer }) })
    session.acceptRemoteOffer(offer.fileId)
    channel.onmessage?.({ data: control('chunk_meta', offer, { index: 0, offset: 0, size: 3 }) })
    channel.onmessage?.({ data: new TextEncoder().encode('abc').buffer })
    channel.onmessage?.({ data: control('file_complete', offer, { bytesSent: 3 }) })

    await vi.waitFor(() => expect(onComplete).toHaveBeenCalledWith(expect.objectContaining({ offer })))
    channel.onmessage?.({ data: control('transfer_cancel', offer, { reason: 'user_cancel' }) })

    expect(statuses.at(-1)).toEqual({ status: 'canceled', reason: undefined })
  })

  it('applies peer transfer_cancel after a local file was sent', async () => {
    const { channel, session, statuses } = createSession({ maxFileSize: 20, chunkSize: 3 })
    const offer = session.publishOffer(new File(['abc'], 'note.txt'))

    channel.onmessage?.({ data: control('file_accept', offer) })
    await vi.waitFor(() => expect(statuses.at(-1)).toEqual({ status: 'complete', reason: undefined }))

    channel.onmessage?.({ data: control('transfer_cancel', offer, { reason: 'user_cancel' }) })

    expect(statuses.at(-1)).toEqual({ status: 'canceled', reason: undefined })
  })

  it('drops the inbound backlog and fails once the queue cap is exceeded', async () => {
    const { channel, session, onError } = createSession({ maxFileSize: 20, chunkSize: 3 })
    const offer = {
      fileId: 'file_flood',
      transferId: 'transfer_flood',
      name: 'flood.txt',
      size: 6,
      mime: 'text/plain',
      chunkSize: 3,
      totalChunks: 2,
    }
    channel.onmessage?.({ data: control('file_offer', offer, { offer }) })
    session.acceptRemoteOffer()

    // Hold the first chunk's checksum so every later frame queues behind it.
    let resolveDigest: (value: ArrayBuffer) => void = () => undefined
    const digestSpy = vi.spyOn(crypto.subtle, 'digest').mockImplementationOnce(
      () => new Promise<ArrayBuffer>((resolve) => {
        resolveDigest = resolve
      }),
    )

    try {
      channel.onmessage?.({
        data: control('chunk_meta', offer, {
          index: 0,
          offset: 0,
          size: 3,
          sha256: 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
        }),
      })
      channel.onmessage?.({ data: new TextEncoder().encode('abc').buffer })

      for (let index = 0; index < 300; index += 1) {
        channel.onmessage?.({ data: JSON.stringify({ protocolVersion: 1, messageId: `msg_${index}`, type: 'text_message', text: 'spam' }) })
      }

      expect(onError).toHaveBeenCalledTimes(1)
      expect(String(onError.mock.calls[0][0])).toMatch(/queue/i)
      // The real property is boundedness: a peer can never grow the backlog
      // past the cap, however many frames it pushes while a chunk verifies.
      const pending = (session as unknown as { pendingInbound: unknown[] }).pendingInbound
      expect(pending.length).toBeLessThanOrEqual(256)
    } finally {
      digestSpy.mockRestore()
      resolveDigest(new ArrayBuffer(0))
    }
  })

  it('stops a sender blocked on buffer space when the session closes', async () => {
    const { channel, session, onError } = createSession({ maxFileSize: 20, chunkSize: 3 })
    const offer = session.publishOffer(new File(['abc'], 'note.txt'))
    channel.bufferedAmount = 16 * 1024 * 1024

    channel.onmessage?.({ data: control('file_accept', offer) })
    await vi.waitFor(() => expect(channel.listenerCount('bufferedamountlow')).toBe(1))

    session.close()
    await Promise.resolve()

    expect(channel.listenerCount('bufferedamountlow')).toBe(0)
    expect(channel.listenerCount('close')).toBe(0)
    expect(channel.listenerCount('error')).toBe(0)
    expect(onError).not.toHaveBeenCalled()
  })

  it('retries the current local file with a new transfer attempt', () => {
    const { channel, session, statuses } = createSession({ maxFileSize: 20, chunkSize: 3 })
    const firstOffer = session.publishOffer(new File(['abcdefg'], 'note.txt'))

    const retryOffer = session.retryCurrentFile()

    expect(retryOffer.fileId).not.toBe(firstOffer.fileId)
    expect(retryOffer.transferId).not.toBe(firstOffer.transferId)
    expect(retryOffer.name).toBe(firstOffer.name)
    expect(channel.sent).toHaveLength(2)
    expect(JSON.parse(String(channel.sent.at(-1)))).toEqual(expect.objectContaining({
      type: 'file_offer',
      fileId: retryOffer.fileId,
      transferId: retryOffer.transferId,
    }))
    expect(statuses.at(-1)).toEqual({ status: 'waiting_accept', reason: undefined })
  })

  it('fails clearly when retrying without a retained local file', () => {
    const { session } = createSession({ maxFileSize: 20, chunkSize: 3 })

    expect(() => session.retryCurrentFile()).toThrow(/No current file/)
  })

  it('assembles a received file by chunk index after download is accepted', async () => {
    const { channel, session, onComplete } = createSession({ maxFileSize: 20, chunkSize: 3 })
    const offer = {
      fileId: 'file_1',
      transferId: 'transfer_1',
      name: 'peer.txt',
      size: 6,
      mime: 'text/plain',
      chunkSize: 3,
      totalChunks: 2,
    }
    channel.onmessage?.({ data: control('file_offer', offer, { offer }) })
    session.acceptRemoteOffer()

    channel.onmessage?.({ data: control('chunk_meta', offer, { index: 0, offset: 0, size: 3 }) })
    channel.onmessage?.({ data: new TextEncoder().encode('abc').buffer })
    channel.onmessage?.({ data: control('chunk_meta', offer, { index: 1, offset: 3, size: 3 }) })
    channel.onmessage?.({ data: new TextEncoder().encode('def').buffer })
    channel.onmessage?.({ data: control('file_complete', offer, { bytesSent: 6 }) })

    await vi.waitFor(() => expect(onComplete).toHaveBeenCalledTimes(1))
    const received = onComplete.mock.calls[0][0]
    await expect(received.blob.text()).resolves.toBe('abcdef')
  })

  it('keeps later chunks ordered while the previous chunk checksum is still verifying', async () => {
    const { channel, session, onComplete, onError } = createSession({ maxFileSize: 20, chunkSize: 3 })
    const offer = {
      fileId: 'file_ordered',
      transferId: 'transfer_ordered',
      name: 'ordered.txt',
      size: 6,
      mime: 'text/plain',
      chunkSize: 3,
      totalChunks: 2,
    }
    channel.onmessage?.({ data: control('file_offer', offer, { offer }) })
    session.acceptRemoteOffer()

    const firstDigest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode('abc'))
    let resolveDigest: (value: ArrayBuffer) => void = () => undefined
    const digestSpy = vi.spyOn(crypto.subtle, 'digest').mockImplementationOnce(
      () => new Promise<ArrayBuffer>((resolve) => {
        resolveDigest = resolve
      }),
    )

    try {
      channel.onmessage?.({
        data: control('chunk_meta', offer, {
          index: 0,
          offset: 0,
          size: 3,
          sha256: 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
        }),
      })
      channel.onmessage?.({ data: new TextEncoder().encode('abc').buffer })
      channel.onmessage?.({ data: control('chunk_meta', offer, { index: 1, offset: 3, size: 3 }) })
      channel.onmessage?.({ data: new TextEncoder().encode('def').buffer })
      channel.onmessage?.({ data: control('file_complete', offer, { bytesSent: 6 }) })

      expect(onComplete).not.toHaveBeenCalled()
      expect(onError).not.toHaveBeenCalled()

      resolveDigest(firstDigest)

      await vi.waitFor(() => expect(onComplete).toHaveBeenCalledTimes(1))
      expect(onError).not.toHaveBeenCalled()
      await expect(onComplete.mock.calls[0][0].blob.text()).resolves.toBe('abcdef')
    } finally {
      digestSpy.mockRestore()
    }
  })

  it('rejects an unexpected chunk index before accepting binary payload', async () => {
    const { channel, session, statuses, onError } = createSession({ maxFileSize: 20, chunkSize: 3 })
    const offer = {
      fileId: 'file_1',
      transferId: 'transfer_1',
      name: 'peer.txt',
      size: 6,
      mime: 'text/plain',
      chunkSize: 3,
      totalChunks: 2,
    }
    channel.onmessage?.({ data: control('file_offer', offer, { offer }) })
    session.acceptRemoteOffer()

    channel.onmessage?.({ data: control('chunk_meta', offer, { index: 1, offset: 0, size: 3 }) })

    await vi.waitFor(() => expect(onError).toHaveBeenCalledWith('Unexpected chunk index.'))
    expect(statuses.at(-1)).toEqual({ status: 'failed', reason: 'Unexpected chunk index.' })
    expect(JSON.parse(String(channel.sent.at(-1)))).toEqual(expect.objectContaining({
      type: 'transfer_error',
      reason: 'Unexpected chunk index.',
    }))
  })

  it('rejects chunk metadata that exceeds total chunks', async () => {
    const { channel, session, onError } = createSession({ maxFileSize: 20, chunkSize: 3 })
    const offer = {
      fileId: 'file_1',
      transferId: 'transfer_1',
      name: 'peer.txt',
      size: 6,
      mime: 'text/plain',
      chunkSize: 3,
      totalChunks: 2,
    }
    channel.onmessage?.({ data: control('file_offer', offer, { offer }) })
    session.acceptRemoteOffer()

    channel.onmessage?.({ data: control('chunk_meta', offer, { index: 2, offset: 0, size: 3 }) })

    await vi.waitFor(() => expect(onError).toHaveBeenCalledWith('Chunk index exceeds file metadata.'))
  })

  it('rejects negative chunk indexes', async () => {
    const { channel, session, onError } = createSession({ maxFileSize: 20, chunkSize: 3 })
    const offer = {
      fileId: 'file_1',
      transferId: 'transfer_1',
      name: 'peer.txt',
      size: 6,
      mime: 'text/plain',
      chunkSize: 3,
      totalChunks: 2,
    }
    channel.onmessage?.({ data: control('file_offer', offer, { offer }) })
    session.acceptRemoteOffer()

    channel.onmessage?.({ data: control('chunk_meta', offer, { index: -1, offset: 0, size: 3 }) })

    await vi.waitFor(() => expect(onError).toHaveBeenCalledWith('Invalid chunk index.'))
  })

  it('rejects duplicate chunk indexes', async () => {
    const { channel, session, onError } = createSession({ maxFileSize: 20, chunkSize: 3 })
    const offer = {
      fileId: 'file_1',
      transferId: 'transfer_1',
      name: 'peer.txt',
      size: 6,
      mime: 'text/plain',
      chunkSize: 3,
      totalChunks: 2,
    }
    channel.onmessage?.({ data: control('file_offer', offer, { offer }) })
    session.acceptRemoteOffer()
    channel.onmessage?.({ data: control('chunk_meta', offer, { index: 0, offset: 0, size: 3 }) })
    channel.onmessage?.({ data: new TextEncoder().encode('abc').buffer })

    channel.onmessage?.({ data: control('chunk_meta', offer, { index: 0, offset: 3, size: 3 }) })

    await vi.waitFor(() => expect(onError).toHaveBeenCalledWith('Unexpected chunk index.'))
  })

  it('does not complete the same transfer attempt twice', async () => {
    const { channel, session, statuses, onComplete, onError } = createSession({ maxFileSize: 20, chunkSize: 3 })
    const offer = {
      fileId: 'file_1',
      transferId: 'transfer_1',
      name: 'peer.txt',
      size: 3,
      mime: 'text/plain',
      chunkSize: 3,
      totalChunks: 1,
    }
    channel.onmessage?.({ data: control('file_offer', offer, { offer }) })
    session.acceptRemoteOffer()
    channel.onmessage?.({ data: control('chunk_meta', offer, { index: 0, offset: 0, size: 3 }) })
    channel.onmessage?.({ data: new TextEncoder().encode('abc').buffer })
    channel.onmessage?.({ data: control('file_complete', offer, { bytesSent: 3 }) })

    await vi.waitFor(() => expect(onComplete).toHaveBeenCalledTimes(1))
    channel.onmessage?.({ data: control('file_complete', offer, { bytesSent: 3 }) })
    channel.onmessage?.({ data: control('chunk_meta', offer, { index: 0, offset: 0, size: 3 }) })

    expect(onComplete).toHaveBeenCalledTimes(1)
    expect(onError).not.toHaveBeenCalled()
    expect(statuses.at(-1)).toEqual({ status: 'complete', reason: undefined })
  })

  it('does not move a completed sender back to transferring on a stale accept', async () => {
    const { channel, session, statuses } = createSession({ maxFileSize: 20, chunkSize: 3 })
    const offer = session.publishOffer(new File(['abc'], 'note.txt'))

    channel.onmessage?.({ data: control('file_accept', offer) })
    await vi.waitFor(() => expect(statuses.at(-1)).toEqual({ status: 'complete', reason: undefined }))

    channel.onmessage?.({ data: control('file_accept', offer) })

    expect(statuses.at(-1)).toEqual({ status: 'complete', reason: undefined })
    expect(statuses.filter((entry) => entry.status === 'transferring')).toHaveLength(1)
  })

  it('does not move a rejected transfer attempt into transferring on a stale accept', async () => {
    const { channel, session, statuses } = createSession({ maxFileSize: 20, chunkSize: 3 })
    const offer = session.publishOffer(new File(['abc'], 'note.txt'))

    channel.onmessage?.({ data: control('file_reject', offer, { reason: 'Peer declined.' }) })
    channel.onmessage?.({ data: control('file_accept', offer) })

    expect(statuses.at(-1)).toEqual({ status: 'rejected', reason: 'Peer declined.' })
    expect(statuses.some((entry) => entry.status === 'transferring')).toBe(false)
  })

  it('sends and receives in-band text messages without touching file state', () => {
    const { channel, session, onTextMessage, statuses } = createSession({ maxFileSize: 20, chunkSize: 3 })

    session.sendText('https://example.com')
    channel.onmessage?.({
      data: JSON.stringify({ protocolVersion: 1, messageId: 'msg_in', type: 'text_message', text: 'pong' }),
    })

    expect(onTextMessage).toHaveBeenCalledWith(expect.objectContaining({ direction: 'outgoing', text: 'https://example.com' }))
    expect(onTextMessage).toHaveBeenCalledWith(expect.objectContaining({ direction: 'incoming', text: 'pong' }))
    expect(JSON.parse(String(channel.sent[0])).type).toBe('text_message')
    expect(statuses).toHaveLength(0)
  })

  it('keeps the sender messageId on both text directions so a message can be recalled', () => {
    const { channel, session, onTextMessage } = createSession({ maxFileSize: 20, chunkSize: 3 })

    session.sendText('draft')
    const sent = JSON.parse(String(channel.sent[0]))
    channel.onmessage?.({
      data: JSON.stringify({ protocolVersion: 1, messageId: 'msg_peer', type: 'text_message', text: 'reply' }),
    })

    expect(onTextMessage).toHaveBeenCalledWith(
      expect.objectContaining({ id: sent.messageId, direction: 'outgoing', text: 'draft' }),
    )
    expect(onTextMessage).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'msg_peer', direction: 'incoming', text: 'reply' }),
    )
  })

  it('retracts a text message for both sides', () => {
    const { channel, session, onRecalled } = createSession({ maxFileSize: 20, chunkSize: 3 })

    session.recall('text', 'msg_1')

    expect(JSON.parse(String(channel.sent.at(-1)))).toMatchObject({
      type: 'message_recall',
      target: 'text',
      targetId: 'msg_1',
    })
    expect(onRecalled).toHaveBeenCalledWith({ target: 'text', targetId: 'msg_1' })
  })

  it('stops an in-flight file and drops it from local state when the sender recalls it', () => {
    const { channel, session, onRecalled } = createSession({ maxFileSize: 20, chunkSize: 3 })
    const offer = session.publishOffer(new File(['abcdefg'], 'note.txt'))

    session.recall('file', offer.fileId)

    // A recall is one control message: the peer removes the bubble instead of
    // receiving a separate transfer_cancel.
    expect(channel.sent.map((message) => JSON.parse(String(message)).type)).toEqual(['file_offer', 'message_recall'])
    expect(onRecalled).toHaveBeenCalledWith({ target: 'file', targetId: offer.fileId })
    expect(() => session.retryFile(offer.fileId)).toThrow(/No current file/)
  })

  it('drops a peer file when the peer recalls it', () => {
    const { channel, session, onRecalled } = createSession({ maxFileSize: 20, chunkSize: 3 })
    const offer = {
      fileId: 'file_1',
      transferId: 'transfer_1',
      name: 'peer.txt',
      size: 6,
      mime: 'text/plain',
      chunkSize: 3,
      totalChunks: 2,
    }
    channel.onmessage?.({ data: control('file_offer', offer, { offer }) })

    channel.onmessage?.({
      data: JSON.stringify({
        protocolVersion: 1,
        messageId: 'msg_recall',
        type: 'message_recall',
        target: 'file',
        targetId: offer.fileId,
      }),
    })

    expect(onRecalled).toHaveBeenCalledWith({ target: 'file', targetId: offer.fileId })
    expect(() => session.acceptRemoteOffer(offer.fileId)).toThrow(/No peer file is available/)
  })

  it('refuses to send text that exceeds the in-band cap', () => {
    const { session } = createSession({ maxFileSize: 20, chunkSize: 3 })

    expect(() => session.sendText('x'.repeat(64 * 1024 + 1))).toThrow(/too large/)
  })

  it('rejects an incoming chunk whose advertised checksum does not match', () => {
    const { channel, session, onError, statuses } = createSession({ maxFileSize: 20, chunkSize: 3 })
    const offer = {
      fileId: 'file_sum',
      transferId: 'transfer_sum',
      name: 'sum.txt',
      size: 3,
      mime: 'text/plain',
      chunkSize: 3,
      totalChunks: 1,
    }
    channel.onmessage?.({ data: control('file_offer', offer, { offer }) })
    session.acceptRemoteOffer()

    channel.onmessage?.({
      data: control('chunk_meta', offer, { index: 0, offset: 0, size: 3, sha256: 'a'.repeat(64) }),
    })
    channel.onmessage?.({ data: new TextEncoder().encode('abc').buffer })

    return vi.waitFor(() => {
      expect(onError).toHaveBeenCalledWith(expect.stringMatching(/checksum mismatch/i))
    }).then(() => {
      expect(statuses.at(-1)).toEqual({ status: 'failed', reason: 'Chunk checksum mismatch.' })
    })
  })

  it('accepts a chunk whose advertised checksum matches', async () => {
    const { channel, session, onComplete, progress } = createSession({ maxFileSize: 20, chunkSize: 3 })
    const offer = {
      fileId: 'file_ok',
      transferId: 'transfer_ok',
      name: 'ok.txt',
      size: 3,
      mime: 'text/plain',
      chunkSize: 3,
      totalChunks: 1,
    }
    channel.onmessage?.({ data: control('file_offer', offer, { offer }) })
    session.acceptRemoteOffer()

    channel.onmessage?.({
      data: control('chunk_meta', offer, {
        index: 0,
        offset: 0,
        size: 3,
        sha256: 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
      }),
    })
    // chunk_meta now verifies asynchronously; let it land before the binary frame.
    await vi.waitFor(() => expect(channel.sent.map((message) => JSON.parse(String(message)).type)).toContain('file_accept'))
    channel.onmessage?.({ data: new TextEncoder().encode('abc').buffer })
    // The binary frame is digested asynchronously too; wait for progress before completing.
    await vi.waitFor(() => expect(progress).toHaveBeenCalledWith(expect.objectContaining({ percent: 100 })))
    channel.onmessage?.({ data: control('file_complete', offer, { bytesSent: 3 }) })

    await vi.waitFor(() => expect(onComplete).toHaveBeenCalledWith(expect.objectContaining({ offer })))
  })
})
