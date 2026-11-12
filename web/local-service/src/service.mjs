import crypto from 'node:crypto'
import fs from 'node:fs/promises'
import http from 'node:http'
import net from 'node:net'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import {
  FrameSocket,
  LAN_SERVICE_TYPE,
  PAIRING_TTL_MS,
  SESSION_TTL_MS,
  authProof,
  buildQrPayload,
  constantTimeEquals,
  deriveRoomKey,
  generateCode,
  hostProof,
  lanAddresses,
  mdnsSid,
  nowIso,
  randomToken,
  resumeHostProof,
} from './lan.mjs'

let bonjourModule
try {
  bonjourModule = await import('bonjour-service')
} catch {
  bonjourModule = null
}

const maxBodyBytes = 64 * 1024
const longPollMs = 20_000
const maxFailures = 10
const defaultLimits = {
  maxFileSize: 2_147_483_648,
  maxActiveTransfers: 1,
  chunkSize: 262_144,
}

class EventQueue {
  constructor() {
    this.events = []
    this.waiters = []
  }

  push(event) {
    this.events.push(event)
    this.wake()
  }

  clear() {
    this.events = []
  }

  take() {
    const events = this.events
    this.events = []
    return events
  }

  async wait(timeoutMs, isValid = () => true) {
    if (this.events.length > 0) {
      return isValid() ? this.take() : []
    }
    return new Promise((resolve) => {
      let settled = false
      const finish = () => {
        if (settled) return
        settled = true
        // A superseded poll must not steal an event from the current one:
        // leave it queued for the poll that is still allowed to consume it.
        resolve(isValid() ? this.take() : [])
      }
      const timer = setTimeout(finish, timeoutMs)
      this.waiters.push(() => {
        clearTimeout(timer)
        finish()
      })
    })
  }

  wake() {
    const waiters = this.waiters.splice(0)
    for (const wake of waiters) wake()
  }
}

/**
 * A browser-native local host.
 *
 * The service owns the LAN TCP listener and Bonjour advertisement; the page
 * served from it is the WebRTC initiator (the host), and any other browser or
 * native app joins as the peer. Only SDP/ICE cross this process; file bytes
 * stay on the WebRTC DataChannel.
 */
export class LocalService {
  constructor({ distDir, hostPort = 0, log = console.log } = {}) {
    this.distDir = distDir || fileURLToPath(new URL('../../public-ui/dist/', import.meta.url))
    this.hostPortRequest = hostPort
    this.log = log
    this.bonjourModule = bonjourModule
    this.hostToken = randomToken(24)
    this.hostDeviceId = `local_${randomToken(6)}`
    this.hostDeviceLabel = 'Web host'
    this.hostQueue = new EventQueue()
    this.guestQueue = new EventQueue()
    this.peer = null
    this.hostAttached = false
    this.httpPort = 0
    this.tcpPort = 0
    this.addresses = []
    this.guestUrl = ''
    this.qrPayload = ''
    this.code = ''
    this.roomKey = ''
    this.roomId = ''
    this.sessionToken = ''
    this.pairingExpiresAt = ''
    this.sessionExpiresAt = ''
    this.pairingDeadline = 0
    this.failures = 0
    this.hostPollGeneration = 0
    this.guestPollGeneration = 0
    this.httpServer = null
    this.tcpServer = null
    this.advertiser = null
    this.deadlineTimer = null
    this.stopped = false
  }

  async start() {
    this.stopped = false
    await this.startHttp()
    await this.createRoom()
    return this.describe()
  }

  async startHttp() {
    this.httpServer = http.createServer((request, response) => {
      this.handleHttp(request, response).catch((error) => {
        this.log(`http: ${error.message}`)
        try {
          respondJson(response, 500, { code: 'internal_error', message: error.message })
        } catch {
          // response may already be gone
        }
      })
    })
    await new Promise((resolve, reject) => {
      this.httpServer.once('error', reject)
      this.httpServer.listen(this.hostPortRequest, '0.0.0.0', () => resolve())
    })
    this.httpPort = this.httpServer.address().port
  }

