import Foundation
import Observation
import UIKit

/// Application orchestrator: room lifecycle, signaling, WebRTC, transfer
/// session, heartbeat, TTL auto-refresh, direct-failure handling, and
/// foreground-only interruption policy. Confined to the main actor.
@MainActor
@Observable
public final class AppViewModel {
    public private(set) var state = AppState()

    let deviceId: String
    let deviceLabel: String
    let contentRepository: PublicContentRepository?
    private let defaults: UserDefaults

    private var signaling: SignalingClient?
    private var peer: PeerRuntime?
    private var transfer: TransferSession?
    private var heartbeatTask: Task<Void, Never>?
    private var countdownTask: Task<Void, Never>?
    private var directFailureTask: Task<Void, Never>?
    private var reconnectTask: Task<Void, Never>?
    private var reconnectAttempt = 0
    private var reconnectDeadlineMs: Int64 = 0
    private var peerGeneration = 0
    private var createRetryTask: Task<Void, Never>?
    private var createRetryAttempt = 0
    private var lastCreateAttemptMs: Int64 = 0

    private static let directFailureTimeoutMs: Int64 = 30_000
    private static let heartbeatIntervalMs: UInt64 = 25_000
    private static let reconnectWindowMs: Int64 = 5 * 60_000

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        contentRepository = PublicContentRepository.fromBundle()
        let stored = defaults.string(forKey: "device_id")
        if let stored, !stored.isEmpty {
            deviceId = stored
        } else {
            let generated = "ios_\(UUID().uuidString)"
            defaults.set(generated, forKey: "device_id")
            deviceId = generated
        }
        deviceLabel = DeviceInfo.deviceLabel
        state.localDeviceLabel = deviceLabel
        if let contentRepository {
            state.siteConfig = contentRepository.siteConfig
            if let stored = defaults.string(forKey: "app_locale"), !stored.isEmpty {
                state.locale = stored
            } else {
                state.locale = Locales.resolve(defaultLocale: contentRepository.siteConfig.defaultLocale)
            }
        }
        state.darkTheme = defaults.bool(forKey: "app_dark_theme")
        cleanupTemporaryFiles()
        log("ready")
        createRoom()
    }

    // MARK: - Settings

    public func setDarkTheme(_ enabled: Bool) {
        state.darkTheme = enabled
        defaults.set(enabled, forKey: "app_dark_theme")
    }

    public func setLocale(_ locale: String) {
        state.locale = locale
        defaults.set(locale, forKey: "app_locale")
    }

    // MARK: - Room lifecycle

    public func createRoom() {
        state.joiningRoom = true
        state.roomError = ""
        // `codeError` is deliberately left alone here: it clears in
        // `handleCreatedRoom`. Clearing it at the start of every attempt would
        // blink the banner off and back on once a second while retrying.
        lastCreateAttemptMs = currentMilliseconds()
        let origin = state.origin
        let deviceId = deviceId
        let deviceLabel = deviceLabel
        Task { [weak self] in
            guard let self else { return }
            do {
                let client = RoomApiClient(origin: origin)
                let created = try await client.create(deviceId: deviceId, deviceLabel: deviceLabel)
                self.handleCreatedRoom(created, origin: origin)
            } catch let error as RoomApiError {
                self.state.joiningRoom = false
                self.state.codeError = error.message
                self.log("room: create failed \(error.code)")
                self.scheduleCreateRetry()
            } catch {
                self.state.joiningRoom = false
                self.state.codeError = "Connection failed."
                self.log("room: create failed \((error as NSError).code)")
                self.scheduleCreateRetry()
            }
        }
    }

    /// Keeps retrying room setup until a code exists.
    ///
    /// A fresh install attempts this at launch, which is exactly when iOS has
    /// not answered the local network prompt yet: the permission gates the app's
    /// whole Wi-Fi path, so the request fails with `NSURLErrorNotConnectedToInternet`
    /// and no code ever appears. Nothing else retries it, so the Connect tab
    /// would sit empty until the user pressed "Create code" by hand.
    ///
    /// The schedule never gives up. A finite ladder is what let a first launch
    /// fail permanently: the user has no reason to answer a permission prompt
    /// within the first minute, and once the ladder ran out nothing fetched a
    /// code again until the next launch.
    private func scheduleCreateRetry() {
        guard createRetryTask == nil, state.createdCode == nil, state.joinedRoom == nil else { return }
        let delay = CreateRetryPolicy.delaySeconds(attempt: createRetryAttempt)
        createRetryAttempt += 1
        log("room: create retry \(createRetryAttempt) in \(Int(delay))s")
        armCreateRetry(after: delay)
    }

    /// Arms the next create attempt after an explicit delay, without advancing
    /// the ladder. Used when the foreground path has to wait out the minimum gap.
    private func scheduleCreateRetry(after delay: Double) {
        guard createRetryTask == nil, state.createdCode == nil, state.joinedRoom == nil else { return }
        log("room: create retry in \(String(format: "%.1f", delay))s")
        armCreateRetry(after: delay)
    }

    private func armCreateRetry(after delay: Double) {
        createRetryTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            guard !Task.isCancelled else { return }
            guard let self else { return }
            self.createRetryTask = nil
            // A code or room that appeared while this was sleeping means the
            // retry is no longer wanted — firing anyway would discard a room the
            // user had already joined. A create or join still running owns the
            // continuation and arms the next retry itself.
            guard self.state.createdCode == nil, self.state.joinedRoom == nil,
                  !self.state.joiningRoom else { return }
            self.createRoom()
        }
    }

    private func cancelCreateRetry() {
        createRetryTask?.cancel()
        createRetryTask = nil
        createRetryAttempt = 0
    }

    public func joinRoom(code: String) {
        let normalized = PairingCodes.normalizeCode(code)
        guard PairingCodes.isUsableCode(normalized) else {
            state.roomError = "Enter a valid 8-digit code."
            return
        }
        state.joiningRoom = true
        state.roomError = ""
        let origin = state.origin
        let deviceId = deviceId
        let deviceLabel = deviceLabel
        let roomKey = deriveRoomKey(normalized)
        Task { [weak self] in
            guard let self else { return }
            do {
                let client = RoomApiClient(origin: origin)
                let joined = try await client.join(roomKey: roomKey, deviceId: deviceId, deviceLabel: deviceLabel)
                self.state.connectedPeerCode = normalized
                self.handleJoinedRoom(joined, origin: origin)
            } catch let error as RoomApiError {
                self.state.joiningRoom = false
                self.state.roomError = error.message
                if error.code == "expired_code" || error.code == "invalid_request" {
                    self.state.invalidatedCodes.insert(normalized)
                }
                self.log("room: join failed \(error.code)")
            } catch {
                self.state.joiningRoom = false
                self.state.roomError = "Connection failed."
                self.log("room: join failed")
            }
        }
    }

    public func joinScannedCode(_ raw: String?) {
        guard let code = PairingCodes.pairingCode(fromScanResult: raw) else {
            state.roomError = "No pairing code found in the scanned QR."
            return
        }
        joinRoom(code: code)
    }

    private func handleCreatedRoom(_ created: CreatedRoom, origin: String) {
        cancelCreateRetry()
        state.joiningRoom = false
        state.roomError = ""
        state.codeError = ""
        state.origin = origin
        state.createdCode = created.code
        state.connectedPeerCode = nil
        state.joinedRoom = created.joined()
        state.pairingExpiresAtMs = iso8601Milliseconds(created.pairingExpiresAt)
        state.pairingCountdownPausedAtMs = nil
        state.nowMs = currentMilliseconds()
        resetPeerConnection(clearTransfers: true)
        connectSignaling()
        startPairingCodeAutoRefresh()
        log("room: created \(created.code)")
    }

    private func handleJoinedRoom(_ joined: JoinedRoom, origin: String) {
        cancelCreateRetry()
        state.joiningRoom = false
        state.codeError = ""
        state.origin = origin
        state.joinedRoom = joined
        state.pairingExpiresAtMs = iso8601Milliseconds(joined.pairingExpiresAt)
        state.pairingCountdownPausedAtMs = nil
        state.nowMs = currentMilliseconds()
        resetPeerConnection(clearTransfers: true)
        connectSignaling()
        startPairingCodeAutoRefresh()
        log("room: joined \(state.connectedPeerCode ?? "")")
    }

    public func copyCode() {
        guard let code = state.createdCode else { return }
        UIPasteboard.general.string = code
        state.codeCopied = true
    }

    public func clearCodeCopied() {
        state.codeCopied = false
    }

    public func setRoomCode(_ value: String) {
        state.peerCode = PairingCodes.normalizeCode(value)
    }

    public func cancelAllLocalTransfers() {
        for card in state.localTransfers {
            transfer?.cancelTransfer(fileId: card.fileId)
        }
    }

    public func disconnect() {
        let room = state.joinedRoom
        closeSignaling(clearReconnect: true)
        resetPeerConnection(clearTransfers: true)
        state.joinedRoom = nil
        state.createdCode = nil
        state.peerCode = ""
        state.connectedPeerCode = nil
        state.pairingExpiresAtMs = nil
        state.signalingReady = false
        state.channelReady = false
        state.connectionPath = .unknown
        state.directConnectionFailed = false
        state.directConnectionFailureNotice = false
        state.peerDeviceLabel = ""
        stopHeartbeat()
        if let room {
            let origin = state.origin
            Task { [weak self] in
                let client = RoomApiClient(origin: origin)
                _ = try? await client.close(room)
                self?.log("room: closed")
            }
        }
        createRoom()
    }

    // MARK: - Signaling

    private func connectSignaling() {
        guard let room = state.joinedRoom else { return }
        closeSignaling(clearReconnect: true)
        let client = SignalingClient(origin: state.origin, onEvent: { [weak self] envelope in
            Task { @MainActor [weak self] in
                self?.handleSignal(envelope)
            }
        }, onDisconnect: { [weak self] reason in
            Task { @MainActor [weak self] in
                self?.handleSignalingDisconnect(reason)
            }
        })
        signaling = client
        client.connect(auth: .auth(roomId: room.roomId, deviceId: room.deviceId, sessionToken: room.sessionToken))
        log("signaling: connecting")
    }

    private func handleSignal(_ envelope: SignalingEnvelope) {
        switch envelope.type {
        case "auth_ok":
            state.signalingReady = true
            reconnectAttempt = 0
            log("signaling: ready")
            startDirectFailureTimer()
            startHeartbeat()
        case "peer_joined":
            let peerId = envelope.from ?? ""
            if !peerId.isEmpty {
                lastPeerId = peerId
                state.peerDeviceLabel = peerLabel(from: envelope.payload)
            }
            log("signaling: peer joined")
            ensurePeer()
            maybeCreateOffer()
        case "peer_left":
            state.peerDeviceLabel = ""
            state.channelReady = false
            state.connectionPath = .unknown
            log("signaling: peer left")
        case "room_closed":
            log("signaling: room closed")
            resetAfterRemoteRoomClosed()
        case "error":
            let message = envelope.error ?? "signaling error"
            log("signaling: error \(message)")
            if message.contains("target peer is not connected") {
                state.roomError = "The peer is not connected yet. Try again."
            }
        case "__invalid__":
            log("signaling: invalid message")
        case "offer", "answer", "ice_candidate":
            ensurePeer()
            peer?.handleSignal(envelope)
        default:
            break
        }
    }

    private func handleSignalingDisconnect(_ reason: String) {
        state.signalingReady = false
        guard state.joinedRoom != nil else { return }
        log("signaling: disconnected \(reason)")
        scheduleSignalingReconnect(reason: reason)
    }

    private func scheduleSignalingReconnect(reason: String) {
        if reconnectTask != nil { return }
        state.transientTransportReconnecting = state.joinedRoom != nil
        reconnectDeadlineMs = currentMilliseconds() + Self.reconnectWindowMs
        reconnectAttempt = 1
        reconnectTask = Task { [weak self] in
            await self?.reconnectLoop()
        }
    }

    private func reconnectLoop() async {
        defer {
            // Leaving the loop means the room is gone, the signaling is back,
            // or the five-minute window ran out. None of those is a transient
            // reconnect any more, and a stale flag would keep the conversation
            // on screen and the composer enabled.
            state.transientTransportReconnecting = false
        }
        while currentMilliseconds() <= reconnectDeadlineMs, state.joinedRoom != nil, !state.signalingReady {
            let delayMs = min(UInt64(1000 * reconnectAttempt), 10_000)
            reconnectAttempt += 1
            try? await Task.sleep(nanoseconds: delayMs * 1_000_000)
            if Task.isCancelled { return }
            if state.joinedRoom == nil || state.signalingReady { return }
            log("signaling: reconnecting (attempt \(reconnectAttempt))")
            connectSignaling()
            return
        }
        if !state.signalingReady, currentMilliseconds() > reconnectDeadlineMs {
            state.roomError = "Signaling connection was lost. Reconnect to continue."
        }
        reconnectTask = nil
    }

    private func closeSignaling(clearReconnect: Bool) {
        if clearReconnect {
            reconnectTask?.cancel()
            reconnectTask = nil
            reconnectAttempt = 0
            state.transientTransportReconnecting = false
        }
        signaling?.close()
        signaling = nil
    }

    // MARK: - WebRTC

    private func maybeCreateOffer() {
        guard state.signalingReady, !state.channelReady, !lastPeerId.isEmpty,
              let room = state.joinedRoom, room.role == "initiator", let peer = peer
        else { return }
        peer.createOffer(to: lastPeerId)
    }

    private var lastPeerId = ""

    private func ensurePeer() {
        if peer != nil { return }
        guard state.joinedRoom != nil else { return }
        // Direct-only policy: drop every STUN/TURN entry the manifest may
        // still carry so the connection can only use host candidates.
        let directOnlyServers = (state.siteConfig?.rtc.iceServers ?? []).filter { server in
            server.urls.allSatisfy { url in
                let lowered = url.lowercased()
                return !lowered.hasPrefix("stun:") && !lowered.hasPrefix("turn")
            }
        }
        let generation = peerGeneration
        let runtime = PeerRuntime(
            factory: PeerRuntime.sharedFactory(),
            iceServers: directOnlyServers,
            sendSignal: { [weak self] envelope in
                Task { @MainActor [weak self] in
                    guard let self, self.peerGeneration == generation else { return }
                    self.signaling?.send(envelope)
                }
            },
            onChannelReady: { [weak self] adapter in
                Task { @MainActor [weak self] in
                    guard let self, self.peerGeneration == generation else { return }
                    self.attachChannel(adapter)
                }
            },
            onConnectionState: { [weak self] status in
                Task { @MainActor [weak self] in
                    guard let self, self.peerGeneration == generation else { return }
                    self.handlePeerConnectionState(status)
                }
            },
        )
        peer = runtime
        startDirectFailureTimer()
    }

    private func resetPeerConnection(clearTransfers: Bool) {
        peerGeneration += 1
        transfer?.cleanup()
        transfer = nil
        peer?.close()
        peer = nil
        lastPeerId = ""
        state.channelReady = false
        state.connectionPath = .unknown
        if clearTransfers {
            state.localTransfers = []
            state.peerTransfers = []
        }
        state.clearChatForNewSession()
    }

    private func handlePeerConnectionState(_ status: PeerConnectionStatus) {
        switch status {
        case .connected:
            state.connectionPath = .direct
        case .failed:
            if state.channelReady {
                handleChannelLost(reason: "webrtc: connection failed")
            } else if state.signalingReady {
                failDirectConnection()
            }
        case .disconnected:
            // ICE may self-heal from a transient loss; the datachannel close
            // callback (or a follow-up `.failed`) owns the paired-state reset.
            if !state.channelReady {
                state.connectionPath = .unknown
            }
        case .closed:
            if state.channelReady {
                handleChannelLost(reason: "webrtc: connection closed")
            } else {
                state.connectionPath = .unknown
            }
        case .connecting:
            break
        }
        log("webrtc: \(String(describing: status))")
    }

    /// Same state teardown as `peer_left`: drop the paired layout but keep the
    /// room and peer runtime so the next `peer_joined` renegotiates a fresh
    /// channel. Queued send items stay pending and become visible on the
    /// Share tab.
    private func handleChannelLost(reason: String) {
        if state.applyChannelLost() {
            log(reason)
        }
    }

    private func attachChannel(_ adapter: ChannelAdapter) {
        directFailureTask?.cancel()
        directFailureTask = nil
        let receivedDirectory = Self.receivedDirectory()
        let room = state.joinedRoom
        let session = TransferSession(
            channel: adapter,
            limits: room?.limits ?? defaultTransferLimits(),
            receivedDirectory: receivedDirectory,
        )
        session.onLog = { [weak self] message in
            Task { @MainActor [weak self] in self?.log(message) }
        }
        session.onReceivedFile = { [weak self] info in
            Task { @MainActor [weak self] in
                self?.state.receivedFiles.insert(info, at: 0)
            }
        }
        session.onLocalTransfer = { [weak self] card in
            Task { @MainActor [weak self] in self?.applyLocalTransferCard(card) }
        }
        session.onPeerTransfer = { [weak self] card in
            Task { @MainActor [weak self] in self?.applyPeerTransferCard(card) }
        }
        session.onTextMessage = { [weak self] message in
            Task { @MainActor [weak self] in self?.applyChatMessage(message) }
        }
        session.onRecalled = { [weak self] recall in
            Task { @MainActor [weak self] in self?.applyRecall(recall) }
        }
        // Bridge DataChannel traffic (WebRTC internal threads) into the
        // transfer session's main actor. The open-state callback from the
        // runtime has already fired by the time we get here, so the adapter's
        // closures are now exclusively transfer traffic.
        adapter.onText = { [weak session] text in
            Task { @MainActor [weak session] in await session?.handleText(text) }
        }
        adapter.onBinary = { [weak session] bytes in
            Task { @MainActor [weak session] in session?.handleBinary(bytes) }
        }
        // PeerRuntime consumes the adapter's open-state callback until the
        // channel opens; every further state change is a close. Without this
        // bridge the paired layout survives an abrupt teardown (peer gone,
        // network drop) with a stale `channelReady`, so the pending-send panel
        // stays hidden behind the connected UI and freshly picked files would
        // appear to vanish.
        adapter.onStateChange = { [weak self, weak adapter] open in
            guard !open else { return }
            Task { @MainActor [weak self] in
                // Identity check: a close dispatched from a stale channel
                // must not tear down a freshly re-paired session.
                guard let self, let adapter, self.transfer?.channel === adapter else { return }
                self.handleChannelLost(reason: "webrtc: datachannel closed")
            }
        }
        transfer = session
        state.channelReady = true
        // The channel is usable again, so a stale "not connected" banner goes.
        state.chatFailure = ""
        state.directConnectionFailed = false
        state.directConnectionFailureNotice = false
        state.connectionPath = .direct
        state.pairingCountdownPausedAtMs = currentMilliseconds()
        log("webrtc: datachannel open")
        flushPendingSendItems()
    }

    private func defaultTransferLimits() -> TransferLimits {
        // Server contract fallback (used only if the room response somehow
        // lacks limits): 200 MB cap, one active transfer, 256 KiB chunks.
        TransferLimits(maxFileSize: 209_715_200, maxActiveTransfers: 1, chunkSize: 262_144)
    }

    // MARK: - Transfer actions

    public func enqueueSendItems(_ items: [PendingSendItem]) {
        state.pendingSendItems.append(contentsOf: items)
        flushPendingSendItems()
    }

    // MARK: - Picked-file materialization

    /// The document picker runs in `asCopy: false` mode: it hands back the
    /// original security-scoped URLs the moment the user confirms, while the
    /// actual byte copy (what `asCopy: true` used to block the whole sheet on)
    /// happens here. Placeholder cards go up immediately, the copies run off
    /// the main actor, and each finished copy swaps its card for a real
    /// pending/transfer entry.
    public func enqueueDocumentPicks(_ urls: [URL]) {
        guard !urls.isEmpty else { return }
        let placeholders = urls.map { PreparingSendItem(name: $0.lastPathComponent) }
        state.preparingSendItems.append(contentsOf: placeholders)
        for (placeholder, url) in zip(placeholders, urls) {
            let mime = Self.mime(for: url)
            Task { [weak self] in
                let outcome = await Self.materializeDocument(at: url)
                self?.finishDocumentPick(placeholderId: placeholder.id, name: placeholder.name, mime: mime, outcome: outcome)
            }
        }
    }

    /// A picked folder: walked under one security scope, copied out into app
    /// temporary files through the same pick pipeline (placeholder cards,
    /// then `enqueueSendItems`), so progress and queueing behave exactly like a
    /// multi-file document pick. The scope closes before the copies start, so
    /// the walk and the copies never overlap. An empty tree and a capped tree
    /// leave a notice for the stream (Android `sendFolderTree`).
    public func enqueueFolderPick(_ folder: URL) {
        state.folderPickNotice = nil
        Task { [weak self] in
            let pick = await Self.materializeFolder(at: folder)
            guard let self else { return }
            self.log("folder: \(pick.urls.count) file(s), truncated=\(pick.truncated)")
            self.state.folderPickNotice = folderPickNotice(fileCount: pick.urls.count, truncated: pick.truncated)
            self.enqueueDocumentPicks(pick.urls)
        }
    }

    public func dismissFolderPickNotice() {
        state.folderPickNotice = nil
    }

    /// Walks the folder while its security scope is open and copies every
    /// found file out before the scope closes. `nonisolated async`, so the
    /// enumeration and the copies run off the main actor; the start/stop pair
    /// is balanced in one scope, including when the walk finds nothing.
    nonisolated static func materializeFolder(
        at folder: URL,
        limit: Int = maxFolderFiles,
    ) async -> FolderMaterialization {
        let scoped = folder.startAccessingSecurityScopedResource()
        defer { if scoped { folder.stopAccessingSecurityScopedResource() } }
        let walk = collectFolderTreeFiles(root: folder, limit: limit)
        var urls: [URL] = []
        urls.reserveCapacity(walk.files.count)
        for file in walk.files {
            if let materialized = await materializeDocument(at: file.url).url {
                urls.append(materialized)
            }
        }
        return FolderMaterialization(urls: urls, truncated: walk.truncated)
    }

    /// Photo-library loads report per item: one large video must not hold the
    /// other picked files hostage the way the old wait-for-all batching did.
    public func beginPhotoPreparation(_ placeholders: [PreparingSendItem]) {
        guard !placeholders.isEmpty else { return }
        state.preparingSendItems.append(contentsOf: placeholders)
    }

    public func finishPhotoPreparation(id: String, photo: PickedPhoto?) {
        state.preparingSendItems.removeAll { $0.id == id }
        guard let photo else { return }
        enqueueSendItems([PendingSendItem(url: photo.url, name: photo.name, mime: photo.mime)])
    }

    private func finishDocumentPick(placeholderId: String, name: String, mime: String, outcome: MaterializedFile) {
        state.preparingSendItems.removeAll { $0.id == placeholderId }
        guard let url = outcome.url else {
            state.roomError = "Unable to read \(name)."
            log("pick: \(name) materialize failed: \(outcome.errorMessage ?? "unknown error")")
            return
        }
        enqueueSendItems([PendingSendItem(url: url, name: name, mime: mime)])
    }

    static func mime(for url: URL) -> String {
        switch url.pathExtension.lowercased() {
        case "png": return "image/png"
        case "jpg", "jpeg": return "image/jpeg"
        case "gif": return "image/gif"
        case "heic": return "image/heic"
        case "webp": return "image/webp"
        case "mp4", "m4v": return "video/mp4"
        case "mov": return "video/quicktime"
        case "mp3": return "audio/mpeg"
        case "wav": return "audio/wav"
        case "pdf": return "application/pdf"
        case "txt": return "text/plain"
        case "csv": return "text/csv"
        case "zip": return "application/zip"
        case "json": return "application/json"
        default: return "application/octet-stream"
        }
    }

    /// Copies a security-scoped picker URL into an app-owned temporary file.
    /// A `nonisolated async` function: the blocking copy runs on the global
    /// executor and never touches the main actor.
    nonisolated private static func materializeDocument(at url: URL) async -> MaterializedFile {
        let ext = url.pathExtension.isEmpty ? "dat" : url.pathExtension
        let destination = FileManager.default.temporaryDirectory
            .appendingPathComponent("pick-\(UUID().uuidString).\(ext)")
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        // Coordinated read keeps the copy consistent with File Provider
        // processes and materializes on-demand iCloud files.
        let coordinator = NSFileCoordinator(filePresenter: nil)
        var coordinatorError: NSError?
        var copyError: String?
        coordinator.coordinate(readingItemAt: url, options: [], error: &coordinatorError) { readURL in
            do {
                if FileManager.default.fileExists(atPath: destination.path) {
                    try FileManager.default.removeItem(at: destination)
                }
                try FileManager.default.copyItem(at: readURL, to: destination)
            } catch {
                copyError = error.localizedDescription
            }
        }
        if let coordinatorError {
            try? FileManager.default.removeItem(at: destination)
            return MaterializedFile(url: nil, errorMessage: coordinatorError.localizedDescription)
        }
        if let copyError {
            try? FileManager.default.removeItem(at: destination)
            return MaterializedFile(url: nil, errorMessage: copyError)
        }
        return MaterializedFile(url: destination, errorMessage: nil)
    }

    private func flushPendingSendItems() {
        // The channel object can outlive the actual connection (suspended app,
        // peer gone). Dequeueing before checking would silently swallow the
        // items: offerFile only logs when the send fails. Items stay queued
        // here and are flushed by the next attachChannel.
        guard state.channelReady, let transfer, transfer.channel.isOpen else { return }
        let items = state.pendingSendItems
        state.pendingSendItems.removeAll()
        for item in items {
            guard let handle = try? FileContentHandle(url: item.url, name: item.name, mime: item.mime) else {
                state.roomError = "Unable to read \(item.name)."
                continue
            }
            transfer.offerFile(handle)
        }
    }

    public func sendGeneratedTestFile() {
        let payload = Data("LanShare iOS test file\n".utf8)
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("lanshare-test-\(Int.random(in: 1000 ... 9999)).txt")
        do {
            try payload.write(to: url)
        } catch {
            state.roomError = "Unable to create the test file."
            return
        }
        enqueueSendItems([PendingSendItem(url: url, name: url.lastPathComponent, mime: "text/plain")])
    }

    public func acceptIncoming(fileId: String? = nil) {
        transfer?.acceptIncoming(fileId: fileId)
    }

    public func acceptAllIncoming() {
        transfer?.acceptAllIncoming()
    }

    public func cancelTransfer(fileId: String) {
        transfer?.cancelTransfer(fileId: fileId)
    }

    public func retryTransfer(fileId: String) {
        transfer?.retry(fileId: fileId)
    }

    public func removePendingSendItem(id: String) {
        state.pendingSendItems.removeAll { $0.id == id }
    }

    /// Flushes the queue on demand (the conversation's 「全部发送」). Same path
    /// the channel-open callback uses, so a queue that cannot go out yet stays
    /// visible instead of being swallowed.
    public func flushQueuedSendItems() {
        flushPendingSendItems()
    }

    // MARK: - Chat

    /// Sends a typed chat line, or falls back to the web rule: a draft over the
    /// text limit is offered as a `.txt` file instead of being lost.
    public func sendChatText(_ text: String) {
        if isTextTooLarge(text) {
            offerOversizeDraftAsFile(text)
            return
        }
        let sent = transfer?.sendText(text) ?? false
        if !sent {
            state.chatFailure = chatChannelNotReady
            log("chat: text not sent, channel not ready")
        }
    }

    /// Retracts a text message or file bubble on both sides (web
    /// `TransferSession.recall`). The session reports the recall back through
    /// `onRecalled`, which flips the entry in place; applying it here as well
    /// keeps the list right when the session is already gone. It is idempotent.
    public func recallChatEntry(target: RecallTarget, targetId: String) {
        transfer?.recall(target: target, targetId: targetId)
        applyRecall(TransferRecall(target: target, targetId: targetId))
    }

    private func applyChatMessage(_ message: ChatMessageState) {
        state.chatMessages.append(message)
    }

    /// Marks the matching text message or file bubble as recalled; every
    /// other entry is untouched. A recalled file also leaves the Receive
    /// list: a retracted file must not stay offered for export. The bytes
    /// already written under Documents/Received are kept — the recall note in
    /// the stream is what explains they are gone — so only the list entry goes
    /// (the web's recall path drops the received row the same way).
    private func applyRecall(_ recall: TransferRecall) {
        switch recall.target {
        case .text:
            for index in state.chatMessages.indices where state.chatMessages[index].id == recall.targetId {
                state.chatMessages[index].recalled = true
            }
        case .file:
            for index in state.localTransfers.indices where state.localTransfers[index].fileId == recall.targetId {
                state.localTransfers[index] = state.localTransfers[index].recalledCopy()
            }
            for index in state.peerTransfers.indices where state.peerTransfers[index].fileId == recall.targetId {
                state.peerTransfers[index] = state.peerTransfers[index].recalledCopy()
            }
            state.receivedFiles.removeAll { $0.fileId == recall.targetId }
        }
    }

    /// Aborts an incoming transfer in flight (the bubble's cancel slot, design
    /// L6). The session reports the card back as canceled, which leaves the
    /// bubble in place showing 已取消 rather than dropping it, so the entry is
    /// kept here on purpose (design O3, unlike the web's row removal).
    public func cancelIncomingTransfer(fileId: String) {
        transfer?.cancelTransfer(fileId: fileId)
    }

    /// Copies a text bubble to the pasteboard (the long-press 复制 action).
    /// Returns whether anything was copied.
    @discardableResult
    public func copyChatText(_ text: String) -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return false }
        UIPasteboard.general.string = text
        return true
    }

    /// The oversize draft as the file the web offers: `message-<UTC stamp>.txt`
    /// with `text/plain`. On an open channel it is offered right away; without
    /// one the bytes are written to a temporary file and queued through the
    /// pending-send path, so the draft is not lost while the connection is
    /// being made.
    private func offerOversizeDraftAsFile(_ text: String) {
        let payload = Data(text.utf8)
        let name = oversizeDraftName(at: currentMilliseconds())
        if let transfer, transfer.channel.isOpen, state.channelReady {
            transfer.offerFile(MemoryContentHandle(name: name, data: payload, mime: "text/plain"))
            log("chat: oversize text offered as \(name) (\(payload.count) bytes)")
            return
        }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        do {
            try payload.write(to: url)
        } catch {
            state.chatFailure = chatChannelNotReady
            log("chat: oversize text draft failed \(error.localizedDescription)")
            return
        }
        enqueueSendItems([PendingSendItem(url: url, name: name, mime: "text/plain")])
        log("chat: oversize text queued as \(name) (\(payload.count) bytes)")
    }

    private func applyLocalTransferCard(_ card: TransferCardState?) {
        guard let card else { return }
        (state.localTransfers, state.peerTransfers) = TransferCards.applyKeepingCanceled(
            card,
            isLocal: true,
            local: state.localTransfers,
            peer: state.peerTransfers,
        )
    }

    private func applyPeerTransferCard(_ card: TransferCardState?) {
        guard let card else { return }
        (state.localTransfers, state.peerTransfers) = TransferCards.applyKeepingCanceled(
            card,
            isLocal: false,
            local: state.localTransfers,
            peer: state.peerTransfers,
        )
    }

    // MARK: - Interruption policy (foreground-only)

    /// The app came back to the foreground. On a fresh install the first launch
    /// is blocked until the local network prompt is answered, and answering it
    /// makes the app active again — that is the moment the permission stops
    /// gating the network path, so a missing code is fetched right away instead
    /// of waiting out the retry delay.
    public func handleScenePhaseActive() {
        markDeadChannelAfterForeground()
        switch CreateRetryPolicy.foregroundAction(
            hasCode: state.createdCode != nil,
            hasRoom: state.joinedRoom != nil,
            busy: state.joiningRoom,
            millisecondsSinceLastAttempt: currentMilliseconds() - lastCreateAttemptMs,
        ) {
        case .none:
            return
        case .retryNow:
            cancelCreateRetry()
            log("room: create retry after foreground")
            createRoom()
        case .retryAfter(let seconds):
            // Too soon after the last attempt to fire another one, but the wait
            // still has to end in a retry: dropping it here is what left a first
            // launch with no code and nothing scheduled to fetch one.
            scheduleCreateRetry(after: seconds)
        }
    }

    /// Local network access was granted. The prompt gates the app's whole Wi-Fi
    /// path, so every request made while it was on screen failed; this is the
    /// signal that the path is usable again.
    ///
    /// It is fed by the permission probe rather than by the scene phase: an
    /// alert can be answered without the scene ever leaving `.active`, in which
    /// case no foreground event arrives to trigger the retry.
    public func handleLocalNetworkGranted() {
        guard state.createdCode == nil, state.joinedRoom == nil else { return }
        guard !state.joiningRoom else { return }
        cancelCreateRetry()
        log("room: create retry after local network granted")
        createRoom()
    }

    /// A paired session idles through backgrounding with the channel object
    /// kept alive, but iOS tears the connection down while suspended. The
    /// stale `channelReady` would keep the Share tab in its paired layout and
    /// newly picked files would sit in a queue the user cannot see, so drop
    /// the paired state exactly like `peer_left` does: the pending-send panel
    /// becomes visible and the next `peer_joined` re-attaches a fresh channel.
    private func markDeadChannelAfterForeground() {
        guard state.channelReady, let transfer, !transfer.channel.isOpen else { return }
        handleChannelLost(reason: "webrtc: datachannel closed while backgrounded")
    }

    public func handleScenePhaseBackground() {
        cancelCreateRetry()
        guard state.channelReady, let session = transfer else { return }
        let hasActiveTransfer = state.localTransfers.contains {
            $0.status == TransferCardStatus.transferring || $0.status == TransferCardStatus.waitingForAcceptance
        } || state.peerTransfers.contains {
            $0.status == TransferCardStatus.receivingFromWeb
        }
        guard hasActiveTransfer else { return }
        session.cleanup()
        transfer = nil
        state.channelReady = false
        state.connectionPath = .unknown
        markActiveTransfersInterrupted()
        log("transfer: interrupted by background")
    }

    private func markActiveTransfersInterrupted() {
        for index in state.localTransfers.indices {
            let status = state.localTransfers[index].status
            if status == TransferCardStatus.transferring || status == TransferCardStatus.waitingForAcceptance {
                state.localTransfers[index].status = "\(TransferCardStatus.errorPrefix)interrupted"
                state.localTransfers[index].progressPercent = nil
                state.localTransfers[index].actionLabel = TransferCardAction.retry
                state.localTransfers[index].canRetry = true
            }
        }
        for index in state.peerTransfers.indices {
            if state.peerTransfers[index].status == TransferCardStatus.receivingFromWeb {
                state.peerTransfers[index].status = "\(TransferCardStatus.errorPrefix)interrupted"
                state.peerTransfers[index].progressPercent = nil
                state.peerTransfers[index].actionLabel = nil
            }
        }
    }

    // MARK: - Timers

    private func startHeartbeat() {
        stopHeartbeat()
        heartbeatTask = Task { [weak self] in
            await self?.heartbeatLoop()
        }
    }

    private func stopHeartbeat() {
        heartbeatTask?.cancel()
        heartbeatTask = nil
    }

    private func heartbeatLoop() async {
        while !Task.isCancelled, state.joinedRoom != nil {
            if let room = state.joinedRoom {
                let origin = state.origin
                do {
                    let response = try await RoomApiClient(origin: origin).heartbeat(room)
                    if !response.ok {
                        log("heartbeat: not ok")
                    }
                } catch let error as RoomApiError where error.code == "invalid_session" {
                    log("heartbeat: invalid session")
                    state.roomError = "The room session expired. Reconnect to continue."
                    resetAfterRemoteRoomClosed()
                    return
                } catch {
                    log("heartbeat: failed")
                }
            }
            try? await Task.sleep(nanoseconds: Self.heartbeatIntervalMs * 1_000_000)
        }
    }

    private func startPairingCodeAutoRefresh() {
        countdownTask?.cancel()
        countdownTask = Task { [weak self] in
            await self?.countdownLoop()
        }
    }

    private func countdownLoop() async {
        while !Task.isCancelled {
            state.nowMs = currentMilliseconds()
            guard state.joinedRoom != nil else { return }
            let countdown = state.pairingCountdownSeconds ?? 0
            if countdown == 0, !state.channelReady, state.createdCode != nil {
                log("room: code expired, refreshing")
                createRoom()
                return
            }
            try? await Task.sleep(nanoseconds: 1_000_000_000)
        }
    }

    private func startDirectFailureTimer() {
        directFailureTask?.cancel()
        guard state.isMonitoringDirectConnectionAttempt else { return }
        directFailureTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(Self.directFailureTimeoutMs * 1_000_000))
            guard !Task.isCancelled else { return }
            self?.directFailureTimeout()
        }
    }

    private func stopDirectFailureTimer() {
        directFailureTask?.cancel()
        directFailureTask = nil
    }

    private func directFailureTimeout() {
        guard state.isMonitoringDirectConnectionAttempt else { return }
        failDirectConnection()
    }

    private func failDirectConnection() {
        guard !state.directConnectionFailed else { return }
        stopDirectFailureTimer()
        state.directConnectionFailed = true
        state.directConnectionFailureNotice = true
        state.roomError = "Direct connection failed. Check that both devices are on the same Wi-Fi network."
        log("webrtc: direct connection failed")
    }

    /// Dismisses the failure dialog only; the failure itself stays visible in
    /// the status surfaces until it recovers, mirroring Android.
    public func clearDirectConnectionFailure() {
        state.directConnectionFailureNotice = false
    }

    /// The Connect banner's close button: hide the failure entirely.
    public func dismissDirectConnectionFailure() {
        state.directConnectionFailed = false
        state.directConnectionFailureNotice = false
        state.roomError = ""
    }

    public func reportError(_ message: String) {
        state.roomError = message
    }

    private func resetAfterRemoteRoomClosed() {
        closeSignaling(clearReconnect: true)
        resetPeerConnection(clearTransfers: true)
        stopHeartbeat()
        stopDirectFailureTimer()
        state.joinedRoom = nil
        state.createdCode = nil
        state.peerCode = ""
        state.connectedPeerCode = nil
        state.pairingExpiresAtMs = nil
        state.signalingReady = false
        state.channelReady = false
        state.connectionPath = .unknown
        state.peerDeviceLabel = ""
        state.roomError = "The room was closed by the peer."
        createRoom()
    }

    // MARK: - Helpers

    private func log(_ message: String) {
        state.logs.append(message)
        if state.logs.count > 200 {
            state.logs.removeFirst(state.logs.count - 200)
        }
    }

    private func cleanupTemporaryFiles() {
        let temporary = FileManager.default.temporaryDirectory
        if let contents = try? FileManager.default.contentsOfDirectory(
            at: temporary,
            includingPropertiesForKeys: nil,
        ) {
            for url in contents where url.lastPathComponent.hasPrefix("incoming-")
                || url.lastPathComponent.hasPrefix("pick-")
                || url.lastPathComponent.hasPrefix("lanshare-test-")
                // Oversize chat drafts offered as `message-<stamp>.txt` files.
                || url.lastPathComponent.hasPrefix("message-") {
                try? FileManager.default.removeItem(at: url)
            }
        }
    }

    nonisolated private static func receivedDirectory() -> URL {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return documents.appendingPathComponent("Received", isDirectory: true)
    }
}

