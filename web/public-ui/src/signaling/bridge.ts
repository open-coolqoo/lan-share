import type { DeviceInfo } from '../device/label'
import { normalizeCode } from '../pairing/code'
import type {
  BridgeAuth,
  BridgeHeartbeatResponse,
  BridgeInfo,
  BridgeJoinResponse,
  BridgeStatusResponse,
  LocalHostResponse,
  SignalingMessage,
  SignalingSession,
} from './types'

const bridgeBase = '/__bridge__'
const requestTimeoutMs = 10_000
const pollRetryDelayMs = 500
const maxPollFailures = 3

async function postJson<T>(path: string, body: unknown, keepalive = false): Promise<T> {
  const controller = new AbortController()
  const timer = globalThis.setTimeout(() => controller.abort(), requestTimeoutMs)
  try {
    const response = await fetch(`${bridgeBase}${path}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      signal: controller.signal,
      keepalive,
    })
    if (!response.ok) {
      const error = await response.json().catch(() => null)
      throw new BridgeError(error?.message || 'Local device request failed.', error?.code || `http_${response.status}`)
    }
    if (response.status === 204) return {} as T
    return response.json() as Promise<T>
  } catch (error) {
    if (error instanceof BridgeError) throw error
    if (error instanceof Error && error.name === 'AbortError') throw new BridgeError('Request timed out.', 'timeout')
    throw new BridgeError('The local device is unreachable.', 'unreachable')
  } finally {
    globalThis.clearTimeout(timer)
  }
}

export class BridgeError extends Error {
  readonly code: string

  constructor(message: string, code: string) {
    super(message)
    this.name = 'BridgeError'
    this.code = code
  }
}

/** The native host serves this page; a non-hosted page has no bridge. */
export async function fetchBridgeInfo(): Promise<BridgeInfo | null> {
  try {
    const response = await fetch(`${bridgeBase}/info`, { cache: 'no-store' })
    if (!response.ok) return null
    const info = await response.json() as BridgeInfo
    return info?.mode === 'lan-bridge' || info?.mode === 'lan-local-service' ? info : null
  } catch {
    return null
  }
}

const bridgeTokenPrefix = 'lanshare.bridge.token.'

export function readBridgeToken(code: string): string | undefined {
  try {
    return sessionStorage.getItem(`${bridgeTokenPrefix}${normalizeCode(code)}`) || undefined
  } catch {
    return undefined
  }
}

export function rememberBridgeToken(code: string, token: string): void {
  try {
    sessionStorage.setItem(`${bridgeTokenPrefix}${normalizeCode(code)}`, token)
  } catch {
    // Storage can be unavailable; the first join still works, only a reload
    // cannot resume the existing guest slot.
  }
}

export function hostLocalService(
  hostToken: string,
  device: DeviceInfo,
  refresh = false,
): Promise<LocalHostResponse> {
  return postJson<LocalHostResponse>('/host', {
    token: hostToken,
    deviceId: device.id,
    deviceLabel: device.label,
    ...(refresh ? { refresh: true } : {}),
  })
}

export async function joinBridgeHost(
  code: string,
  device: DeviceInfo,
  token?: string,
): Promise<BridgeJoinResponse> {
  const joined = await postJson<BridgeJoinResponse>('/join', {
    code: normalizeCode(code),
    deviceId: device.id,
    deviceLabel: device.label,
    deviceType: device.type,
    ...(token ? { token } : {}),
  })
  return {
    ...joined,
    sessionToken: joined.sessionToken || joined.token || '',
  }
}

/**
 * Polling signaling for the browser guest. The host long-polls this endpoint,
 * so the page sees an offer/ICE frame almost immediately while the DataChannel
 * carries the file bytes.
 */
export function openBridgeSignaling(
  auth: BridgeAuth,
  onMessage: (message: SignalingMessage) => void,
  onDisconnect?: (event: { code: number; reason: string; wasClean: boolean }) => void,
): Promise<SignalingSession> {
  let closed = false
  let failures = 0
  const abort = new AbortController()

  const send = (message: SignalingMessage) => {
    if (closed) return
    void postJson('/signal', { token: auth.sessionToken, message }, true).catch(() => undefined)
  }

  // Replacing a signaling session must not tell the host the guest left:
  // reconnect opens a second poll before closing the old one. Explicit exits
  // call closeBridge(), and page unload uses closeBridgeBeacon().
  const close = () => {
    if (closed) return
    closed = true
    abort.abort()
  }

  const poll = async () => {
    while (!closed) {
      try {
        const response = await fetch(`${bridgeBase}/events`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ token: auth.sessionToken }),
          signal: abort.signal,
        })
        if (response.status === 403 || response.status === 404) {
          // The host rotated its code or dropped this guest; no amount of
          // polling with the old token can recover it.
          onDisconnect?.({ code: 1008, reason: 'bridge session ended', wasClean: false })
          return
        }
        if (!response.ok) throw new Error(`events ${response.status}`)
        const payload = await response.json() as { messages?: SignalingMessage[] }
        failures = 0
        for (const message of payload.messages || []) {
          if (!closed) onMessage(message)
        }
      } catch {
        if (closed) return
        failures += 1
        if (failures >= maxPollFailures) {
          onDisconnect?.({ code: 1006, reason: 'bridge polling failed', wasClean: false })
          return
        }
        await new Promise((resolve) => globalThis.setTimeout(resolve, pollRetryDelayMs))
      }
    }
  }

  void poll()
  return Promise.resolve({ send, close })
}

export function getBridgeStatus(auth: BridgeAuth): Promise<BridgeStatusResponse> {
  return postJson<BridgeStatusResponse>('/status', { token: auth.sessionToken })
}

export function sendBridgeHeartbeat(auth: BridgeAuth): Promise<BridgeHeartbeatResponse> {
  return postJson<BridgeHeartbeatResponse>('/heartbeat', { token: auth.sessionToken })
}

export function closeBridge(auth: BridgeAuth): Promise<void> {
  return postJson<void>('/close', { token: auth.sessionToken })
}

export function closeBridgeBeacon(auth: BridgeAuth): void {
  const body = JSON.stringify({ token: auth.sessionToken })
  if (navigator.sendBeacon) {
    const sent = navigator.sendBeacon(`${bridgeBase}/close`, new Blob([body], { type: 'application/json' }))
    if (sent) return
  }
  void closeBridge(auth).catch(() => undefined)
}
