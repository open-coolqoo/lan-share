import { afterEach, describe, expect, it, vi } from 'vitest'
import { deriveRoomKey } from './roomKey'

describe('room key derivation', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('derives a stable opaque room key without exposing the code', async () => {
    const first = await deriveRoomKey('49382716')
    const second = await deriveRoomKey('49382716')

    expect(first).toBe(second)
    expect(first).toMatch(/^[A-Za-z0-9_-]+$/)
    expect(first).not.toContain('49382716')
  })

  it('fails clearly when secure crypto is unavailable', async () => {
    vi.stubGlobal('crypto', { ...crypto, subtle: undefined })

    await expect(deriveRoomKey('49382716')).rejects.toThrow(/HTTPS or localhost/)
  })
})