  describe() {
    const hostUrl = `http://127.0.0.1:${this.httpPort}/?host=${this.hostToken}`
    return {
      hostUrl,
      guestUrl: this.guestUrl,
      qrPayload: this.qrPayload,
      code: this.code,
      httpPort: this.httpPort,
      tcpPort: this.tcpPort,
      hostToken: this.hostToken,
      roomId: this.roomId,
      pairingExpiresAt: this.pairingExpiresAt,
      sessionExpiresAt: this.sessionExpiresAt,
    }
  }

  async createRoom() {
    this.teardownRoom()
    this.code = generateCode()
    this.roomKey = deriveRoomKey(this.code)
    this.roomId = `room_${randomToken(12)}`
    this.sessionToken = randomToken(24)
    this.pairingDeadline = Date.now() + PAIRING_TTL_MS
    this.pairingExpiresAt = nowIso(PAIRING_TTL_MS)
    this.sessionExpiresAt = nowIso(SESSION_TTL_MS)
    this.failures = 0
    this.hostAttached = false

    this.tcpServer = net.createServer((socket) => {
      this.handleNative(socket).catch((error) => {
        this.log(`native: ${error.message}`)
        try { socket.destroy() } catch { /* already gone */ }
      })
    })
    await new Promise((resolve, reject) => {
      this.tcpServer.once('error', reject)
      this.tcpServer.listen(0, '0.0.0.0', () => resolve())
    })
    this.tcpPort = this.tcpServer.address().port
    this.addresses = lanAddresses(this.tcpPort)
    const webAddress = this.addresses[0]
      ? `${this.addresses[0].host}:${this.httpPort}`
      : `127.0.0.1:${this.httpPort}`
    this.guestUrl = `http://${webAddress}/?pair=${this.code}`
    this.qrPayload = buildQrPayload({
      code: this.code,
      webAddress,
      lanAddresses: this.addresses,
    })
    this.advertise()
    this.deadlineTimer = setTimeout(() => {
      if (!this.peer) {
        this.log('lan: pairing window expired')
        this.stopAccepting()
      }
    }, PAIRING_TTL_MS)
    this.log(`room ${this.roomId} listening on ${this.tcpPort}; guest url ${this.guestUrl}`)
  }

  advertise() {
    try {
      const { Bonjour } = this.bonjourModule || {}
      if (!Bonjour) return
      const bonjour = new Bonjour()
      const salt = randomToken(8)
      const service = bonjour.publish({
        name: `LanShare-${randomToken(3)}`,
        type: LAN_SERVICE_TYPE,
        port: this.tcpPort,
        txt: {
          v: '1',
          salt,
          sid: mdnsSid(this.roomKey, salt),
        },
      })
      this.advertiser = { bonjour, service }
      service.on?.('error', (error) => this.log(`mdns: ${error.message}`))
    } catch (error) {
      this.log(`mdns: ${error.message}`)
    }
  }

  stopAccepting() {
    if (this.deadlineTimer) {
      clearTimeout(this.deadlineTimer)
      this.deadlineTimer = null
    }
    if (this.tcpServer) {
      this.tcpServer.close()
      this.tcpServer = null
    }
    if (this.advertiser) {
      try { this.advertiser.service.stop?.() } catch { /* already stopped */ }
      try { this.advertiser.bonjour.destroy?.() } catch { /* already stopped */ }
      this.advertiser = null
    }
  }

  teardownRoom() {
    this.stopAccepting()
    this.closePeer()
    this.hostQueue.clear()
    this.guestQueue.clear()
    this.peer = null
    this.hostAttached = false
  }

  closePeer() {
    if (this.peer?.kind === 'native') {
      try { this.peer.frame.close() } catch { /* already closed */ }
    }
    this.peer = null
  }

  // MARK: HTTP

  async handleHttp(request, response) {
    const url = new URL(request.url || '/', `http://${request.headers.host || 'localhost'}`)
    if (url.pathname.startsWith('/__bridge__/')) {
      await this.handleBridge(url.pathname.slice('/__bridge__/'.length), request, response, url)
      return
    }
    await this.serveStatic(url.pathname, request, response)
  }

