export interface FileOffer {
  fileId: string
  transferId: string
  name: string
  size: number
  mime: string
  chunkSize: number
  totalChunks: number
}

export interface ProtocolBase {
  protocolVersion: 1
  messageId: string
  transferId: string
  fileId: string
}

export interface FileOfferMessage extends ProtocolBase {
  type: 'file_offer'
  offer: FileOffer
}

export interface FileAcceptMessage extends ProtocolBase {
  type: 'file_accept'
}

export interface FileRejectMessage extends ProtocolBase {
  type: 'file_reject'
  reason: string
}

export interface ChunkMetaMessage extends ProtocolBase {
  type: 'chunk_meta'
  index: number
  offset: number
  size: number
  sha256?: string
}

export interface FileCompleteMessage extends ProtocolBase {
  type: 'file_complete'
  bytesSent: number
}

export interface TransferErrorMessage extends ProtocolBase {
  type: 'transfer_error'
  reason: string
}

export interface TransferCancelMessage extends ProtocolBase {
  type: 'transfer_cancel'
  reason?: string
}

export interface TextMessage {
  protocolVersion: 1
  messageId: string
  type: 'text_message'
  text: string
}

export interface MessageRecallMessage {
  protocolVersion: 1
  messageId: string
  type: 'message_recall'
  target: 'text' | 'file'
  targetId: string
}

export type TransferControlMessage =
  | FileOfferMessage
  | FileAcceptMessage
  | FileRejectMessage
  | ChunkMetaMessage
  | FileCompleteMessage
  | TransferErrorMessage
  | TransferCancelMessage
  | TextMessage
  | MessageRecallMessage

/** In-band text cap. Larger drafts are sent as a .txt file instead. */
export const MAX_TEXT_BYTES = 64 * 1024

/** Bounds the receiver's chunk bookkeeping before any array is allocated. */
export const MAX_TRANSFER_CHUNKS = 65_536
export const MAX_TRANSFER_CHUNK_BYTES = 16 * 1024 * 1024

export function utf8ByteLength(text: string): number {
  return new TextEncoder().encode(text).length
}

export function isTextTooLarge(text: string): boolean {
  return utf8ByteLength(text) > MAX_TEXT_BYTES
}

export function isSha256Hex(value: string): boolean {
  return /^[0-9a-f]{64}$/.test(value)
}

/** Returns '' when WebCrypto is unavailable (insecure context); callers then omit the field. */
export async function sha256Hex(buffer: ArrayBuffer): Promise<string> {
  if (!globalThis.crypto?.subtle) return ''
  const digest = await globalThis.crypto.subtle.digest('SHA-256', buffer)
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('')
}

export function createTextMessage(text: string): TextMessage {
  return {
    protocolVersion: 1,
    messageId: `msg_${crypto.randomUUID()}`,
    type: 'text_message',
    text,
  }
}

export function createRecallMessage(target: 'text' | 'file', targetId: string): MessageRecallMessage {
  return {
    protocolVersion: 1,
    messageId: `msg_${crypto.randomUUID()}`,
    type: 'message_recall',
    target,
    targetId,
  }
}

export function createFileOffer(file: File, chunkSize = 262_144): FileOffer {
  const fileId = `file_${crypto.randomUUID()}`
  return {
    fileId,
    transferId: `transfer_${crypto.randomUUID()}`,
    name: sanitizeFileName(file.name),
    size: file.size,
    mime: file.type || 'application/octet-stream',
    chunkSize,
    totalChunks: Math.max(1, Math.ceil(file.size / chunkSize)),
  }
}

