import {
  ChunkMetaMessage,
  createControlMessage,
  createFileOffer,
  createRecallMessage,
  createTextMessage,
  FileOffer,
  isBinaryPayload,
  isSha256Hex,
  isTextTooLarge,
  parseControlMessage,
  sha256Hex,
  TextMessage,
  TransferControlMessage,
} from './protocol'

export type TransferStatus = 'idle' | 'offered' | 'waiting_accept' | 'transferring' | 'complete' | 'failed' | 'rejected' | 'canceled'

interface TransferSessionOptions {
  limits?: TransferLimits | (() => TransferLimits | undefined)
  autoAcceptRemoteOffers?: boolean
  deferAutoAcceptedRemoteOfferVisibility?: boolean
  onLocalOffer: (item: TransferFileItem) => void
  onRemoteOffer: (item: TransferFileItem) => void
  onStatus: (fileId: string, status: TransferStatus, reason?: string) => void
  onProgress: (fileId: string, progress: TransferProgress) => void
  onComplete: (result: ReceivedFile) => void
  onError: (fileId: string | null, reason: string) => void
  onChannelFailure?: (reason: string) => void
  onTextMessage?: (message: TextExchange) => void
  onRecalled?: (recall: TransferRecall) => void
}

export interface TextExchange {
  id: string
  direction: 'incoming' | 'outgoing'
  text: string
  atMs: number
}

/** A recalled chat entry: the sender removed its text message or file bubble. */
export interface TransferRecall {
  target: 'text' | 'file'
  targetId: string
}

export interface TransferLimits {
  maxFileSize: number
  chunkSize: number
}

export interface TransferProgress {
  percent: number
  bytesDone: number
  bytesTotal: number
  bytesPerSecond: number
  secondsRemaining: number | null
}

export interface TransferFileItem {
  offer: FileOffer
  status: TransferStatus
  progress: TransferProgress
  failure: string
  // When this entry joined the chat, so files and text can share one timeline.
  atMs: number
  // Set when the sender retracted the file; the chat shows a system note instead.
  recalled?: boolean
}

export interface ReceivedFile {
  offer: FileOffer
  blob: Blob
  url: string
}

export interface TransferSessionCloseOptions {
  status?: Extract<TransferStatus, 'canceled' | 'failed'>
  reason?: string
}

interface LocalTransfer {
  file: File
  offer: FileOffer
  status: TransferStatus
}

interface IncomingTransfer {
  offer: FileOffer
  chunks: ArrayBuffer[]
  receivedBytes: number
  receivedChunks: boolean[]
  expectedChunkIndex: number
  expectedChunk: ChunkMetaMessage | null
}

const maxBufferedAmount = 8 * 1024 * 1024
const lowBufferedAmount = 2 * 1024 * 1024
const browserMaxReadableFileSize = 512 * 1024 * 1024
// A peer must not be able to grow the inbound queue without limit while a
// chunk checksum is verifying. Control frames are tiny and at most one chunk
// payload is outstanding, so this is far above any honest burst.
const maxPendingInbound = 256

export const emptyTransferProgress: TransferProgress = {
  percent: 0,
  bytesDone: 0,
  bytesTotal: 0,
  bytesPerSecond: 0,
  secondsRemaining: null,
}

export class TransferSession {
  private readonly channel: RTCDataChannel
  private readonly options: TransferSessionOptions
  private localFiles = new Map<string, LocalTransfer>()
  private remoteOffers = new Map<string, TransferFileItem>()
  private incoming: IncomingTransfer | null = null
  private activeSendingFileId: string | null = null
  private activeReceivingFileId: string | null = null
  private transferStartedAt = 0
  private closedTransferIds = new Set<string>()
  private queuedRemoteAccepts: string[] = []
  private visibleRemoteOfferIds = new Set<string>()
  private pendingInbound: unknown[] = []
  private verifyingBinary = false
  private rejectBufferWait: ((reason: Error) => void) | null = null
  private closed = false

  constructor(channel: RTCDataChannel, options: TransferSessionOptions) {
    this.channel = channel
    this.options = options
    this.channel.binaryType = 'arraybuffer'
    this.channel.bufferedAmountLowThreshold = lowBufferedAmount
    this.channel.onmessage = (event) => {
      this.enqueueInbound(event.data)
    }
    this.channel.onerror = () => {
      if (this.closed) return
      const reason = 'File channel failed.'
      this.fail(null, reason)
      this.options.onChannelFailure?.(reason)
    }
  }

