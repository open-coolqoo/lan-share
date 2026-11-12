import Foundation
import Network

private enum LanRole {
    case host
    case guest
}

private final class LanOnce: @unchecked Sendable {
    private let lock = UnfairLock()
    private var done = false

    func take() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if done { return false }
        done = true
        return true
    }
}

/// Serializes sends so an offer cannot overtake the ICE candidates generated
/// after it. `enqueue` is synchronous because `SignalingLink.send` is.
private final class LanOutboundQueue: @unchecked Sendable {
    private let lock = UnfairLock()
    private var messages: [LanMessage] = []
    private var draining = false
    private var closed = false
    private let send: @Sendable (LanMessage) async throws -> Void

    init(send: @escaping @Sendable (LanMessage) async throws -> Void) {
        self.send = send
    }

    func enqueue(_ message: LanMessage) {
        lock.lock()
        if closed {
            lock.unlock()
            return
        }
        if messages.count >= 256 {
            messages.removeFirst()
        }
        messages.append(message)
        let shouldStart = !draining
        draining = true
        lock.unlock()
        if shouldStart {
            Task { [weak self] in
                await self?.drain()
            }
        }
    }

    func close() {
        lock.lock()
        closed = true
        messages.removeAll()
        lock.unlock()
    }

    private func drain() async {
        while true {
            lock.lock()
            if closed || messages.isEmpty {
                draining = false
                lock.unlock()
                return
            }
            let next = messages.removeFirst()
            lock.unlock()
            do {
                try await send(next)
            } catch {
                close()
                return
            }
        }
    }
}

/// One local room. The creator owns a TCP listener and advertises it over
/// Bonjour; the joiner uses the QR addresses or matches the advertised `sid`
/// derived from the code. After the challenge/HMAC handshake, SDP/ICE travel
/// over the socket as `signal` frames; WebRTC and the transfer layer are
/// untouched.
public final class LanRoomSession: RoomService, SignalingLink, @unchecked Sendable {
    private let deviceId: String
    private let deviceLabel: String
    private let log: @Sendable (String) -> Void
    private let queue = DispatchQueue(label: "com.lanshare.lan.session")
    private let lock = UnfairLock()

    private var role: LanRole = .host
    private var active = false
    private var paired = false
    private var code = ""
    private var roomKey = ""
    private var roomId = ""
    private var sessionToken = ""
    private var pairingExpiresAt = ""
    private var sessionExpiresAt = ""
    private var remoteDeviceId = ""
    private var remoteDeviceLabel = ""
    private var addresses: [LanAddress] = []
    private var listener: NWListener?
    private var connection: LanConnection?
    private var bridgeTransport: LanBridgeTransport?
    private var bridgeToken = ""
    private var pairingDeadlineMs: Int64 = 0
    private var outbound: LanOutboundQueue?
    private var readTask: Task<Void, Never>?
    private var pingTask: Task<Void, Never>?
    private var deadlineTask: Task<Void, Never>?
    private var resumeTask: Task<Void, Never>?
    private var failureCount = 0
    private var handshakeBusy = false
    private var intentionalClose = false
    /// Invalidates late callbacks from a socket that has already been replaced.
    private var socketGeneration = 0
    private var pendingEvents: [SignalingEnvelope] = []
    private var pendingSignals: [SignalingEnvelope] = []
    private var eventSink: (@Sendable (SignalingEnvelope) -> Void)?
    private var disconnectSink: (@Sendable (String) -> Void)?
    private var resumeEndpoints: [NWEndpoint] = []

    public init(
        deviceId: String,
        deviceLabel: String,
        log: @escaping @Sendable (String) -> Void,
    ) {
        self.deviceId = deviceId
        self.deviceLabel = deviceLabel
        self.log = log
    }

    deinit {
        listener?.cancel()
        connection?.cancel()
        readTask?.cancel()
        pingTask?.cancel()
        deadlineTask?.cancel()
        resumeTask?.cancel()
    }

    // MARK: RoomService