  async handleBridge(action, request, response, url) {
    switch (action) {
      case 'info':
        return this.sendInfo(request, response)
      case 'host':
        return this.host(request, response)
      case 'join':
        return this.join(request, response)
      case 'events':
        return this.events(request, response)
      case 'signal':
        return this.signal(request, response)
      case 'status':
        return this.status(request, response)
      case 'heartbeat':
        return this.heartbeat(request, response)
      case 'close':
        return this.close(request, response)
      default:
        return respondJson(response, 404, { code: 'not_found', message: 'Unknown bridge endpoint.' })
    }
  }

  sendInfo(request, response) {
    if (request.method !== 'GET' && request.method !== 'HEAD') {
      return respondJson(response, 405, { code: 'method_not_allowed' })
    }
    respondJson(response, 200, {
      mode: 'lan-local-service',
      code: this.code,
      hostDeviceId: this.hostDeviceId,
      hostDeviceLabel: this.hostDeviceLabel,
      roomId: this.roomId,
      pairingExpiresAt: this.pairingExpiresAt,
      sessionExpiresAt: this.sessionExpiresAt,
      limits: defaultLimits,
      guestUrl: this.guestUrl,
    })
  }

  async host(request, response) {
    if (request.method !== 'POST') {
      return respondJson(response, 405, { code: 'method_not_allowed' })
    }
    const body = await readJson(request)
    if (!body || !constantTimeEquals(String(body.token || ''), this.hostToken)) {
      return respondJson(response, 403, { code: 'invalid_host', message: 'Invalid host token.' })
    }
    if (body.refresh === true) {
      await this.createRoom()
    }
    this.hostAttached = true
    respondJson(response, 200, {
      mode: 'lan-local-service',
      hostToken: this.hostToken,
      code: this.code,
      roomId: this.roomId,
      deviceId: this.hostDeviceId,
      deviceLabel: this.hostDeviceLabel,
      sessionToken: this.hostToken,
      role: 'initiator',
      peerCount: 1,
      pairingExpiresAt: this.pairingExpiresAt,
      sessionExpiresAt: this.sessionExpiresAt,
      limits: defaultLimits,
      guestUrl: this.guestUrl,
      qrPayload: this.qrPayload,
      lanAddresses: this.addresses.map((address) => `${address.host}:${address.port}`),
    })
  }

  async join(request, response) {
    if (request.method !== 'POST') {
      return respondJson(response, 405, { code: 'method_not_allowed' })
    }
    const body = await readJson(request)
    const code = String(body?.code || '').trim()
    const guestId = String(body?.deviceId || '').trim()
    const guestLabel = String(body?.deviceLabel || '').trim() || 'Web guest'
    if (!code || !guestId) {
      return respondJson(response, 400, { code: 'invalid_request', message: 'Missing code or device identity.' })
    }
    if (this.peer) {
      if (
        this.peer.kind === 'browser' &&
        guestId === this.peer.id &&
        body.token &&
        constantTimeEquals(String(body.token), this.peer.token)
      ) {
        // A reloaded page reconnects its signaling; make the host issue a new
        // offer for the fresh WebRTC peer.
        this.hostQueue.push(this.peerJoinedEvent(this.peer.id, this.peer.label))
        return respondJson(response, 200, this.guestWelcome(this.peer))
      }
      return respondJson(response, 409, { code: 'room_full', message: 'This room already has a peer.' })
    }
    if (Date.now() > this.pairingDeadline) {
      return respondJson(response, 403, { code: 'invalid_code', message: 'The code is wrong or expired.' })
    }
    if (!constantTimeEquals(code, this.code)) {
      this.failures += 1
      await new Promise((resolve) => setTimeout(resolve, 2_000))
      if (this.failures >= maxFailures) {
        return respondJson(response, 429, { code: 'too_many_attempts', message: 'Too many failed pairing attempts.' })
      }
      return respondJson(response, 403, { code: 'invalid_code', message: 'The code is wrong or expired.' })
    }
    this.failures = 0
    const guest = {
      kind: 'browser',
      id: guestId,
      label: guestLabel,
      token: randomToken(24),
    }
    this.peer = guest
    this.hostQueue.push(this.peerJoinedEvent(guestId, guestLabel))
    this.log(`browser guest ${guestLabel} paired`)
    respondJson(response, 200, this.guestWelcome(guest))
  }