  publishOffer(file: File): FileOffer {
    if (this.closed) throw new Error('File channel is closed.')
    if (this.channel.readyState !== 'open') throw new Error('File channel is not open.')
    const maxFileSize = this.maxFileSize()
    if (file.size > maxFileSize) {
      throw new Error(`File is too large for this connection limit (${formatBytes(maxFileSize)}).`)
    }
    const offer = createFileOffer(file, this.chunkSize())
    this.closedTransferIds.delete(offer.transferId)
    this.localFiles.set(offer.fileId, { file, offer, status: 'waiting_accept' })
    this.options.onLocalOffer({ offer, status: 'waiting_accept', progress: emptyProgressFor(offer.size), failure: '', atMs: Date.now() })
    this.options.onStatus(offer.fileId, 'waiting_accept')
    this.sendControl(createControlMessage('file_offer', offer, { offer }))
    return offer
  }

  publishOffers(files: File[]): FileOffer[] {
    return files.map((file) => this.publishOffer(file))
  }

  sendText(text: string): void {
    if (this.closed) throw new Error('File channel is closed.')
    if (this.channel.readyState !== 'open') throw new Error('File channel is not open.')
    if (isTextTooLarge(text)) throw new Error('Text is too large for this connection. Send it as a file instead.')
    const message = createTextMessage(text)
    this.sendControl(message)
    this.options.onTextMessage?.({ id: message.messageId, direction: 'outgoing', text, atMs: Date.now() })
  }

  /**
   * Retract a chat entry on both sides: the text message or file bubble leaves
   * the conversation for sender and peer alike. In-flight bytes stop immediately.
   */
  recall(target: 'text' | 'file', targetId: string): void {
    if (this.closed) return
    if (target === 'file') this.forgetFile(targetId)
    if (this.channel.readyState === 'open') this.sendControl(createRecallMessage(target, targetId))
    this.options.onRecalled?.({ target, targetId })
  }

  retryFile(fileId: string): FileOffer {
    const current = this.localFiles.get(fileId)
    if (!current) throw new Error('No current file is available to retry.')
    this.localFiles.delete(fileId)
    return this.publishOffer(current.file)
  }

  retryCurrentFile(): FileOffer {
    const current = Array.from(this.localFiles.values()).at(-1)
    if (!current) throw new Error('No current file is available to retry.')
    return this.retryFile(current.offer.fileId)
  }

  acceptRemoteOffer(fileId?: string): void {
    const item = fileId ? this.remoteOffers.get(fileId) : Array.from(this.remoteOffers.values()).at(-1)
    if (!item) throw new Error('No peer file is available.')
    if (!canAcceptRemoteOfferStatus(item.status)) throw new Error('Peer file is not available for remote transfer.')
    if (this.incoming || this.activeSendingFileId) throw new Error('Another file transfer is already active.')
    this.startRemoteOffer(item)
  }

  acceptRemoteOffers(fileIds: string[]): void {
    for (const fileId of fileIds) {
      const item = this.remoteOffers.get(fileId)
      if (!item || !canAcceptRemoteOfferStatus(item.status)) continue
      if (this.incoming || this.activeSendingFileId) {
        this.queueRemoteAccept(fileId)
        continue
      }
      this.startRemoteOffer(item)
    }
  }

  private startRemoteOffer(item: TransferFileItem): void {
    const offer = item.offer
    const maxFileSize = this.maxFileSize()
    if (offer.size > maxFileSize) {
      const reason = `File exceeds this connection limit (${formatBytes(maxFileSize)}).`
      this.sendControl(createControlMessage('file_reject', offer, { reason }))
      this.markTerminal(offer.transferId)
      this.updateRemoteItem(offer.fileId, 'failed', reason)
      return
    }
    this.closedTransferIds.delete(offer.transferId)
    this.activeReceivingFileId = offer.fileId
    this.incoming = {
      offer,
      chunks: new Array(offer.totalChunks),
      receivedBytes: 0,
      receivedChunks: new Array(offer.totalChunks).fill(false),
      expectedChunkIndex: 0,
      expectedChunk: null,
    }
    this.transferStartedAt = performance.now()
    this.updateRemoteItem(offer.fileId, 'transferring')
    this.reportProgress(offer.fileId, 0, offer.size)
    this.sendControl(createControlMessage('file_accept', offer, {}))
  }