    public func create(deviceId: String, deviceLabel: String) async throws -> CreatedRoom {
        teardown()
        lock.lock()
        role = .host
        code = LanPairing.generateCode()
        roomKey = LanPairing.roomKey(code)
        roomId = "room_" + LanPairing.randomToken(bytes: 12)
        sessionToken = LanPairing.randomToken(bytes: 24)
        remoteDeviceId = ""
        remoteDeviceLabel = ""
        paired = false
        failureCount = 0
        bridgeTransport = nil
        bridgeToken = LanPairing.randomToken(bytes: 24)
        pairingDeadlineMs = currentMilliseconds() + LanPairing.pairingTTLMilliseconds
        pairingExpiresAt = isoNow(LanPairing.pairingTTLMilliseconds)
        sessionExpiresAt = isoNow(LanPairing.sessionTTLMilliseconds)
        let salt = LanPairing.randomToken(bytes: 8)
        let key = roomKey
        let txt = [
            "v": "1",
            "salt": salt,
            "sid": LanPairing.mdnsSid(roomKey: key, salt: salt),
        ]
        let roomCode = code
        let roomId = self.roomId
        let token = sessionToken
        let pairingExpiry = pairingExpiresAt
        let sessionExpiry = sessionExpiresAt
        lock.unlock()

        let port = try await startListener(txt: txt)
        let addresses = enumerateLanAddresses(port: port)
        lock.lock()
        self.addresses = addresses
        active = true
        lock.unlock()

        let created = CreatedRoom(
            code: roomCode,
            roomId: roomId,
            deviceId: deviceId,
            role: "initiator",
            peerCount: 1,
            sessionToken: token,
            pairingExpiresAt: pairingExpiry,
            sessionExpiresAt: sessionExpiry,
            limits: defaultTransferLimits(),
        )
        deadlineTask = Task { [weak self] in
            try? await Task.sleep(
                nanoseconds: UInt64(LanPairing.pairingTTLMilliseconds) * 1_000_000,
            )
            guard let self, !Task.isCancelled else { return }
            self.lock.lock()
            let isPaired = self.paired
            self.lock.unlock()
            if !isPaired {
                self.log("lan: pairing window expired")
                self.stopListener()
            }
        }
        log("lan: room \(roomId) listening on \(port) (key \(key.prefix(6))…)")
        return created
    }

    public func join(
        target: LanJoinTarget,
        deviceId: String,
        deviceLabel: String,
    ) async throws -> JoinedRoom {
        teardown()
        lock.lock()
        role = .guest
        code = target.code
        roomKey = target.roomKey
        active = true
        lock.unlock()

        var endpoints: [NWEndpoint] = target.addresses.compactMap { address in
            guard let port = NWEndpoint.Port(rawValue: address.port) else { return nil }
            return .hostPort(host: NWEndpoint.Host(address.host), port: port)
        }
        if endpoints.isEmpty {
            let key = target.roomKey
            let service = await LanBrowser().browse(timeout: 5) { discovered in
                guard discovered.txt["v"] == "1",
                      let salt = discovered.txt["salt"],
                      let sid = discovered.txt["sid"] else { return false }
                return LanPairing.matchesSid(roomKey: key, salt: salt, advertised: sid)
            }
            if let service {
                endpoints = [service.endpoint]
            }
        }
        guard !endpoints.isEmpty else {
            lock.lock()
            active = false
            lock.unlock()
            throw RoomApiError(
                code: "no_local_device",
                message: "No local device found for this code.",
                status: nil,
            )
        }
        lock.lock()
        resumeEndpoints = endpoints
        lock.unlock()
        var lastError: Error?
        for endpoint in endpoints {
            do {
                return try await joinAt(endpoint, deviceId: deviceId, deviceLabel: deviceLabel)
            } catch {
                lastError = error
                log("lan: join failed \(error.localizedDescription)")
            }
        }
        lock.lock()
        active = false
        lock.unlock()
        throw lastError ?? RoomApiError(
            code: "connection_failed",
            message: "Unable to reach the local device.",
            status: nil,
        )
    }