/// Outcome of copying a picked document into app-owned storage. Plain
/// value fields so it can cross the actor boundary back to the
/// main-actor view model.
struct MaterializedFile: Sendable {
    let url: URL?
    let errorMessage: String?
}

/// A picked folder after its scope closed: copies in the app's temporary
/// directory, plus whether the walk hit the file cap.
struct FolderMaterialization: Sendable {
    let urls: [URL]
    let truncated: Bool
}

/// Why a chat action could not be delivered: the text channel is not usable.
let chatChannelNotReady = "channel_not_ready"

func iso8601Milliseconds(_ value: String) -> Int64? {
    let formatter = ISO8601DateFormatter()
    formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    if let date = formatter.date(from: value) {
        return Int64(date.timeIntervalSince1970 * 1000)
    }
    formatter.formatOptions = [.withInternetDateTime]
    if let date = formatter.date(from: value) {
        return Int64(date.timeIntervalSince1970 * 1000)
    }
    return nil
}

func currentMilliseconds() -> Int64 {
    Int64(Date().timeIntervalSince1970 * 1000)
}

private func peerLabel(from payload: JSONValue?) -> String {
    guard case .object(let object) = payload,
          case .string(let label) = object["deviceLabel"],
          !label.isEmpty
    else { return "" }
    return label
}