  cancelTransfer(fileId?: string, reason = 'user_cancel'): void {
    if (fileId && !this.findOffer(fileId) && reason === 'user_cancel') {
      reason = fileId
      fileId = undefined
    }
    const offer = this.findOffer(fileId)
    if (!offer) return
    if (this.channel.readyState === 'open') {
      this.sendControl(createControlMessage('transfer_cancel', offer, { reason }))
    }
    this.cancelLocalState(offer.fileId)
    this.markTerminal(offer.transferId)
    this.removeOffer(offer.fileId)
    this.emitStatus(offer.fileId, 'canceled')
    this.acceptNextQueuedRemote()
  }

  close(options: TransferSessionCloseOptions = {}): void {
    if (this.closed) return
    this.closed = true
    const status = options.status || 'canceled'
    const reason = status === 'failed' ? options.reason || 'File channel interrupted.' : undefined
    const staleFileIds = new Set([...this.localFiles.keys(), ...this.remoteOffers.keys()])
    staleFileIds.forEach((fileId) => {
      const offer = this.findOffer(fileId)
      if (!offer) return
      // A channel swap closes this session; transfers that already finished
      // keep their outcome instead of being rewritten to "failed" after the
      // fact, which would stain the chat history without anything happening.
      const settled = this.closedTransferIds.has(offer.transferId)
      this.cancelLocalState(fileId)
      this.markTerminal(offer.transferId)
      if (settled && status === 'failed') return
      if (status === 'canceled') this.removeOffer(fileId)
      this.emitStatus(fileId, status, reason)
    })
    this.incoming = null
    this.activeSendingFileId = null
    this.activeReceivingFileId = null
    this.queuedRemoteAccepts = []
    this.pendingInbound = []
    this.rejectBufferWait?.(new Error('File channel is closed.'))
    this.channel.onmessage = null
    this.channel.onerror = null
  }

  private enqueueInbound(data: unknown): void {
    if (this.closed) return
    if (this.verifyingBinary) {
      if (this.pendingInbound.length >= maxPendingInbound) {
        // Drop the backlog before failing so a peer cannot keep the queue
        // growing through the failure path.
        this.pendingInbound = []
        this.fail(this.incoming?.offer.fileId || this.activeReceivingFileId, 'Too many queued messages.')
        return
      }
      this.pendingInbound.push(data)
      return
    }
    if (!isBinaryPayload(data)) {
      void this.handleMessage(data)
      return
    }
    this.verifyingBinary = true
    void this.handleMessage(data)
      .catch(() => undefined)
      .finally(() => {
        this.verifyingBinary = false
        this.drainInbound()
      })
  }

  private drainInbound(): void {
    while (!this.closed && !this.verifyingBinary && this.pendingInbound.length > 0) {
      const next = this.pendingInbound.shift()
      if (next === undefined) return
      this.enqueueInbound(next)
    }
  }

  private async handleMessage(data: unknown): Promise<void> {
    if (this.closed) return
    try {
      if (isBinaryPayload(data)) {
        await this.handleBinary(data)
        return
      }
      if (typeof data !== 'string') throw new Error('Unsupported transfer payload.')
      const message = parseControlMessage(data)
      // Unknown future types are tolerated (see parseControlMessage).
      if (message === null) return
      await this.handleControl(message)
    } catch (error) {
      if (this.closed) return
      const fileId = this.incoming?.offer.fileId || this.activeSendingFileId || this.activeReceivingFileId
      this.fail(fileId, error instanceof Error ? error.message : 'Transfer failed.')
    }
  }