export function sanitizeFileName(name: string): string {
  const cleaned = name.replace(/[\\/:*?"<>|]/g, '_').replace(/\.\.+/g, '.').trim()
  return cleaned || 'unnamed-file'
}

export function createControlMessage<T extends TransferControlMessage['type']>(
  type: T,
  offer: FileOffer,
  fields: Omit<Extract<TransferControlMessage, { type: T }>, keyof ProtocolBase | 'type'>,
): Extract<TransferControlMessage, { type: T }> {
  return {
    protocolVersion: 1,
    messageId: `msg_${crypto.randomUUID()}`,
    transferId: offer.transferId,
    fileId: offer.fileId,
    type,
    ...fields,
  } as Extract<TransferControlMessage, { type: T }>
}

export function parseControlMessage(value: string): TransferControlMessage | null {
  let parsed: unknown
  try {
    parsed = JSON.parse(value)
  } catch {
    throw new Error('Invalid transfer message JSON.')
  }

  if (!isRecord(parsed)) throw new Error('Invalid transfer message.')
  if (parsed.protocolVersion !== 1) throw new Error('Unsupported transfer protocol version.')
  if (typeof parsed.type !== 'string') throw new Error('Missing transfer message type.')
  // Unknown types are ignored, not fatal: the other side may already speak a
  // newer optional message type, and one unknown chat line must not kill a file transfer.
  if (parsed.type === 'text_message') {
    if (typeof parsed.messageId !== 'string') throw new Error('Missing transfer message identity.')
    if (typeof parsed.text !== 'string' || parsed.text.length === 0 || isTextTooLarge(parsed.text)) {
      throw new Error('Invalid text message.')
    }
    return parsed as unknown as TextMessage
  }
  if (parsed.type === 'message_recall') {
    if (typeof parsed.messageId !== 'string') throw new Error('Missing transfer message identity.')
    if (parsed.target !== 'text' && parsed.target !== 'file') throw new Error('Invalid recall target.')
    if (typeof parsed.targetId !== 'string' || parsed.targetId.length === 0 || parsed.targetId.length > 128) {
      throw new Error('Invalid recall target identity.')
    }
    return parsed as unknown as MessageRecallMessage
  }
  if (typeof parsed.messageId !== 'string' || typeof parsed.transferId !== 'string' || typeof parsed.fileId !== 'string') {
    throw new Error('Missing transfer message identity.')
  }

  switch (parsed.type) {
    case 'file_offer':
      if (!isFileOffer(parsed.offer)) throw new Error('Invalid file offer.')
      return parsed as unknown as FileOfferMessage
    case 'file_accept':
      return parsed as unknown as FileAcceptMessage
    case 'file_reject':
      if (typeof parsed.reason !== 'string') throw new Error('Invalid reject reason.')
      return parsed as unknown as FileRejectMessage
    case 'chunk_meta':
      if (!Number.isSafeInteger(parsed.index) || !Number.isSafeInteger(parsed.offset) || !Number.isSafeInteger(parsed.size)) {
        throw new Error('Invalid chunk metadata.')
      }
      if (parsed.sha256 != null && (typeof parsed.sha256 !== 'string' || !isSha256Hex(parsed.sha256))) {
        throw new Error('Invalid chunk checksum.')
      }
      return parsed as unknown as ChunkMetaMessage
    case 'file_complete':
      if (!Number.isSafeInteger(parsed.bytesSent)) throw new Error('Invalid completion message.')
      return parsed as unknown as FileCompleteMessage
    case 'transfer_error':
      if (typeof parsed.reason !== 'string') throw new Error('Invalid transfer error.')
      return parsed as unknown as TransferErrorMessage
    case 'transfer_cancel':
      // Kotlin encodes absent nullable fields as null; treat it like undefined.
      if (parsed.reason != null && typeof parsed.reason !== 'string') throw new Error('Invalid cancel reason.')
      return parsed as unknown as TransferCancelMessage
    default:
      return null
  }
}

export function isBinaryPayload(value: unknown): value is ArrayBuffer {
  return value instanceof ArrayBuffer
}

function isFileOffer(value: unknown): value is FileOffer {
  if (!isRecord(value)) return false
  const size = value.size
  const chunkSize = value.chunkSize
  const totalChunks = value.totalChunks
  return (
    typeof value.fileId === 'string' &&
    typeof value.transferId === 'string' &&
    typeof value.name === 'string' &&
    typeof value.mime === 'string' &&
    typeof size === 'number' &&
    typeof chunkSize === 'number' &&
    typeof totalChunks === 'number' &&
    Number.isSafeInteger(size) &&
    Number.isSafeInteger(chunkSize) &&
    Number.isSafeInteger(totalChunks) &&
    size >= 0 &&
    chunkSize > 0 &&
    chunkSize <= MAX_TRANSFER_CHUNK_BYTES &&
    totalChunks <= MAX_TRANSFER_CHUNKS &&
    totalChunks === Math.max(1, Math.ceil(size / chunkSize))
  )
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
