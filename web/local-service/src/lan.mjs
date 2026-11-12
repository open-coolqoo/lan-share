import crypto from 'node:crypto'
import os from 'node:os'

const weakCodes = new Set([
  '00000000', '11111111', '22222222', '33333333', '44444444',
  '55555555', '66666666', '77777777', '88888888', '99999999',
  '12345678', '87654321',
])

const roomKeySalt = 'lanshare-room-v1:2026-06-25'
const roomKeyInfo = 'lanshare-room-v1'
const authPrefix = 'lanshare-lan-auth-v1|'
const hostPrefix = 'lanshare-lan-host-v1|'
const resumePrefix = 'lanshare-lan-resume-v1|'
const mdnsPrefix = 'lanshare-lan-mdns-v1|'

export const LAN_SERVICE_TYPE = 'lanshare'
export const PAIRING_TTL_MS = 120_000
export const SESSION_TTL_MS = 24 * 60 * 60 * 1_000

export function isUsableCode(code) {
  if (!/^\d{8}$/.test(code)) return false
  if (weakCodes.has(code)) return false
  const digits = code.split('').map(Number)
  const ascending = digits.every((digit, index) => index === 0 || digit === (digits[index - 1] + 1) % 10)
  const descending = digits.every((digit, index) => index === 0 || digit === (digits[index - 1] + 9) % 10)
  const same = digits.every((digit) => digit === digits[0])
  return !ascending && !descending && !same
}

export function generateCode(random = crypto) {
  for (let attempt = 0; attempt < 128; attempt += 1) {
    const code = String(random.randomInt(0, 100_000_000)).padStart(8, '0')
    if (isUsableCode(code)) return code
  }
  throw new Error('unable to generate a secure connection code')
}

export function deriveRoomKey(code) {
  const derived = crypto.hkdfSync(
    'sha256',
    Buffer.from(code, 'utf8'),
    Buffer.from(roomKeySalt, 'utf8'),
    Buffer.from(roomKeyInfo, 'utf8'),
    32,
  )
  const digest = crypto.createHash('sha256').update(Buffer.from(derived)).digest()
  return digest.toString('base64url')
}

export function hmac(key, data) {
  return crypto.createHmac('sha256', key).update(data).digest('base64url')
}

export function authProof(roomKey, nonce, deviceId) {
  return hmac(roomKey, `${authPrefix}${nonce}|${deviceId}`)
}

export function hostProof(roomKey, nonce, hostDeviceId) {
  return hmac(roomKey, `${hostPrefix}${nonce}|${hostDeviceId}`)
}

export function resumeHostProof(roomKey, sessionToken, hostDeviceId) {
  return hmac(roomKey, `${resumePrefix}${sessionToken}|${hostDeviceId}`)
}

export function mdnsSid(roomKey, salt) {
  return hmac(roomKey, `${mdnsPrefix}${salt}`).slice(0, 16)
}

export function randomToken(bytes = 16) {
  return crypto.randomBytes(bytes).toString('base64url')
}

export function constantTimeEquals(left, right) {
  const a = Buffer.from(String(left), 'utf8')
  const b = Buffer.from(String(right), 'utf8')
  return a.length === b.length && crypto.timingSafeEqual(a, b)
}

export function nowIso(offsetMs) {
  return new Date(Date.now() + offsetMs).toISOString()
}

export function authority(address) {
  return address.host.includes(':') ? `[${address.host}]:${address.port}` : `${address.host}:${address.port}`
}

export function lanAddresses(port) {
  const candidates = []
  for (const [name, items] of Object.entries(os.networkInterfaces())) {
    for (const item of items || []) {
      if (!item.internal && item.family === 'IPv4' && item.address) {
        candidates.push({ name, address: item.address })
      }
    }
  }
  const excluded = ['lo', 'tun', 'utun', 'ppp', 'ipsec', 'rmnet', 'docker', 'veth', 'bridge', 'awdl', 'llw']
  const usable = candidates.filter(({ name, address }) => {
    const lower = name.toLowerCase()
    if (excluded.some((prefix) => lower.startsWith(prefix))) return false
    const octets = address.split('.').map(Number)
    if (octets.length !== 4) return false
    return octets[0] === 10 ||
      (octets[0] === 172 && octets[1] >= 16 && octets[1] <= 31) ||
      (octets[0] === 192 && octets[1] === 168) ||
      (octets[0] === 169 && octets[1] === 254)
  })
  const preferred = usable.sort((left, right) => {
    const rank = (name) => {
      const lower = name.toLowerCase()
      if (lower.startsWith('en') || lower.startsWith('wlan') || lower.startsWith('eth')) return 0
      return 1
    }
    return rank(left.name) - rank(right.name)
  })
  return preferred.slice(0, 6).map(({ address }) => ({ host: address, port }))
}

/** Builds the QR that both a native scanner and a camera can use. */
export function buildQrPayload({ code, webAddress, lanAddresses: addresses }) {
  const base = webAddress
    ? `http://${webAddress}/?pair=${code}`
    : `lanshare-lan://pair?pair=${code}`
  return base + addresses
    .map((address) => `&addr=${encodeURIComponent(authority(address))}`)
    .join('')
}

/** Four-byte big-endian length prefix, then one JSON object. */
export function frame(message) {
  const payload = Buffer.from(JSON.stringify(message), 'utf8')
  const header = Buffer.allocUnsafe(4)
  header.writeUInt32BE(payload.length, 0)
  return Buffer.concat([header, payload])
}

export class FrameSocket {
  constructor(socket) {
    this.socket = socket
    this.buffer = Buffer.alloc(0)
    this.waiters = []
    this.closed = false
    socket.on('data', (chunk) => {
      this.buffer = Buffer.concat([this.buffer, chunk])
      this.pump()
    })
    socket.on('close', () => this.finish())
    socket.on('error', () => this.finish())
  }

  pump() {
    while (this.buffer.length >= 4) {
      const length = this.buffer.readUInt32BE(0)
      if (length <= 0 || length > 256 * 1024) {
        this.finish()
        return
      }
      if (this.buffer.length < 4 + length) return
      const payload = this.buffer.subarray(4, 4 + length)
      this.buffer = this.buffer.subarray(4 + length)
      const waiter = this.waiters.shift()
      if (waiter) {
        try {
          waiter.resolve(JSON.parse(payload.toString('utf8')))
        } catch {
          this.finish()
          return
        }
      }
    }
  }

  read(timeoutMs = 10_000) {
    if (this.closed) return Promise.reject(new Error('closed'))
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.waiters = this.waiters.filter((waiter) => waiter.resolve !== resolve)
        reject(new Error('timeout'))
      }, timeoutMs)
      this.waiters.push({
        resolve: (value) => {
          clearTimeout(timer)
          resolve(value)
        },
      })
    })
  }

  write(message) {
    if (this.closed) return false
    try {
      this.socket.write(frame(message))
      return true
    } catch {
      this.finish()
      return false
    }
  }

  close() {
    this.finish()
    try {
      this.socket.end()
    } catch {
      // already closed
    }
  }

  finish() {
    this.closed = true
    const waiters = this.waiters.splice(0)
    for (const waiter of waiters) waiter.resolve(null)
  }
}