  private async handleControl(message: TransferControlMessage): Promise<void> {
    if (this.closed) return
    if (isTextMessage(message)) {
      this.options.onTextMessage?.({ id: message.messageId, direction: 'incoming', text: message.text, atMs: Date.now() })
      return
    }
    if (message.type === 'message_recall') {
      if (message.target === 'file') this.forgetFile(message.targetId)
      this.options.onRecalled?.({ target: message.target, targetId: message.targetId })
      return
    }
    if (message.type !== 'transfer_cancel' && this.closedTransferIds.has(message.transferId)) return
    switch (message.type) {
      case 'file_offer':
        this.closedTransferIds.delete(message.transferId)
        this.remoteOffers.set(message.offer.fileId, {
          offer: message.offer,
          status: 'offered',
          progress: emptyProgressFor(message.offer.size),
          failure: '',
          atMs: Date.now(),
        })
        if (!this.shouldDeferRemoteOfferVisibility()) this.revealRemoteOffer(message.offer.fileId)
        this.emitStatus(message.offer.fileId, 'offered')
        if (this.options.autoAcceptRemoteOffers) this.acceptRemoteOffers([message.offer.fileId])
        return
      case 'file_accept':
        await this.sendSelectedFile(message.fileId)
        return
      case 'file_reject':
        this.markTerminal(message.transferId)
        this.emitStatus(message.fileId, 'rejected', message.reason)
        return
      case 'chunk_meta':
        if (!this.incoming || message.fileId !== this.incoming.offer.fileId) throw new Error('Unexpected chunk metadata.')
        this.validateChunkMeta(message, this.incoming)
        this.incoming.expectedChunk = message
        return
      case 'file_complete':
        this.completeIncoming(message.bytesSent)
        return
      case 'transfer_error':
        this.markTerminal(message.transferId)
        this.cancelLocalState(message.fileId)
        this.emitStatus(message.fileId, 'failed', message.reason)
        this.options.onError(message.fileId, message.reason)
        this.acceptNextQueuedRemote()
        return
      case 'transfer_cancel':
        this.cancelLocalState(message.fileId)
        this.markTerminal(message.transferId)
        this.removeOffer(message.fileId)
        this.emitStatus(message.fileId, 'canceled')
        this.acceptNextQueuedRemote()
        return
    }
  }

  private async sendSelectedFile(fileId: string): Promise<void> {
    const local = this.localFiles.get(fileId)
    if (!local) throw new Error('Requested file is no longer available.')
    if (this.closed) throw new Error('File channel is closed.')
    if (this.incoming || (this.activeSendingFileId && this.activeSendingFileId !== fileId)) throw new Error('Another file transfer is already active.')

    const { file, offer } = local
    let offset = 0
    let index = 0
    this.closedTransferIds.delete(offer.transferId)
    this.activeSendingFileId = fileId
    this.transferStartedAt = performance.now()
    this.emitStatus(fileId, 'transferring')
    this.reportProgress(fileId, 0, file.size)

    while (offset < file.size) {
      if (this.closed || this.closedTransferIds.has(offer.transferId) || this.activeSendingFileId !== fileId) return
      const end = Math.min(offset + offer.chunkSize, file.size)
      const buffer = await file.slice(offset, end).arrayBuffer()
      if (this.closed || this.closedTransferIds.has(offer.transferId) || this.activeSendingFileId !== fileId) return
      const checksum = await sha256Hex(buffer)
      this.sendControl(createControlMessage('chunk_meta', offer, {
        index,
        offset,
        size: buffer.byteLength,
        ...(checksum ? { sha256: checksum } : {}),
      }))
      await this.waitForBuffer()
      if (this.closed || this.closedTransferIds.has(offer.transferId) || this.activeSendingFileId !== fileId) return
      this.channel.send(buffer)
      offset = end
      index += 1
      this.reportProgress(fileId, offset, file.size)
    }

    if (this.closed || this.closedTransferIds.has(offer.transferId) || this.activeSendingFileId !== fileId) return
    if (file.size === 0) this.reportProgress(fileId, 0, 0)
    this.sendControl(createControlMessage('file_complete', offer, { bytesSent: file.size }))
    this.activeSendingFileId = null
    this.markTerminal(offer.transferId)
    this.emitStatus(fileId, 'complete')
  }

  private async handleBinary(buffer: ArrayBuffer): Promise<void> {
    if (!this.incoming || !this.incoming.expectedChunk) throw new Error('Unexpected binary payload.')
    const expected = this.incoming.expectedChunk
    if (buffer.byteLength !== expected.size) throw new Error('Chunk size mismatch.')
    if (expected.sha256) {
      const actual = await sha256Hex(buffer)
      if (actual !== expected.sha256) throw new Error('Chunk checksum mismatch.')
    }
    this.incoming.chunks[expected.index] = buffer
    this.incoming.receivedChunks[expected.index] = true
    this.incoming.receivedBytes += buffer.byteLength
    this.incoming.expectedChunkIndex += 1
    this.incoming.expectedChunk = null
    this.reportProgress(this.incoming.offer.fileId, this.incoming.receivedBytes, this.incoming.offer.size)
  }

