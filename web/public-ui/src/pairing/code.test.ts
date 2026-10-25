import { describe, expect, it, vi } from 'vitest'
import { generateConnectionCode, isUsableCode, normalizeCode } from './code'

describe('connection code rules', () => {
  it('normalizes peer input without accepting non-digits', () => {
    expect(normalizeCode(' １２34-56 78abc')).toBe('12345678')
  })

  it('does not truncate overlong peer input into a valid code', () => {
    const normalized = normalizeCode('4938271600')
    expect(normalized).toBe('4938271600')
    expect(isUsableCode(normalized)).toBe(false)
  })

  it('rejects weak 8-digit numeric codes', () => {
    expect(isUsableCode('11111111')).toBe(false)
    expect(isUsableCode('12345678')).toBe(false)
    expect(isUsableCode('87654321')).toBe(false)
    expect(isUsableCode('49382716')).toBe(true)
  })

  it('generates a CSPRNG-backed 8-digit usable code', () => {
    const spy = vi.spyOn(crypto, 'getRandomValues')
    const code = generateConnectionCode()
    expect(code).toMatch(/^\d{8}$/)
    expect(isUsableCode(code)).toBe(true)
    expect(spy).toHaveBeenCalled()
    spy.mockRestore()
  })
})
