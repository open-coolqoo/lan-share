import Foundation
import WebRTC

public enum PeerConnectionStatus: Equatable, Sendable {
    case connecting
    case connected
    case disconnected
    case failed
    case closed
}

/// Thread-safe DataChannel adapter handed to the transfer layer.
/// Native WebRTC `RTCDataChannel` calls are thread-safe; state polling and
/// send are forwarded as-is.
public final class ChannelAdapter: NSObject, RTCDataChannelDelegate, @unchecked Sendable, TransferChannel {
    public let identity: ObjectIdentifier
    private let channel: RTCDataChannel
    private let delegateQueue = DispatchQueue(label: "lanshare.channel.adapter")

    public var onText: (@Sendable (String) -> Void)?
    public var onBinary: (@Sendable (Data) -> Void)?
    public var onStateChange: (@Sendable (Bool) -> Void)?

    init(channel: RTCDataChannel) {
        self.channel = channel
        identity = ObjectIdentifier(channel)
        super.init()
        channel.delegate = self
    }

    public var isOpen: Bool { channel.readyState == .open }
    public var bufferedAmount: Int { Int(channel.bufferedAmount) }

    public func sendText(_ text: String) -> Bool {
        guard let data = text.data(using: .utf8) else { return false }
        return channel.sendData(RTCDataBuffer(data: data, isBinary: false))
    }

    public func sendBinary(_ bytes: Data) -> Bool {
        channel.sendData(RTCDataBuffer(data: bytes, isBinary: true))
    }

    public func close() {
        channel.close()
    }

    // MARK: RTCDataChannelDelegate (fired on WebRTC's internal threads)

    public func dataChannelDidChangeState(_ dataChannel: RTCDataChannel) {
        let open = dataChannel.readyState == .open
        delegateQueue.async { [weak self] in
            self?.onStateChange?(open)
        }
    }

    public func dataChannel(_ dataChannel: RTCDataChannel, didReceiveMessageWith buffer: RTCDataBuffer) {
        let data = buffer.data
        let binary = buffer.isBinary
        delegateQueue.async { [weak self] in
            guard let self else { return }
            if binary {
                self.onBinary?(data)
            } else if let text = String(data: data, encoding: .utf8) {
                self.onText?(text)
            }
        }
    }
}

/// Owns the `RTCPeerConnection` and the ordered `lanshare-files` DataChannel.
/// Mirrors the Android `AndroidPeerRuntime`: offer when initiator, answer on
/// remote offer, ICE forwarding with pending-candidate buffering.
public final class PeerRuntime: NSObject, RTCPeerConnectionDelegate, @unchecked Sendable {
    public static let dataChannelLabel = "lanshare-files"

    private let lock = NSLock()
    private var remoteDeviceId = ""
    private var pendingRemoteCandidates: [IceCandidatePayload] = []
    private var localChannelAdapters: [ChannelAdapter] = []
    private var closed = false

    private let peerConnection: RTCPeerConnection
    private let sendSignal: @Sendable (SignalingEnvelope) -> Void
    private let onChannelReady: @Sendable (ChannelAdapter) -> Void
    private let onConnectionState: @Sendable (PeerConnectionStatus) -> Void

    static func makeFactory() -> RTCPeerConnectionFactory {
        RTCInitializeSSL()
        return RTCPeerConnectionFactory(
            encoderFactory: RTCDefaultVideoEncoderFactory(),
            decoderFactory: RTCDefaultVideoDecoderFactory(),
        )
    }

    private static let factoryLock = NSLock()
    nonisolated(unsafe) private static var cachedFactory: RTCPeerConnectionFactory?

    /// Lazily creates the process-wide peer connection factory. WebRTC
    /// requires at most one factory; the lock keeps the double-checked init
    /// race-free.
    static func sharedFactory() -> RTCPeerConnectionFactory {
        factoryLock.lock()
        defer { factoryLock.unlock() }
        if let cachedFactory { return cachedFactory }
        let created = makeFactory()
        cachedFactory = created
        return created
    }

    /// - Parameters:
    ///   - iceServers: already direct-only (stun/turn entries filtered out upstream).
    public init(
        factory: RTCPeerConnectionFactory,
        iceServers: [RtcIceServer],
        sendSignal: @escaping @Sendable (SignalingEnvelope) -> Void,
        onChannelReady: @escaping @Sendable (ChannelAdapter) -> Void,
        onConnectionState: @escaping @Sendable (PeerConnectionStatus) -> Void,
    ) {
        self.sendSignal = sendSignal
        self.onChannelReady = onChannelReady
        self.onConnectionState = onConnectionState

        let configuration = RTCConfiguration()
        configuration.iceServers = iceServers.map { server in
            RTCIceServer(
                urlStrings: server.urls,
                username: server.username,
                credential: server.credential,
            )
        }
        configuration.sdpSemantics = .unifiedPlan
        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        peerConnection = factory.peerConnection(
            with: configuration,
            constraints: constraints,
            delegate: nil,
        )!
        super.init()
        peerConnection.delegate = self
    }