  private completeIncoming(bytesSent: number): void {
    if (!this.incoming) throw new Error('No incoming file to complete.')
    if (this.incoming.expectedChunk) throw new Error('Missing chunk payload.')
    if (bytesSent !== this.incoming.receivedBytes || this.incoming.receivedBytes !== this.incoming.offer.size) {
      throw new Error('Received file size mismatch.')
    }
    if (!this.incoming.receivedChunks.every(Boolean)) throw new Error('Missing file chunk.')

    const offer = this.incoming.offer
    const blob = new Blob(this.incoming.chunks, { type: offer.mime })
    const url = URL.createObjectURL(blob)
    this.reportProgress(offer.fileId, this.incoming.receivedBytes, offer.size)
    this.markTerminal(offer.transferId)
    this.emitStatus(offer.fileId, 'complete')
    this.revealRemoteOffer(offer.fileId)
    this.options.onComplete({ offer, blob, url })
    this.incoming = null
    this.activeReceivingFileId = null
    this.acceptNextQueuedRemote()
  }

  private sendControl(message: TransferControlMessage): void {
    this.channel.send(JSON.stringify(message))
  }

  private markTerminal(transferId: string): void {
    this.closedTransferIds.add(transferId)
  }

  private emitStatus(fileId: string, status: TransferStatus, reason?: string): void {
    const local = this.localFiles.get(fileId)
    if (local) local.status = status
    const remote = this.remoteOffers.get(fileId)
    if (remote) {
      remote.status = status
      remote.failure = reason || ''
    }
    this.options.onStatus(fileId, status, reason)
  }

  private updateRemoteItem(fileId: string, status: TransferStatus, reason?: string): void {
    const item = this.remoteOffers.get(fileId)
    if (!item) return
    item.status = status
    item.failure = reason || ''
    this.options.onStatus(fileId, status, reason)
  }

  private cancelLocalState(fileId: string): void {
    if (this.activeSendingFileId === fileId) this.activeSendingFileId = null
    if (this.incoming?.offer.fileId === fileId) this.incoming = null
    if (this.activeReceivingFileId === fileId) this.activeReceivingFileId = null
    this.queuedRemoteAccepts = this.queuedRemoteAccepts.filter((queuedFileId) => queuedFileId !== fileId)
  }

  private removeOffer(fileId: string): void {
    this.localFiles.delete(fileId)
    this.remoteOffers.delete(fileId)
    this.visibleRemoteOfferIds.delete(fileId)
  }

  /** Drop every local trace of a file whose chat entry was recalled. */
  private forgetFile(fileId: string): void {
    const offer = this.findOffer(fileId)
    if (!offer) {
      this.removeOffer(fileId)
      return
    }
    this.cancelLocalState(fileId)
    this.markTerminal(offer.transferId)
    this.removeOffer(fileId)
    this.acceptNextQueuedRemote()
  }

  private shouldDeferRemoteOfferVisibility(): boolean {
    return this.options.autoAcceptRemoteOffers === true && this.options.deferAutoAcceptedRemoteOfferVisibility === true
  }

  private revealRemoteOffer(fileId: string): void {
    const item = this.remoteOffers.get(fileId)
    if (!item || this.visibleRemoteOfferIds.has(fileId)) return
    this.visibleRemoteOfferIds.add(fileId)
    this.options.onRemoteOffer(item)
  }

  private queueRemoteAccept(fileId: string): void {
    if (this.queuedRemoteAccepts.includes(fileId)) return
    this.queuedRemoteAccepts.push(fileId)
  }

  private acceptNextQueuedRemote(): void {
    if (this.incoming || this.activeSendingFileId) return
    while (this.queuedRemoteAccepts.length > 0) {
      const fileId = this.queuedRemoteAccepts.shift()
      if (!fileId) return
      const item = this.remoteOffers.get(fileId)
      if (!item || !canAcceptRemoteOfferStatus(item.status)) continue
      this.startRemoteOffer(item)
      return
    }
  }

  private findOffer(fileId?: string): FileOffer | null {
    if (fileId) return this.localFiles.get(fileId)?.offer || this.remoteOffers.get(fileId)?.offer || null
    return this.localFiles.get(this.activeSendingFileId || '')?.offer
      || this.remoteOffers.get(this.activeReceivingFileId || '')?.offer
      || Array.from(this.remoteOffers.values()).at(-1)?.offer
      || Array.from(this.localFiles.values()).at(-1)?.offer
      || null
  }

