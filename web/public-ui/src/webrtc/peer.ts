import type { SignalingMessage } from '../signaling/api'

type ConnectionState = 'new' | 'connecting' | 'connected' | 'disconnected' | 'failed' | 'closed'

interface PeerRuntimeOptions {
  iceServers?: RTCIceServer[]
  sendSignal: (message: SignalingMessage) => void
  onOpen: (channel: RTCDataChannel) => void
  onState: (state: ConnectionState) => void
  onFailure: (reason: string) => void
}

export class PeerRuntime {
  private pc: RTCPeerConnection
  private channel: RTCDataChannel | null = null
  private remoteDeviceId = ''
  private pendingIceCandidates: RTCIceCandidateInit[] = []
  private readonly options: PeerRuntimeOptions

  constructor(options: PeerRuntimeOptions) {
    this.options = options
    this.pc = new RTCPeerConnection({
      iceServers: options.iceServers || [],
    })
    this.pc.onicecandidate = (event) => {
      if (!event.candidate || !this.remoteDeviceId) return
      this.options.sendSignal({
        type: 'ice_candidate',
        to: this.remoteDeviceId,
        payload: event.candidate.toJSON(),
      })
    }
    this.pc.onconnectionstatechange = () => {
      const state = this.pc.connectionState as ConnectionState
      this.options.onState(state)
      // `disconnected` is where a Wi-Fi/4G switch shows up first. It often heals
      // on its own, so it is reported as a state rather than a failure; the
      // caller owns the deadline that decides when it stops being a blip.
      if (state === 'failed') this.options.onFailure('Direct connection failed.')
    }
    this.pc.ondatachannel = (event) => {
      this.attachChannel(event.channel)
    }
  }

  async createOffer(remoteDeviceId: string): Promise<void> {
    this.remoteDeviceId = remoteDeviceId
    this.attachChannel(this.pc.createDataChannel('lanshare-files', { ordered: true }))
    const offer = await this.pc.createOffer()
    await this.pc.setLocalDescription(offer)
    this.options.sendSignal({
      type: 'offer',
      to: remoteDeviceId,
      payload: this.pc.localDescription?.toJSON(),
    })
  }

  async handleSignal(message: SignalingMessage): Promise<void> {
    if (message.from) this.remoteDeviceId = message.from
    if (message.type === 'offer') {
      const offer = message.payload as RTCSessionDescriptionInit
      await this.pc.setRemoteDescription(offer)
      await this.flushPendingIceCandidates()
      const answer = await this.pc.createAnswer()
      await this.pc.setLocalDescription(answer)
      this.options.sendSignal({
        type: 'answer',
        to: this.remoteDeviceId,
        payload: this.pc.localDescription?.toJSON(),
      })
      return
    }
    if (message.type === 'answer') {
      await this.pc.setRemoteDescription(message.payload as RTCSessionDescriptionInit)
      await this.flushPendingIceCandidates()
      return
    }
    if (message.type === 'ice_candidate' && message.payload) {
      const candidate = message.payload as RTCIceCandidateInit
      if (!this.pc.remoteDescription) {
        this.pendingIceCandidates.push(candidate)
        return
      }
      await this.pc.addIceCandidate(candidate)
    }
  }

  // Rebuilding the candidate set is the only way a WebRTC link recovers after
  // the interface underneath it changed; without this a recovered network keeps
  // a dead connection forever.
  async restartIce(): Promise<void> {
    if (this.pc.signalingState === 'closed') return
    const offer = await this.pc.createOffer({ iceRestart: true })
    await this.pc.setLocalDescription(offer)
    if (!this.remoteDeviceId) return
    this.options.sendSignal({
      type: 'offer',
      to: this.remoteDeviceId,
      payload: this.pc.localDescription?.toJSON(),
    })
  }

  // A re-authenticated peer re-broadcasts peer_joined; only the transport
  // itself can tell that upkeep apart from a fresh pairing that must be
  // re-offered. A channel that merely looks open on a half-dead connection
  // does not count.
  isLinkHealthy(): boolean {
    return this.channel?.readyState === 'open' && this.pc.connectionState === 'connected'
  }

  close(): void {
    this.channel?.close()
    this.pc.close()
  }

  private attachChannel(channel: RTCDataChannel): void {
    this.channel = channel
    channel.binaryType = 'arraybuffer'
    channel.onopen = () => this.options.onOpen(channel)
    channel.onerror = () => this.options.onFailure('DataChannel failed.')
    channel.onclose = () => this.options.onState('closed')
  }

  private async flushPendingIceCandidates(): Promise<void> {
    const candidates = this.pendingIceCandidates.splice(0)
    for (const candidate of candidates) {
      await this.pc.addIceCandidate(candidate)
    }
  }
}