    public func createOffer(to remoteId: String) {
        lock.lock()
        remoteDeviceId = remoteId
        lock.unlock()
        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        if let channelConfig = channelConfiguration(),
           let channel = peerConnection.dataChannel(
               forLabel: PeerRuntime.dataChannelLabel,
               configuration: channelConfig,
           ) {
            let adapter = ChannelAdapter(channel: channel)
            // The data channel holds its delegate weakly, so the adapter must be
            // owned from here until the transfer session takes it — the only
            // other reference is the weak capture in the closure below. Without
            // this the adapter is released the moment this function returns, the
            // open callback is lost, and the offerer stays unconnected until the
            // direct-connection timeout reports a failure that never happened.
            // (Mirrors Android keeping channels in its `dataChannels` set.)
            lock.lock()
            localChannelAdapters.append(adapter)
            lock.unlock()
            // The channel is only usable once it actually opens; hold the
            // callback until the open state change fires.
            adapter.onStateChange = { [weak self, weak adapter] open in
                guard open, let self, let adapter else { return }
                self.onChannelReady(adapter)
            }
        }
        peerConnection.offer(for: constraints) { [weak self] description, _ in
            guard let self, let description else { return }
            self.peerConnection.setLocalDescription(description) { _ in }
            self.sendSignal(
                SignalingEnvelope(
                    type: "offer",
                    to: remoteId,
                    payload: signalingPayload(
                        SessionDescriptionPayload(
                            type: "offer",
                            sdp: description.sdp,
                        ),
                    ),
                ),
            )
        }
    }

    public func handleSignal(_ message: SignalingEnvelope) {
        lock.lock()
        if let from = message.from, !from.isEmpty { remoteDeviceId = from }
        lock.unlock()
        switch message.type {
        case "offer":
            guard let payload = try? decodedPayload(message.payload, as: SessionDescriptionPayload.self) else {
                return
            }
            let description = RTCSessionDescription(type: .offer, sdp: payload.sdp)
            peerConnection.setRemoteDescription(description) { [weak self] _ in
                guard let self else { return }
                self.flushPendingCandidates()
                let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
                self.peerConnection.answer(for: constraints) { [weak self] answer, _ in
                    guard let self, let answer else { return }
                    self.peerConnection.setLocalDescription(answer) { _ in }
                    self.sendSignal(
                        SignalingEnvelope(
                            type: "answer",
                            payload: signalingPayload(
                                SessionDescriptionPayload(
                                    type: "answer",
                                    sdp: answer.sdp,
                                ),
                            ),
                        ),
                    )
                }
            }
        case "answer":
            guard let payload = try? decodedPayload(message.payload, as: SessionDescriptionPayload.self) else {
                return
            }
            let description = RTCSessionDescription(type: .answer, sdp: payload.sdp)
            peerConnection.setRemoteDescription(description) { [weak self] _ in
                self?.flushPendingCandidates()
            }
        case "ice_candidate":
            guard let payload = try? decodedPayload(message.payload, as: IceCandidatePayload.self) else {
                return
            }
            if peerConnection.remoteDescription == nil {
                lock.lock()
                pendingRemoteCandidates.append(payload)
                lock.unlock()
            } else {
                peerConnection.add(makeCandidate(payload))
            }
        default:
            break
        }
    }

    public func close() {
        lock.lock()
        guard !closed else {
            lock.unlock()
            return
        }
        closed = true
        pendingRemoteCandidates.removeAll()
        localChannelAdapters.removeAll()
        lock.unlock()
        peerConnection.close()
    }

    private func channelConfiguration() -> RTCDataChannelConfiguration? {
        let configuration = RTCDataChannelConfiguration()
        configuration.isOrdered = true
        return configuration
    }

    private func makeCandidate(_ payload: IceCandidatePayload) -> RTCIceCandidate {
        RTCIceCandidate(
            sdp: payload.candidate,
            sdpMLineIndex: Int32(payload.sdpMLineIndex),
            sdpMid: payload.sdpMid,
        )
    }

    private func flushPendingCandidates() {
        lock.lock()
        let pending = pendingRemoteCandidates
        pendingRemoteCandidates.removeAll()
        lock.unlock()
        pending.forEach { peerConnection.add(makeCandidate($0)) }
    }

    // MARK: RTCPeerConnectionDelegate

    // Required members (M152 imports several didChange* selectors as the same
    // Swift `didChange` overload set).
    public func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
    public func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    public func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    public func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}
    public func peerConnection(_ peerConnection: RTCPeerConnection, didChange signalingState: RTCSignalingState) {}
    public func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {}
    public func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {}

    public func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        lock.lock()
        let remote = remoteDeviceId
        lock.unlock()
        guard !remote.isEmpty else { return }
        sendSignal(
            SignalingEnvelope(
                type: "ice_candidate",
                to: remote,
                payload: signalingPayload(
                    IceCandidatePayload(
                        candidate: candidate.sdp,
                        sdpMid: candidate.sdpMid,
                        sdpMLineIndex: Int(candidate.sdpMLineIndex),
                    ),
                ),
            ),
        )
    }

    public func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {
        let adapter = ChannelAdapter(channel: dataChannel)
        onChannelReady(adapter)
    }

    public func peerConnection(_ peerConnection: RTCPeerConnection, didChange state: RTCPeerConnectionState) {
        let status: PeerConnectionStatus
        switch state {
        case .new, .connecting: status = .connecting
        case .connected: status = .connected
        case .disconnected: status = .disconnected
        case .failed: status = .failed
        case .closed: status = .closed
        @unknown default: status = .closed
        }
        onConnectionState(status)
    }
}