  private validateChunkMeta(message: ChunkMetaMessage, incoming: IncomingTransfer): void {
    const offer = incoming.offer
    if (!Number.isSafeInteger(message.index) || message.index < 0) throw new Error('Invalid chunk index.')
    if (message.index >= offer.totalChunks) throw new Error('Chunk index exceeds file metadata.')
    if (message.index !== incoming.expectedChunkIndex) throw new Error('Unexpected chunk index.')
    if (incoming.receivedChunks[message.index]) throw new Error('Duplicate chunk index.')
    if (message.offset < 0 || message.size < 0) throw new Error('Invalid chunk bounds.')
    if (message.offset !== incoming.receivedBytes) throw new Error('Unexpected chunk offset.')
    if (message.offset + message.size > offer.size) throw new Error('Chunk exceeds file size.')
    if (message.size > offer.chunkSize) throw new Error('Chunk exceeds negotiated size.')
    if (message.sha256 != null && !isSha256Hex(message.sha256)) throw new Error('Invalid chunk checksum.')
    const isLastChunk = message.index === offer.totalChunks - 1
    if (!isLastChunk && message.size !== offer.chunkSize) throw new Error('Intermediate chunk size mismatch.')
    if (isLastChunk && message.offset + message.size !== offer.size) throw new Error('Final chunk size mismatch.')
  }

  private maxFileSize(): number {
    const serverLimit = this.currentLimits()?.maxFileSize
    if (serverLimit && serverLimit > 0) return Math.min(serverLimit, browserMaxReadableFileSize)
    return browserMaxReadableFileSize
  }

  private chunkSize(): number {
    const serverChunkSize = this.currentLimits()?.chunkSize
    if (serverChunkSize && serverChunkSize > 0) return serverChunkSize
    return 262_144
  }

  private currentLimits(): TransferLimits | undefined {
    const limits = this.options.limits
    return typeof limits === 'function' ? limits() : limits
  }

  private async waitForBuffer(): Promise<void> {
    if (this.channel.bufferedAmount <= maxBufferedAmount) return
    await new Promise<void>((resolve, reject) => {
      const cleanup = () => {
        this.channel.removeEventListener('bufferedamountlow', handleLow)
        this.channel.removeEventListener('close', handleUnavailable)
        this.channel.removeEventListener('error', handleUnavailable)
        if (this.rejectBufferWait === rejectWait) this.rejectBufferWait = null
      }
      const rejectWait = (reason: Error) => {
        cleanup()
        reject(reason)
      }
      const handleLow = () => {
        cleanup()
        resolve()
      }
      const handleUnavailable = () => rejectWait(new Error('File channel is closed.'))
      this.rejectBufferWait = rejectWait
      this.channel.addEventListener('bufferedamountlow', handleLow)
      this.channel.addEventListener('close', handleUnavailable)
      this.channel.addEventListener('error', handleUnavailable)
    })
  }

  private reportProgress(fileId: string, bytesDone: number, bytesTotal: number): void {
    const elapsedSeconds = this.transferStartedAt > 0 ? Math.max((performance.now() - this.transferStartedAt) / 1000, 0) : 0
    const bytesPerSecond = elapsedSeconds > 0 ? bytesDone / elapsedSeconds : 0
    const remainingBytes = Math.max(bytesTotal - bytesDone, 0)
    const progress = {
      percent: percent(bytesDone, bytesTotal),
      bytesDone,
      bytesTotal,
      bytesPerSecond,
      secondsRemaining: bytesPerSecond > 0 && remainingBytes > 0 ? remainingBytes / bytesPerSecond : null,
    }
    const remote = this.remoteOffers.get(fileId)
    if (remote) remote.progress = progress
    this.options.onProgress(fileId, progress)
  }

  private fail(fileId: string | null, reason: string): void {
    const offer = this.findOffer(fileId || undefined)
    if (offer && this.closedTransferIds.has(offer.transferId)) return
    if (offer) this.markTerminal(offer.transferId)
    if (offer) this.cancelLocalState(offer.fileId)
    if (offer) this.emitStatus(offer.fileId, 'failed', reason)
    this.options.onError(offer?.fileId || null, reason)
    if (offer && this.channel.readyState === 'open') {
      this.sendControl(createControlMessage('transfer_error', offer, { reason }))
    }
    this.acceptNextQueuedRemote()
  }
}

function isTextMessage(value: TransferControlMessage): value is TextMessage {
  return value.type === 'text_message'
}

function percent(done: number, total: number): number {
  if (total === 0) return 100
  return Math.min(100, Math.round((done / total) * 100))
}

function emptyProgressFor(bytesTotal: number): TransferProgress {
  return { ...emptyTransferProgress, bytesTotal }
}

function canAcceptRemoteOfferStatus(status: TransferStatus): boolean {
  return status === 'offered'
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}