  guestWelcome(guest) {
    return {
      token: guest.token,
      sessionToken: guest.token,
      roomId: this.roomId,
      deviceId: guest.id,
      role: 'peer',
      peerCount: 2,
      pairingExpiresAt: this.pairingExpiresAt,
      sessionExpiresAt: this.sessionExpiresAt,
      limits: defaultLimits,
      host: {
        deviceId: this.hostDeviceId,
        deviceLabel: this.hostDeviceLabel,
      },
    }
  }

  async events(request, response) {
    if (request.method !== 'POST') {
      return respondJson(response, 405, { code: 'method_not_allowed' })
    }
    const body = await readJson(request)
    const role = this.roleForToken(body?.token)
    if (!role) {
      return respondJson(response, 403, { code: 'invalid_session', message: 'Invalid bridge token.' })
    }
    const queue = role === 'host' ? this.hostQueue : this.guestQueue
    const generation = role === 'host' ? ++this.hostPollGeneration : ++this.guestPollGeneration
    const isCurrent = () => role === 'host'
      ? generation === this.hostPollGeneration
      : generation === this.guestPollGeneration
    const messages = await queue.wait(longPollMs, isCurrent)
    respondJson(response, 200, { messages })
  }

  async signal(request, response) {
    if (request.method !== 'POST') {
      return respondJson(response, 405, { code: 'method_not_allowed' })
    }
    const body = await readJson(request)
    const role = this.roleForToken(body?.token)
    const message = body?.message
    if (!role || !message || typeof message !== 'object') {
      return respondJson(response, 403, { code: 'invalid_session', message: 'Invalid bridge token or signal.' })
    }
    if (role === 'host') {
      this.sendToPeer(message)
    } else if (this.peer?.kind === 'browser') {
      this.hostQueue.push({
        ...message,
        roomId: this.roomId,
        from: this.peer.id,
        to: this.hostDeviceId,
      })
    }
    respondEmpty(response, 202)
  }

  sendToPeer(message) {
    const peer = this.peer
    if (!peer) return
    const envelope = {
      ...message,
      roomId: this.roomId,
      from: this.hostDeviceId,
      to: peer.id,
    }
    if (peer.kind === 'native') {
      peer.frame.write({ t: 'signal', e: envelope })
    } else {
      this.guestQueue.push(envelope)
    }
  }

  async status(request, response) {
    if (request.method !== 'POST') {
      return respondJson(response, 405, { code: 'method_not_allowed' })
    }
    const body = await readJson(request)
    const role = this.roleForToken(body?.token)
    if (!role) {
      return respondJson(response, 403, { code: 'invalid_session', message: 'Invalid bridge token.' })
    }
    const peers = [{
      deviceId: this.hostDeviceId,
      deviceLabel: this.hostDeviceLabel,
      deviceType: 'web',
    }]
    if (this.peer) {
      peers.push({
        deviceId: this.peer.id,
        deviceLabel: this.peer.label,
        deviceType: this.peer.kind,
      })
    }
    respondJson(response, 200, {
      roomId: this.roomId,
      peerCount: this.peer ? 2 : 1,
      paired: Boolean(this.peer),
      pairingExpiresAt: this.pairingExpiresAt,
      sessionExpiresAt: this.sessionExpiresAt,
      peers,
    })
  }

  async heartbeat(request, response) {
    if (request.method !== 'POST') {
      return respondJson(response, 405, { code: 'method_not_allowed' })
    }
    const body = await readJson(request)
    if (!this.roleForToken(body?.token)) {
      return respondJson(response, 403, { code: 'invalid_session', message: 'Invalid bridge token.' })
    }
    respondJson(response, 200, {
      ok: true,
      lastSeenAt: nowIso(0),
      sessionExpiresAt: this.sessionExpiresAt,
    })
  }

