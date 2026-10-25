const weakCodes = new Set(['00000000', '11111111', '22222222', '33333333', '44444444', '55555555', '66666666', '77777777', '88888888', '99999999', '12345678', '87654321'])

export function normalizeCode(value: string): string {
  return value
    .normalize('NFKC')
    .trim()
    .replace(/[\s-]/g, '')
    .replace(/[^\d]/g, '')
}

export function isWeakCode(code: string): boolean {
  if (!/^\d{8}$/.test(code)) return true
  if (weakCodes.has(code)) return true
  const digits = code.split('').map(Number)
  const ascending = digits.every((digit, index) => index === 0 || digit === (digits[index - 1] + 1) % 10)
  const descending = digits.every((digit, index) => index === 0 || digit === (digits[index - 1] + 9) % 10)
  const same = digits.every((digit) => digit === digits[0])
  return ascending || descending || same
}

export function isUsableCode(code: string): boolean {
  return /^\d{8}$/.test(code) && !isWeakCode(code)
}

export function generateConnectionCode(): string {
  const max = 100_000_000
  const limit = Math.floor(0xffffffff / max) * max
  const buffer = new Uint32Array(1)

  for (let attempt = 0; attempt < 64; attempt += 1) {
    crypto.getRandomValues(buffer)
    const value = buffer[0]
    if (value >= limit) continue
    const code = String(value % max).padStart(8, '0')
    if (isUsableCode(code)) return code
  }
  throw new Error('Unable to generate a secure connection code.')
}
