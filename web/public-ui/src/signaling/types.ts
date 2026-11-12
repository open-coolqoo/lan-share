export class ApiError extends Error {
  readonly code: string
  readonly status: number

  constructor(message: string, code: string, status: number) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
  }
}

export interface SignalingMessage {
  type: string
  roomId?: string
  from?: string
  to?: string
  deviceId?: string
  payload?: unknown
  error?: string
}

export interface SignalingSession {
  /** The cloud transport exposes a WebSocket; the local bridge polls instead. */
  socket?: WebSocket | null
  send: (message: SignalingMessage) => void
  close: () => void
}

export interface BridgeAuth {
  roomId: string
  deviceId: string
  sessionToken: string
}

export interface BridgeLimits {
  maxFileSize: number
  maxActiveTransfers?: number
  chunkSize: number
}

export interface BridgeInfo {
  mode: 'lan-bridge' | 'lan-local-service'
  code: string
  hostDeviceId: string
  hostDeviceLabel: string
  roomId: string
  pairingExpiresAt: string
  sessionExpiresAt: string | null
  limits: BridgeLimits
  guestUrl?: string
}

export interface LocalHostResponse extends BridgeAuth {
  mode: 'lan-local-service'
  hostToken: string
  code: string
  hostDeviceId: string
  hostDeviceLabel: string
  role: 'initiator'
  peerCount: number
  pairingExpiresAt: string
  sessionExpiresAt: string | null
  limits: BridgeLimits
  guestUrl: string
  qrPayload: string
  lanAddresses: string[]
}

export interface BridgeJoinResponse extends BridgeAuth {
  /** The host's opaque guest token; older responses only used this name. */
  token?: string
  role: 'peer'
  peerCount: number
  pairingExpiresAt: string
  sessionExpiresAt: string | null
  limits: BridgeLimits
  host: {
    deviceId: string
    deviceLabel: string
  }
}

export interface BridgeStatusResponse {
  roomId: string
  peerCount: number
  paired: boolean
  pairingExpiresAt: string
  sessionExpiresAt: string | null
  peers: Array<{
    deviceId: string
    deviceLabel: string
    deviceType: string
  }>
}

export interface BridgeHeartbeatResponse {
  ok: boolean
  lastSeenAt: string
  sessionExpiresAt: string | null
}