  async close(request, response) {
    if (request.method !== 'POST') {
      return respondJson(response, 405, { code: 'method_not_allowed' })
    }
    const body = await readJson(request)
    const role = this.roleForToken(body?.token)
    if (!role) {
      return respondJson(response, 403, { code: 'invalid_session', message: 'Invalid bridge token.' })
    }
    if (role === 'guest') {
      const peer = this.peer
      this.closePeer()
      if (peer) {
        this.hostQueue.push({
          type: 'peer_left',
          roomId: this.roomId,
          from: peer.id,
        })
      }
    } else {
      // The host page is leaving or reattaching; keep the code/listener but
      // drop the peer so the next guest starts cleanly.
      const peer = this.peer
      this.closePeer()
      if (peer) {
        this.guestQueue.push({ type: 'room_closed', roomId: this.roomId })
      }
    }
    respondEmpty(response, 204)
  }

  roleForToken(token) {
    const value = String(token || '')
    if (value && constantTimeEquals(value, this.hostToken)) return 'host'
    if (this.peer?.kind === 'browser' && value && constantTimeEquals(value, this.peer.token)) return 'guest'
    return null
  }

  // MARK: native guest

  async handleNative(socket) {
    const frame = new FrameSocket(socket)
    socket.setTimeout(0)
    const first = await frame.read(10_000)
    if (!first) {
      frame.close()
      return
    }
    if (first.t === 'hello') {
      await this.handleHello(frame, first)
    } else if (first.t === 'resume') {
      await this.handleResume(frame, first)
    } else {
      frame.close()
    }
  }

  async handleHello(frame, hello) {
    if (hello.v !== 1 || !hello.deviceId) {
      frame.write({ t: 'reject', code: 'invalid_request' })
      frame.close()
      return
    }
    if (this.peer) {
      frame.write({ t: 'reject', code: 'room_full' })
      frame.close()
      return
    }
    if (Date.now() > this.pairingDeadline) {
      frame.write({ t: 'reject', code: 'expired_code' })
      frame.close()
      return
    }
    const nonce = randomToken(16)
    frame.write({ t: 'challenge', nonce })
    const auth = await frame.read(10_000)
    if (!auth || auth.t !== 'auth') {
      frame.close()
      return
    }
    if (!constantTimeEquals(authProof(this.roomKey, nonce, hello.deviceId), auth.proof || '')) {
      frame.write({ t: 'reject', code: 'invalid_code' })
      frame.close()
      this.failures += 1
      await new Promise((resolve) => setTimeout(resolve, 2_000))
      if (this.failures >= maxFailures) this.stopAccepting()
      return
    }
    this.failures = 0
    const guest = {
      kind: 'native',
      id: hello.deviceId,
      label: hello.deviceLabel || 'Native guest',
      frame,
    }
    this.peer = guest
    frame.write(this.welcome({
      proof: hostProof(this.roomKey, nonce, this.hostDeviceId),
    }))
    this.hostQueue.push(this.peerJoinedEvent(guest.id, guest.label))
    this.log(`native guest ${guest.label} paired`)
    this.readNativeLoop(guest)
  }

  async handleResume(frame, resume) {
    const valid = resume.deviceId &&
      this.sessionToken &&
      constantTimeEquals(String(resume.sessionToken || ''), this.sessionToken)
    if (!valid) {
      frame.write({ t: 'reject', code: 'invalid_session' })
      frame.close()
      return
    }
    const guest = {
      kind: 'native',
      id: String(resume.deviceId),
      label: 'Native guest',
      frame,
    }
    this.closePeer()
    this.peer = guest
    frame.write(this.welcome({
      proof: resumeHostProof(this.roomKey, this.sessionToken, this.hostDeviceId),
    }))
    this.hostQueue.push(this.peerJoinedEvent(guest.id, guest.label))
    this.readNativeLoop(guest)
  }

  welcome({ proof }) {
    return {
      t: 'welcome',
      v: 1,
      deviceId: this.hostDeviceId,
      deviceLabel: this.hostDeviceLabel,
      proof,
      roomId: this.roomId,
      role: 'peer',
      sessionToken: this.sessionToken,
      pairingExpiresAt: this.pairingExpiresAt,
      sessionExpiresAt: this.sessionExpiresAt,
      limits: defaultLimits,
    }
  }

  peerJoinedEvent(deviceId, deviceLabel) {
    return {
      type: 'peer_joined',
      roomId: this.roomId,
      from: deviceId,
      deviceId,
      payload: { deviceLabel },
    }
  }