    public func heartbeat(_ room: JoinedRoom) async throws -> RoomHeartbeatResponse {
        RoomHeartbeatResponse(
            ok: true,
            lastSeenAt: isoNow(0),
            sessionExpiresAt: room.sessionExpiresAt,
        )
    }

    public func close(_ room: JoinedRoom) async throws {
        if let bridgeTransport {
            bridgeTransport.send(
                SignalingEnvelope(type: "room_closed", roomId: roomId, deviceId: deviceId),
            )
            bridgeTransport.close()
        } else {
            outbound?.enqueue(LanMessage(t: "close"))
            try? await Task.sleep(nanoseconds: 60_000_000)
        }
        teardown()
    }

    public func signalingLink(log: @escaping @Sendable (String) -> Void) -> any SignalingLink {
        self
    }

    // MARK: SignalingLink

    public func connect(
        room: JoinedRoom,
        onEvent: @escaping @Sendable (SignalingEnvelope) -> Void,
        onDisconnect: @escaping @Sendable (String) -> Void,
    ) {
        lock.lock()
        eventSink = onEvent
        disconnectSink = onDisconnect
        let buffered = pendingEvents
        pendingEvents.removeAll()
        let hasConnection = connection != nil
        let isGuest = role == .guest
        lock.unlock()
        for event in buffered {
            onEvent(event)
        }
        if isGuest, !hasConnection {
            startResume()
        }
    }

    public func send(_ envelope: SignalingEnvelope) {
        let currentRoomId: String
        lock.lock()
        currentRoomId = roomId
        lock.unlock()
        let copy = SignalingEnvelope(
            type: envelope.type,
            roomId: currentRoomId,
            from: deviceId,
            to: envelope.to,
            deviceId: envelope.deviceId,
            sessionToken: envelope.sessionToken,
            payload: envelope.payload,
            error: envelope.error,
        )
        lock.lock()
        let bridge = bridgeTransport
        let queue = outbound
        if bridge == nil, queue == nil {
            if pendingSignals.count >= 64 {
                pendingSignals.removeFirst()
            }
            pendingSignals.append(copy)
        }
        lock.unlock()
        if let bridge {
            bridge.send(copy)
            return
        }
        queue?.enqueue(LanMessage(t: "signal", e: copy))
    }

    public func close() {
        closeConnection()
    }

    /// Non-suspending teardown for a mode switch or app teardown.
    public func shutdown() {
        teardown()
    }

    /// The addresses the host advertised in its QR, or an empty list off-Wi-Fi.
    public func localAddresses() -> [LanAddress] {
        lock.lock()
        let value = addresses
        lock.unlock()
        return value
    }

    /// The bound listener port, for tests and diagnostics.
    public func listeningPort() -> UInt16? {
        lock.lock()
        let listener = self.listener
        lock.unlock()
        return listener?.port?.rawValue
    }

    // MARK: Browser bridge

    /// Current host room fields for the browser guest's info endpoint.
    func bridgeInfo() -> LanBridgeRoomInfo? {
        lock.lock()
        defer { lock.unlock() }
        guard role == .host, active, !code.isEmpty else { return nil }
        return LanBridgeRoomInfo(
            code: code,
            hostDeviceId: deviceId,
            hostDeviceLabel: deviceLabel,
            roomId: roomId,
            pairingExpiresAt: pairingExpiresAt,
            sessionExpiresAt: sessionExpiresAt,
            limits: defaultTransferLimits(),
        )
    }

    /// Routes host signaling to the browser guest instead of the TCP socket.
    func bridgeAttach(_ transport: LanBridgeTransport) {
        lock.lock()
        bridgeTransport = transport
        let pending = pendingSignals
        pendingSignals.removeAll()
        lock.unlock()
        for envelope in pending {
            transport.send(envelope)
        }
    }

