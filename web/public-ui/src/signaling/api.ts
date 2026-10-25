import type { DeviceInfo } from '../device/label'
import { roomEndpoints, signalingEndpoint } from './endpoints'

const requestTimeoutMs = 10_000

export interface JoinRoomResponse {
  roomId: string
  deviceId: string
  role: 'initiator' | 'peer'
  peerCount: number
  sessionToken: string
  pairingExpiresAt: string
  sessionExpiresAt: string | null
  limits: {
    maxFileSize: number
    maxActiveTransfers: number
    chunkSize: number
  }
}

export interface CreateRoomResponse extends JoinRoomResponse {
  code: string
}

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

export async function joinRoom(roomKey: string, device: DeviceInfo, sessionToken?: string): Promise<JoinRoomResponse> {
  const response = await fetchWithTimeout(roomEndpoints.join, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      roomKey,
      deviceId: device.id,
      deviceLabel: device.label,
      deviceType: device.type,
      // Proves the join comes from the client that owns the live session when
      // the room already holds this deviceId; the server refuses a taken slot
      // without it so a second tab cannot silently take over a pairing.
      ...(sessionToken ? { sessionToken } : {}),
      capabilities: {
        webrtc: 'RTCPeerConnection' in window,
        fileSystemAccess: 'showSaveFilePicker' in window,
        maxChunkSize: 1_048_576,
        maxReadableFileSize: 2_147_483_648,
      },
    }),
  })
  if (!response.ok) throw await readAPIError(response, 'Connection failed.')
  return response.json()
}

export async function createRoom(device: DeviceInfo): Promise<CreateRoomResponse> {
  const response = await fetchWithTimeout(roomEndpoints.create, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      deviceId: device.id,
      deviceLabel: device.label,
      deviceType: device.type,
      capabilities: {
        webrtc: 'RTCPeerConnection' in window,
        fileSystemAccess: 'showSaveFilePicker' in window,
        maxChunkSize: 1_048_576,
        maxReadableFileSize: 2_147_483_648,
      },
    }),
  })
  if (!response.ok) throw await readAPIError(response, 'Connection code could not be created.')
  return response.json()
}

export interface CloseRoomRequest {
  roomId: string
  deviceId: string
  sessionToken: string
}

export interface RoomStatusResponse {
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

export interface RoomHeartbeatResponse {
  ok: boolean
  lastSeenAt: string
  sessionExpiresAt: string | null
}

export async function getRoomStatus(session: CloseRoomRequest): Promise<RoomStatusResponse> {
  const response = await fetchWithTimeout(roomEndpoints.status(session.roomId), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      deviceId: session.deviceId,
      sessionToken: session.sessionToken,
    }),
    keepalive: true,
  })
  if (!response.ok) throw await readAPIError(response, 'Room status failed.')
  return response.json()
}

// A heartbeat is a liveness probe, not a data request: waiting the full request
// timeout would let one silent beat masquerade as a long outage.
export const heartbeatTimeoutMs = 5_000

export async function sendRoomHeartbeat(session: CloseRoomRequest): Promise<RoomHeartbeatResponse> {
  const response = await fetchWithTimeout(roomEndpoints.heartbeat(session.roomId), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      deviceId: session.deviceId,
      sessionToken: session.sessionToken,
    }),
    keepalive: true,
  }, heartbeatTimeoutMs)
  if (!response.ok) throw await readAPIError(response, 'Room heartbeat failed.')
  return response.json()
}

export async function closeRoom(session: CloseRoomRequest): Promise<void> {
  const response = await fetchWithTimeout(roomEndpoints.close(session.roomId), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      deviceId: session.deviceId,
      sessionToken: session.sessionToken,
    }),
    keepalive: true,
  })
  if (!response.ok) throw await readAPIError(response, 'Room close failed.')
}

export function closeRoomBeacon(session: CloseRoomRequest): void {
  const body = JSON.stringify({
    deviceId: session.deviceId,
    sessionToken: session.sessionToken,
  })
  if (navigator.sendBeacon) {
    const sent = navigator.sendBeacon(roomEndpoints.close(session.roomId), new Blob([body], { type: 'application/json' }))
    if (sent) return
  }
  void closeRoom(session).catch(() => undefined)
}

export interface SignalingSession {
  socket: WebSocket
  send: (message: SignalingMessage) => void
  close: () => void
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

export type SignalingAuth = Pick<JoinRoomResponse, 'roomId' | 'deviceId' | 'sessionToken'>

export function openSignaling(joined: SignalingAuth, onMessage?: (message: SignalingMessage) => void, onDisconnect?: (event: CloseEvent) => void): Promise<SignalingSession> {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(signalingEndpoint())
    let authenticated = false
    let closedByClient = false
    let settled = false
    const timeout = window.setTimeout(() => {
      settled = true
      socket.close()
      reject(new Error('Signaling auth timed out.'))
    }, 5000)

    socket.addEventListener('open', () => {
      socket.send(JSON.stringify({
        type: 'auth',
        roomId: joined.roomId,
        deviceId: joined.deviceId,
        sessionToken: joined.sessionToken,
      }))
    })

    socket.addEventListener('message', (event) => {
      try {
        const msg = JSON.parse(String(event.data))
        if (msg.type === 'auth_ok') {
          authenticated = true
          window.clearTimeout(timeout)
          settled = true
          resolve({
            socket,
            send: (message) => socket.send(JSON.stringify(message)),
            close: () => {
              closedByClient = true
              socket.close()
            },
          })
          return
        }
        if (msg.type === 'error') {
          window.clearTimeout(timeout)
          settled = true
          reject(new Error(msg.error || 'Signaling failed.'))
          return
        }
        onMessage?.(msg)
      } catch {
        window.clearTimeout(timeout)
        settled = true
        reject(new Error('Invalid signaling message.'))
      }
    })

    socket.addEventListener('error', () => {
      window.clearTimeout(timeout)
      settled = true
      reject(new Error('Signaling service is unreachable.'))
    })

    socket.addEventListener('close', (event) => {
      window.clearTimeout(timeout)
      if (!authenticated && !settled) {
        settled = true
        reject(new Error('Signaling connection closed before authentication.'))
        return
      }
      if (authenticated && !closedByClient) onDisconnect?.(event)
    })
  })
}

async function fetchWithTimeout(input: RequestInfo | URL, init?: RequestInit, timeoutMs = requestTimeoutMs): Promise<Response> {
  const controller = new AbortController()
  const timer = globalThis.setTimeout(() => controller.abort(), timeoutMs)
  try {
    return await fetch(input, { ...init, signal: controller.signal })
  } catch (error) {
    if (error instanceof Error && error.name === 'AbortError') {
      throw new Error('Request timed out.')
    }
    throw error
  } finally {
    globalThis.clearTimeout(timer)
  }
}

async function readAPIError(response: Response, fallback: string): Promise<ApiError> {
  const body = await response.json().catch(() => null)
  const code = typeof body?.code === 'string' && body.code.trim() ? body.code : `http_${response.status}`
  const message = typeof body?.message === 'string' && body.message.trim() ? body.message : fallback
  return new ApiError(message, code, response.status)
}
