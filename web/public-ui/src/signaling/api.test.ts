import { afterEach, describe, expect, it, vi } from 'vitest'
import * as api from './api'
import { ApiError, closeRoom, closeRoomBeacon, getRoomStatus, openSignaling, sendRoomHeartbeat } from './api'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('room close API', () => {
  it('does not expose a relay-config client in direct-only mode', () => {
    expect('getRelayConfig' in api).toBe(false)
  })

  it('posts heartbeat session credentials in the body instead of the URL', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ ok: true, lastSeenAt: '2026-06-28T00:00:00Z', sessionExpiresAt: '2026-06-28T02:00:00Z' }),
    })
    vi.stubGlobal('fetch', fetchMock)

    const heartbeat = await sendRoomHeartbeat({
      roomId: 'room_123',
      deviceId: 'dev_abc',
      sessionToken: 'sess_secret',
    })

    expect(fetchMock).toHaveBeenCalledWith('/__private__/rooms/room_123/heartbeat', expect.objectContaining({
      method: 'POST',
      keepalive: true,
    }))
    const [, init] = fetchMock.mock.calls[0]
    expect(JSON.parse(init.body)).toEqual({
      deviceId: 'dev_abc',
      sessionToken: 'sess_secret',
    })
    expect(fetchMock.mock.calls[0][0]).not.toContain('sess_secret')
    expect(heartbeat.sessionExpiresAt).toBe('2026-06-28T02:00:00Z')
  })

  it('preserves heartbeat backend error codes', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 401,
      json: () => Promise.resolve({ code: 'invalid_session', message: 'Room or session is not valid.' }),
    })
    vi.stubGlobal('fetch', fetchMock)

    await expect(sendRoomHeartbeat({
      roomId: 'room_123',
      deviceId: 'dev_abc',
      sessionToken: 'sess_secret',
    })).rejects.toMatchObject({
      name: 'ApiError',
      code: 'invalid_session',
      status: 401,
      message: 'Room or session is not valid.',
    } satisfies Partial<ApiError>)
  })

  it('posts room status session credentials in the body instead of the URL', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ roomId: 'room_123', peerCount: 2, paired: true, peers: [] }),
    })
    vi.stubGlobal('fetch', fetchMock)

    await getRoomStatus({
      roomId: 'room_123',
      deviceId: 'dev_abc',
      sessionToken: 'sess_secret',
    })

    expect(fetchMock).toHaveBeenCalledWith('/__private__/rooms/room_123/status', expect.objectContaining({
      method: 'POST',
      keepalive: true,
    }))
    const [, init] = fetchMock.mock.calls[0]
    expect(JSON.parse(init.body)).toEqual({
      deviceId: 'dev_abc',
      sessionToken: 'sess_secret',
    })
    expect(fetchMock.mock.calls[0][0]).not.toContain('sess_secret')
  })

  it('posts session credentials in the body instead of the URL', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true })
    vi.stubGlobal('fetch', fetchMock)

    await closeRoom({
      roomId: 'room_123',
      deviceId: 'dev_abc',
      sessionToken: 'sess_secret',
    })

    expect(fetchMock).toHaveBeenCalledWith('/__private__/rooms/room_123/close', expect.objectContaining({
      method: 'POST',
      keepalive: true,
    }))
    const [, init] = fetchMock.mock.calls[0]
    expect(JSON.parse(init.body)).toEqual({
      deviceId: 'dev_abc',
      sessionToken: 'sess_secret',
    })
    expect(fetchMock.mock.calls[0][0]).not.toContain('sess_secret')
  })

  it('uses sendBeacon for pagehide close when available', () => {
    const sendBeacon = vi.fn().mockReturnValue(true)
    const fetchMock = vi.fn()
    vi.stubGlobal('navigator', { sendBeacon })
    vi.stubGlobal('fetch', fetchMock)

    closeRoomBeacon({
      roomId: 'room_123',
      deviceId: 'dev_abc',
      sessionToken: 'sess_secret',
    })

    expect(sendBeacon).toHaveBeenCalledOnce()
    expect(sendBeacon.mock.calls[0][0]).toBe('/__private__/rooms/room_123/close')
    expect(sendBeacon.mock.calls[0][0]).not.toContain('sess_secret')
    expect(fetchMock).not.toHaveBeenCalled()
  })
})

describe('signaling websocket setup', () => {
  it('rejects when the socket closes before auth succeeds', async () => {
    class FakeWebSocket {
      static instances: FakeWebSocket[] = []
      private listeners: Record<string, Array<(event: Event) => void>> = {}

      constructor(readonly url: string) {
        FakeWebSocket.instances.push(this)
      }

      addEventListener(type: string, listener: (event: Event) => void) {
        this.listeners[type] = [...(this.listeners[type] || []), listener]
      }

      send = vi.fn()
      close = vi.fn()

      dispatch(type: string, event = new Event(type)) {
        for (const listener of this.listeners[type] || []) listener(event)
      }
    }

    vi.stubGlobal('WebSocket', FakeWebSocket)
    vi.stubGlobal('window', {
      location: { protocol: 'https:', host: 'lanshare.example' },
      setTimeout: globalThis.setTimeout,
      clearTimeout: globalThis.clearTimeout,
    })

    const setup = openSignaling({
      roomId: 'room_123',
      deviceId: 'dev_abc',
      sessionToken: 'sess_secret',
    })

    FakeWebSocket.instances[0].dispatch('close')

    await expect(setup).rejects.toThrow('Signaling connection closed before authentication.')
  })
})