    /// Authenticates a browser guest with the room code (or resumes with the
    /// token minted by an earlier join) and announces it to the host so the
    /// existing `peer_joined` path creates the offer.
    func bridgeJoin(
        code joinCode: String,
        deviceId guestDeviceId: String,
        deviceLabel guestDeviceLabel: String,
        resumeToken: String?,
    ) -> LanBridgeWelcome? {
        lock.lock()
        guard role == .host, active, !code.isEmpty else {
            lock.unlock()
            return nil
        }
        if !paired, currentMilliseconds() > pairingDeadlineMs {
            lock.unlock()
            return nil
        }
        guard LanPairing.constantTimeEquals(code, joinCode) else {
            lock.unlock()
            return nil
        }
        let resuming = paired &&
            (resumeToken?.isEmpty == false) &&
            LanPairing.constantTimeEquals(bridgeToken, resumeToken ?? "") &&
            remoteDeviceId == guestDeviceId
        if paired, !resuming {
            lock.unlock()
            return nil
        }
        if bridgeToken.isEmpty {
            bridgeToken = LanPairing.randomToken(bytes: 24)
        }
        remoteDeviceId = guestDeviceId
        remoteDeviceLabel = guestDeviceLabel
        paired = true
        let welcome = LanBridgeWelcome(
            token: bridgeToken,
            roomId: roomId,
            hostDeviceId: deviceId,
            hostDeviceLabel: deviceLabel,
            pairingExpiresAt: pairingExpiresAt,
            sessionExpiresAt: sessionExpiresAt,
            limits: defaultTransferLimits(),
        )
        lock.unlock()
        emitAuthAndPeerJoined(guestDeviceLabel)
        log("lan: browser guest \(guestDeviceLabel.prefix(32)) paired")
        return welcome
    }

    /// Relays an answer/ICE envelope from the browser to the host's AppViewModel.
    func bridgeIncoming(_ envelope: SignalingEnvelope) {
        lock.lock()
        guard bridgeTransport != nil else {
            lock.unlock()
            return
        }
        let from = remoteDeviceId
        lock.unlock()
        emit(
            SignalingEnvelope(
                type: envelope.type,
                roomId: roomId,
                from: from,
                to: envelope.to,
                payload: envelope.payload,
                error: envelope.error,
            ),
        )
    }

    /// Drop a bridge transport that is shutting down, without a peer_left event.
    func bridgeDetach(_ transport: LanBridgeTransport) {
        lock.lock()
        guard bridgeTransport === transport else {
            lock.unlock()
            return
        }
        bridgeTransport = nil
        bridgeToken = ""
        paired = false
        remoteDeviceId = ""
        remoteDeviceLabel = ""
        lock.unlock()
    }

    /// The browser tab closed: keep the room and code, drop the guest.
    func bridgeClose() {
        lock.lock()
        let transport = bridgeTransport
        bridgeTransport = nil
        paired = false
        bridgeToken = ""
        let from = remoteDeviceId
        remoteDeviceId = ""
        remoteDeviceLabel = ""
        lock.unlock()
        transport?.close()
        emit(SignalingEnvelope(type: "peer_left", roomId: roomId, from: from))
    }

    // MARK: Host