  readNativeLoop(peer) {
    const run = async () => {
      try {
        while (this.peer === peer) {
          const message = await peer.frame.read(30_000)
          if (!message) break
          switch (message.t) {
            case 'signal':
              if (message.e) {
                this.hostQueue.push({
                  ...message.e,
                  roomId: this.roomId,
                  from: peer.id,
                  to: this.hostDeviceId,
                })
              }
              break
            case 'ping':
              peer.frame.write({ t: 'pong', n: message.n })
              break
            case 'pong':
              break
            case 'close':
              this.pushPeerLeft(peer)
              this.closePeer()
              return
            case 'reject':
            case 'error':
              this.log(`native: ${message.t} ${message.code || message.message || ''}`)
              break
            default:
              break
          }
        }
      } catch {
        // socket ended
      }
      if (this.peer === peer) {
        this.pushPeerLeft(peer)
        this.closePeer()
      }
    }
    void run()
    const ping = setInterval(() => {
      if (this.peer !== peer) {
        clearInterval(ping)
        return
      }
      peer.frame.write({ t: 'ping', n: 0 })
    }, 10_000)
    peer.frame.socket.once('close', () => clearInterval(ping))
  }

  pushPeerLeft(peer) {
    this.hostQueue.push({
      type: 'peer_left',
      roomId: this.roomId,
      from: peer.id,
    })
  }

  // MARK: static

  async serveStatic(pathname, request, response) {
    if (request.method !== 'GET' && request.method !== 'HEAD') {
      return respondEmpty(response, 405)
    }
    let clean = decodeURIComponent(pathname)
    if (clean === '/' || clean === '') clean = '/index.html'
    if (clean.includes('..')) return respondEmpty(response, 404)
    const filePath = path.join(this.distDir, clean)
    try {
      const data = await fs.readFile(filePath)
      response.writeHead(200, {
        'Content-Type': contentType(filePath),
        'Content-Length': data.length,
        'Cache-Control': 'no-store',
      })
      response.end(request.method === 'HEAD' ? undefined : data)
    } catch {
      if (clean.startsWith('/assets/') || clean.endsWith('.webmanifest')) {
        return respondEmpty(response, 404)
      }
      try {
        const shell = await fs.readFile(path.join(this.distDir, 'index.html'))
        response.writeHead(200, {
          'Content-Type': 'text/html; charset=utf-8',
          'Content-Length': shell.length,
          'Cache-Control': 'no-store',
        })
        response.end(request.method === 'HEAD' ? undefined : shell)
      } catch {
        respondEmpty(response, 404)
      }
    }
  }

  async stop() {
    this.stopped = true
    this.teardownRoom()
    if (this.httpServer) {
      await new Promise((resolve) => this.httpServer.close(resolve))
      this.httpServer = null
    }
  }
}

function contentType(filePath) {
  switch (path.extname(filePath).slice(1).toLowerCase()) {
    case 'html': return 'text/html; charset=utf-8'
    case 'js':
    case 'mjs': return 'text/javascript; charset=utf-8'
    case 'css': return 'text/css; charset=utf-8'
    case 'json': return 'application/json; charset=utf-8'
    case 'webmanifest': return 'application/manifest+json'
    case 'svg': return 'image/svg+xml'
    case 'png': return 'image/png'
    case 'ico': return 'image/x-icon'
    case 'woff2': return 'font/woff2'
    default: return 'application/octet-stream'
  }
}

async function readJson(request) {
  if (request.method !== 'POST') return null
  const chunks = []
  let total = 0
  for await (const chunk of request) {
    total += chunk.length
    if (total > maxBodyBytes) return null
    chunks.push(chunk)
  }
  try {
    return JSON.parse(Buffer.concat(chunks).toString('utf8'))
  } catch {
    return null
  }
}

function respondJson(response, status, body) {
  const data = Buffer.from(JSON.stringify(body), 'utf8')
  response.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': data.length,
    'Cache-Control': 'no-store',
    'Connection': 'close',
  })
  response.end(data)
}

function respondEmpty(response, status) {
  response.writeHead(status, {
    'Content-Length': 0,
    'Cache-Control': 'no-store',
    'Connection': 'close',
  })
  response.end()
}