    private func startListener(txt: [String: String]) async throws -> UInt16 {
        let parameters = NWParameters.tcp
        parameters.allowLocalEndpointReuse = true
        let listener = try NWListener(using: parameters, on: .any)
        listener.service = NWListener.Service(
            name: "LanShare-\(LanPairing.randomToken(bytes: 3))",
            type: LanPairing.serviceType,
            domain: nil,
            txtRecord: NWTXTRecord(txt),
        )
        lock.lock()
        self.listener = listener
        lock.unlock()
        listener.newConnectionHandler = { [weak self] connection in
            self?.handleIncoming(connection)
        }
        let once = LanOnce()
        let port: UInt16 = try await withCheckedThrowingContinuation { continuation in
            listener.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    guard once.take() else { return }
                    if let value = listener.port?.rawValue {
                        continuation.resume(returning: value)
                    } else {
                        continuation.resume(
                            throwing: LanConnectionError.readyFailed("listener has no port"),
                        )
                    }
                case .failed(let error):
                    guard once.take() else { return }
                    continuation.resume(throwing: error)
                default:
                    break
                }
            }
            listener.start(queue: queue)
        }
        return port
    }

    private func handleIncoming(_ connection: NWConnection) {
        lock.lock()
        let busy = handshakeBusy
        if !busy { handshakeBusy = true }
        lock.unlock()
        guard !busy else {
            connection.cancel()
            return
        }
        Task { [weak self] in
            guard let self else { return }
            await self.hostHandshake(connection)
            self.lock.lock()
            self.handshakeBusy = false
            self.lock.unlock()
        }
    }

    private func hostHandshake(_ nw: NWConnection) async {
        let connection = LanConnection(connection: nw)
        do {
            try await connection.open(timeout: 5)
            connection.setIdleTimeout(12)
            guard let first = try await connection.receive() else {
                connection.cancel()
                return
            }
            switch first.t {
            case "hello":
                try await handleHello(connection, first)
            case "resume":
                try await handleResume(connection, first)
            default:
                connection.cancel()
            }
        } catch {
            log("lan: host handshake failed \(error.localizedDescription)")
            connection.cancel()
        }
    }

    private func handleHello(
        _ connection: LanConnection,
        _ hello: LanMessage,
    ) async throws {
        lock.lock()
        let alreadyPaired = paired
        lock.unlock()
        if alreadyPaired {
            try? await connection.send(LanMessage(t: "reject", code: "room_full"))
            connection.cancel()
            return
        }
        guard hello.v == lanProtocolVersion, let guestDeviceId = hello.deviceId, !guestDeviceId.isEmpty else {
            try? await connection.send(LanMessage(t: "reject", code: "invalid_request"))
            connection.cancel()
            return
        }
        let nonce = LanPairing.randomToken(bytes: 16)
        try await connection.send(LanMessage(t: "challenge", nonce: nonce))
        guard let auth = try await connection.receive(), auth.t == "auth" else {
            connection.cancel()
            return
        }
        lock.lock()
        let key = roomKey
        lock.unlock()
        let expected = LanPairing.authProof(roomKey: key, nonce: nonce, deviceId: guestDeviceId)
        guard LanPairing.constantTimeEquals(expected, auth.proof ?? "") else {
            try? await connection.send(LanMessage(t: "reject", code: "invalid_code"))
            connection.cancel()
            lock.lock()
            failureCount += 1
            let failures = failureCount
            lock.unlock()
            log("lan: rejected handshake (\(failures))")
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            if failures >= 10 {
                log("lan: too many failed handshakes")
                stopListener()
            }
            return
        }
        lock.lock()
        remoteDeviceId = guestDeviceId
        remoteDeviceLabel = hello.deviceLabel ?? ""
        paired = true
        let welcome = welcomeFrame(
            proof: LanPairing.hostProof(
                roomKey: key,
                nonce: nonce,
                hostDeviceId: deviceId,
            ),
        )
        let label = hello.deviceLabel ?? ""
        lock.unlock()
        try await connection.send(welcome)
        attach(connection)
        emitAuthAndPeerJoined(label)
        log("lan: guest \(label) paired")
    }

    private func handleResume(
        _ connection: LanConnection,
        _ resume: LanMessage,
    ) async throws {
        lock.lock()
        let valid = paired &&
            resume.deviceId == remoteDeviceId &&
            resume.sessionToken == sessionToken
        let label = remoteDeviceLabel
        let welcome = valid
            ? welcomeFrame(
                proof: LanPairing.resumeHostProof(
                    roomKey: roomKey,
                    sessionToken: sessionToken,
                    hostDeviceId: deviceId,
                ),
            )
            : nil
        lock.unlock()
        guard let welcome else {
            try? await connection.send(LanMessage(t: "reject", code: "invalid_session"))
            connection.cancel()
            return
        }
        try await connection.send(welcome)
        attach(connection)
        emitAuthAndPeerJoined(label)
        log("lan: guest resumed")
    }

    private func welcomeFrame(proof: String?) -> LanMessage {
        LanMessage(
            t: "welcome",
            v: lanProtocolVersion,
            deviceId: deviceId,
            deviceLabel: deviceLabel,
            proof: proof,
            roomId: roomId,
            role: "peer",
            sessionToken: sessionToken,
            pairingExpiresAt: pairingExpiresAt,
            sessionExpiresAt: sessionExpiresAt,
            limits: defaultTransferLimits(),
        )
    }

    // MARK: Guest

    private func joinAt(
        _ endpoint: NWEndpoint,
        deviceId: String,
        deviceLabel: String,
    ) async throws -> JoinedRoom {
        let connection = LanConnection(connection: NWConnection(to: endpoint, using: .tcp))
        do {
            try await connection.open(timeout: 5)
            connection.setIdleTimeout(12)
            try await connection.send(
                LanMessage(
                    t: "hello",
                    v: lanProtocolVersion,
                    deviceId: deviceId,
                    deviceLabel: deviceLabel,
                    deviceType: "mobile",
                    capabilities: ClientCapabilities.standard,
                ),
            )
            guard let challenge = try await connection.receive(),
                  challenge.t == "challenge",
                  let nonce = challenge.nonce
            else {
                throw RoomApiError(
                    code: "invalid_response",
                    message: "The local device sent an unexpected response.",
                    status: nil,
                )
            }
            lock.lock()
            let key = roomKey
            lock.unlock()
            try await connection.send(
                LanMessage(
                    t: "auth",
                    proof: LanPairing.authProof(roomKey: key, nonce: nonce, deviceId: deviceId),
                ),
            )
            guard let welcome = try await connection.receive() else {
                throw RoomApiError(
                    code: "connection_failed",
                    message: "The local device closed the connection.",
                    status: nil,
                )
            }
            if welcome.t == "reject" {
                throw RoomApiError(
                    code: welcome.code ?? "join_rejected",
                    message: welcome.message ?? "The local device rejected the code.",
                    status: nil,
                )
            }
            guard welcome.t == "welcome", let hostDeviceId = welcome.deviceId, !hostDeviceId.isEmpty else {
                throw RoomApiError(
                    code: "invalid_response",
                    message: "The local device sent an unexpected response.",
                    status: nil,
                )
            }
            let expected = LanPairing.hostProof(
                roomKey: key,
                nonce: nonce,
                hostDeviceId: hostDeviceId,
            )
            guard LanPairing.constantTimeEquals(expected, welcome.proof ?? "") else {
                throw RoomApiError(
                    code: "invalid_response",
                    message: "The local device could not be verified.",
                    status: nil,
                )
            }
            let joinedRoomId = welcome.roomId.flatMap { $0.isEmpty ? nil : $0 }
                ?? "room_" + LanPairing.randomToken(bytes: 12)
            let token = welcome.sessionToken.flatMap { $0.isEmpty ? nil : $0 }
                ?? LanPairing.randomToken(bytes: 24)
            let pairingExpiry = welcome.pairingExpiresAt.flatMap { $0.isEmpty ? nil : $0 }
                ?? isoNow(LanPairing.pairingTTLMilliseconds)
            let sessionExpiry = welcome.sessionExpiresAt.flatMap { $0.isEmpty ? nil : $0 }
                ?? isoNow(LanPairing.sessionTTLMilliseconds)
            let joined = JoinedRoom(
                roomId: joinedRoomId,
                deviceId: deviceId,
                role: welcome.role ?? "peer",
                peerCount: 2,
                sessionToken: token,
                pairingExpiresAt: pairingExpiry,
                sessionExpiresAt: sessionExpiry,
                limits: welcome.limits ?? defaultTransferLimits(),
            )
            lock.lock()
            roomId = joinedRoomId
            sessionToken = token
            remoteDeviceId = hostDeviceId
            remoteDeviceLabel = welcome.deviceLabel ?? ""
            pairingExpiresAt = pairingExpiry
            sessionExpiresAt = sessionExpiry
            paired = true
            resumeEndpoints = [endpoint]
            let label = remoteDeviceLabel
            lock.unlock()
            attach(connection)
            emitAuthAndPeerJoined(label)
            log("lan: joined \(joinedRoomId)")
            return joined
        } catch {
            connection.cancel()
            throw error
        }
    }

    // MARK: Socket plumbing

    private func attach(_ connection: LanConnection) {
        closeConnection()
        let generation = bumpSocketGeneration()
        let outbound = LanOutboundQueue { message in
            try await connection.send(message)
        }
        lock.lock()
        self.connection = connection
        self.outbound = outbound
        intentionalClose = false
        let pending = pendingSignals
        pendingSignals.removeAll()
        lock.unlock()
        for envelope in pending {
            outbound.enqueue(LanMessage(t: "signal", e: envelope))
        }
        connection.setIdleTimeout(30)
        pingTask?.cancel()
        pingTask = Task { [weak self] in
            var counter = 0
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 10_000_000_000)
                guard !Task.isCancelled else { return }
                self?.outbound?.enqueue(LanMessage(t: "ping", n: counter))
                counter += 1
            }
        }
        startReadLoop(connection, generation)
    }

    private func startReadLoop(_ connection: LanConnection, _ generation: Int) {
        readTask?.cancel()
        readTask = Task { [weak self] in
            await self?.readLoop(connection, generation)
        }
    }

    private func readLoop(_ connection: LanConnection, _ generation: Int) async {
        do {
            while generation == currentSocketGeneration(),
                  let message = try await connection.receive() {
                guard generation == currentSocketGeneration() else { return }
                switch message.t {
                case "signal":
                    if let envelope = message.e {
                        lock.lock()
                        let roomId = self.roomId
                        let from = remoteDeviceId
                        lock.unlock()
                        emit(
                            SignalingEnvelope(
                                type: envelope.type,
                                roomId: roomId,
                                from: from,
                                to: envelope.to,
                                deviceId: envelope.deviceId,
                                sessionToken: envelope.sessionToken,
                                payload: envelope.payload,
                                error: envelope.error,
                            ),
                        )
                    }
                case "ping":
                    outbound?.enqueue(LanMessage(t: "pong", n: message.n))
                case "pong":
                    break
                case "close":
                    intentionalClose = true
                    lock.lock()
                    let roomId = self.roomId
                    let from = remoteDeviceId
                    lock.unlock()
                    emit(
                        SignalingEnvelope(
                            type: "room_closed",
                            roomId: roomId,
                            from: from,
                        ),
                    )
                    connection.cancel()
                    return
                case "reject":
                    log("lan: peer rejected \(message.code ?? "")")
                    connection.cancel()
                    return
                default:
                    break
                }
            }
        } catch {
            // The stream ends when the socket closes; the handler below owns
            // the user-visible disconnect.
        }
        if generation == currentSocketGeneration(), !intentionalClose {
            notifyDisconnect("lan signaling closed")
        }
    }

    private func currentSocketGeneration() -> Int {
        lock.lock()
        let value = socketGeneration
        lock.unlock()
        return value
    }

    private func bumpSocketGeneration() -> Int {
        lock.lock()
        socketGeneration += 1
        let value = socketGeneration
        lock.unlock()
        return value
    }

    private func closeConnection() {
        _ = bumpSocketGeneration()
        lock.lock()
        let connection = self.connection
        let outbound = self.outbound
        self.connection = nil
        self.outbound = nil
        lock.unlock()
        connection?.cancel()
        outbound?.close()
        readTask?.cancel()
        readTask = nil
        pingTask?.cancel()
        pingTask = nil
    }

    private func notifyDisconnect(_ reason: String) {
        lock.lock()
        let sink = disconnectSink
        let isActive = active
        lock.unlock()
        if isActive {
            sink?(reason)
        }
    }

    private func emit(_ event: SignalingEnvelope) {
        lock.lock()
        let sink = eventSink
        if sink == nil {
            if pendingEvents.count >= 64 {
                pendingEvents.removeFirst()
            }
            pendingEvents.append(event)
        }
        lock.unlock()
        sink?(event)
    }

    private func emitAuthAndPeerJoined(_ label: String) {
        lock.lock()
        let roomId = self.roomId
        let remote = remoteDeviceId
        lock.unlock()
        emit(SignalingEnvelope(type: "auth_ok", roomId: roomId, deviceId: deviceId))
        emit(
            SignalingEnvelope(
                type: "peer_joined",
                roomId: roomId,
                from: remote,
                deviceId: remote,
                payload: .object(["deviceLabel": .string(label)]),
            ),
        )
    }

    private func startResume() {
        lock.lock()
        if resumeTask != nil {
            lock.unlock()
            return
        }
        let endpoints = resumeEndpoints
        lock.unlock()
        guard !endpoints.isEmpty else { return }
        resumeTask = Task { [weak self] in
            guard let self else { return }
            let deadline = Date().addingTimeInterval(60)
            var attempt = 0
            while Date() < deadline && !Task.isCancelled {
                self.lock.lock()
                let alive = self.connection != nil
                let isActive = self.active
                self.lock.unlock()
                if !isActive || alive { break }
                for endpoint in endpoints {
                    if await self.resumeAt(endpoint) {
                        return
                    }
                }
                attempt += 1
                try? await Task.sleep(
                    nanoseconds: UInt64(min(10, attempt)) * 1_000_000_000,
                )
            }
            self.lock.lock()
            let alive = self.connection != nil
            let isActive = self.active
            self.resumeTask = nil
            self.lock.unlock()
            if isActive, !alive {
                self.notifyDisconnect("lan resume failed")
            }
        }
    }

    private func resumeAt(_ endpoint: NWEndpoint) async -> Bool {
        let connection = LanConnection(connection: NWConnection(to: endpoint, using: .tcp))
        do {
            try await connection.open(timeout: 4)
            connection.setIdleTimeout(12)
            lock.lock()
            let token = sessionToken
            let key = roomKey
            lock.unlock()
            try await connection.send(
                LanMessage(t: "resume", deviceId: deviceId, sessionToken: token),
            )
            guard let welcome = try await connection.receive(), welcome.t == "welcome" else {
                connection.cancel()
                return false
            }
            let expected = LanPairing.resumeHostProof(
                roomKey: key,
                sessionToken: token,
                hostDeviceId: welcome.deviceId ?? "",
            )
            guard LanPairing.constantTimeEquals(expected, welcome.proof ?? "") else {
                connection.cancel()
                return false
            }
            let label = welcome.deviceLabel ?? remoteDeviceLabel
            attach(connection)
            emitAuthAndPeerJoined(label)
            log("lan: resumed")
            return true
        } catch {
            connection.cancel()
            return false
        }
    }

    private func stopAdvertising() {
        lock.lock()
        let listener = self.listener
        lock.unlock()
        listener?.service = nil
    }

    /// Stops accepting new connections and removes the Bonjour advertisement.
    /// The existing paired socket keeps working.
    private func stopListener() {
        lock.lock()
        let listener = self.listener
        self.listener = nil
        lock.unlock()
        listener?.cancel()
    }

    private func teardown() {
        lock.lock()
        active = false
        paired = false
        handshakeBusy = false
        intentionalClose = true
        let bridge = bridgeTransport
        bridgeTransport = nil
        bridgeToken = ""
        let listener = self.listener
        self.listener = nil
        deadlineTask?.cancel()
        deadlineTask = nil
        resumeTask?.cancel()
        resumeTask = nil
        lock.unlock()
        bridge?.close()
        closeConnection()
        listener?.cancel()
        lock.lock()
        pendingEvents.removeAll()
        pendingSignals.removeAll()
        eventSink = nil
        disconnectSink = nil
        code = ""
        roomKey = ""
        roomId = ""
        sessionToken = ""
        pairingExpiresAt = ""
        sessionExpiresAt = ""
        remoteDeviceId = ""
        remoteDeviceLabel = ""
        addresses = []
        resumeEndpoints = []
        failureCount = 0
        lock.unlock()
    }

    private func isoNow(_ offsetMilliseconds: Int64) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        return formatter.string(
            from: Date().addingTimeInterval(Double(offsetMilliseconds) / 1_000),
        )
    }
}
