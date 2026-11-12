import Foundation

public struct PendingSendItem: Identifiable, Equatable, Sendable {
    public let id: String
    public let name: String
    public let size: Int
    public let mime: String
    public let url: URL

    public init(url: URL, name: String, mime: String) {
        self.id = UUID().uuidString
        self.url = url
        self.name = name
        self.mime = mime
        let attributes = try? FileManager.default.attributesOfItem(atPath: url.path)
        size = (attributes?[.size] as? NSNumber)?.intValue ?? 0
    }
}

/// A picked file whose bytes are still being copied out of the picker's
/// source (photo library, Files provider, iCloud). Rendered as a spinner
/// card so the Share tab reflects the pick immediately instead of sitting
/// blank for the whole copy.
public struct PreparingSendItem: Identifiable, Equatable, Sendable {
    public let id: String
    public let name: String

    public init(id: String = UUID().uuidString, name: String) {
        self.id = id
        self.name = name
    }
}

public enum ConnectionPath: Equatable, Sendable {
    case unknown
    case direct

    public var label: String {
        switch self {
        case .unknown: return "Unknown"
        case .direct: return "Direct"
        }
    }
}

public enum PeerCodeValidation: Equatable, Sendable {
    case ok
    case invalid
    case selfCode
    case refreshed
}

public struct AppState: Sendable {
    public var locale: String = "en"
    public var darkTheme: Bool = false
    public var createdCode: String?
    public var connectedPeerCode: String?
    /// Peer-entered code, mirroring the Android `roomCode` field. Digit-filtered
    /// as the user types; never overwritten by the locally created code.
    public var peerCode: String = ""
    public var invalidatedCodes: Set<String> = []
    public var pairingExpiresAtMs: Int64?
    public var pairingCountdownPausedAtMs: Int64?
    public var nowMs: Int64 = 0
    public var codeCopied: Bool = false
    public var joiningRoom: Bool = false
    public var roomError: String = ""
    /// iOS local-network permission was answered with "Don't Allow".
    public var localNetworkRefused: Bool = false
    /// Payload shown in the host QR: a LAN HTTP URL when the browser bridge is up.
    public var lanQrPayload: String = ""
    /// Browser URL for a desktop on the same Wi-Fi, empty when unavailable.
    public var lanBrowserUrl: String = ""
    /// Why the local code could not be fetched. Kept apart from `roomError`,
    /// which also carries peer-code and transfer errors: a create failure belongs
    /// next to the local code, with a retry, not beside the peer-code field where
    /// it reads as a bad peer code. Non-empty means the code is unavailable.
    public var codeError: String = ""
    public var joinedRoom: JoinedRoom?
    public var siteConfig: PublicSiteConfig?
    public var localDeviceLabel: String = "iOS"
    public var peerDeviceLabel: String = ""
    public var localTransfers: [TransferCardState] = []
    public var peerTransfers: [TransferCardState] = []
    /// The conversation: chat lines and file bubbles share one ordered list.
    public var chatMessages: [ChatMessageState] = []
    /// Why the last chat action could not be delivered; empty once the channel
    /// is usable again. Kept apart from `roomError`, which names room problems.
    public var chatFailure: String = ""
    public var pendingSendItems: [PendingSendItem] = []
    public var preparingSendItems: [PreparingSendItem] = []
    public var receivedFiles: [ReceivedFileInfo] = []
    public var connectionPath: ConnectionPath = .unknown
    public var directConnectionFailed: Bool = false
    /// One-shot trigger for the failure dialog, mirroring Android's
    /// `directConnectionFailureNotice`: unlike `directConnectionFailed` this is
    /// cleared as soon as the dialog is dismissed, while the failure itself
    /// stays visible in the status surfaces until it recovers.
    public var directConnectionFailureNotice: Bool = false
    public var signalingReady: Bool = false
    public var channelReady: Bool = false
    /// A live room whose signaling is reconnecting. `channelReady` drops
    /// during that window while the session, its transfers and its history
    /// survive, so the conversation stays on screen (design: only the user's
    /// ‹ returns to the home page).
    public var transientTransportReconnecting: Bool = false
    public var scanningQR: Bool = false
    public var logs: [String] = ["ready"]
    /// The stream note left by the last folder pick (empty tree / capped),
    /// dismissed by the notice itself. Mirrors Android `folderPickNotice`.
    public var folderPickNotice: FolderPickNotice?

    public init() {}

    public var pairingCountdownSeconds: Int? {
        guard var expiresAtMs = pairingExpiresAtMs else { return nil }
        let referenceMs = pairingCountdownPausedAtMs ?? nowMs
        expiresAtMs -= referenceMs
        return expiresAtMs > 0 ? Int(ceil(Double(expiresAtMs) / 1000.0)) : 0
    }

    /// `MM:SS` pairing TTL, mirroring the Android `ttlLabel`.
    public var ttlLabel: String {
        guard let expiresAtMs = pairingExpiresAtMs else { return "--:--" }
        let referenceMs = pairingCountdownPausedAtMs ?? nowMs
        let seconds = max(0, Int((expiresAtMs - referenceMs + 999) / 1000))
        let capped = min(seconds, 120)
        return String(format: "%02d:%02d", capped / 60, capped % 60)
    }

    public var isPaired: Bool { channelReady }

    /// Live validation of the digits the user has typed into the peer-code
    /// field, matching Android's INVALID / SELF / REFRESHED rules.
    public var peerCodeValidation: PeerCodeValidation {
        let digits = PairingCodes.normalizeCode(peerCode)
        if digits.isEmpty { return .ok }
        if digits.count != 8 || PairingCodes.isWeakCode(digits) { return .invalid }
        if digits == createdCode { return .selfCode }
        if invalidatedCodes.contains(digits) { return .refreshed }
        return .ok
    }

    public var canJoinPeerCode: Bool {
        peerCode.count == 8 && peerCodeValidation == .ok && !joiningRoom
    }

    /// Actions are locked while connecting, mirroring `connectionActionsLocked`.
    public var connectionBusy: Bool {
        if channelReady { return false }
        if directConnectionFailed { return false }
        if joiningRoom { return true }
        if joinedRoom?.role == "peer" { return true }
        return joinedRoom?.role == "initiator" && !peerDeviceLabel.isEmpty
    }

    public var isPeerConnectionMode: Bool {
        !peerCode.isEmpty || joinedRoom?.role == "peer"
    }

    /// Whether this device is actually trying to reach a peer, mirroring
    /// Android's `shouldMonitorDirectConnectionAttempt`. Hosting a room and
    /// waiting for someone to join is not an attempt, so the direct-connection
    /// timeout must not treat an idle room as a failure.
    public var isMonitoringDirectConnectionAttempt: Bool {
        guard let role = joinedRoom?.role else { return false }
        if channelReady || directConnectionFailed { return false }
        return role == "peer" || !peerDeviceLabel.isEmpty
    }

    /// Top-bar phase pill, mirroring Android `phaseLabelText`.
    public var phaseLabel: String {
        if channelReady { return "Connected" }
        if joiningRoom || (joinedRoom?.role == "peer" && !channelReady && !directConnectionFailed) {
            return "Connecting"
        }
        return "Waiting"
    }

    /// Connection-status direction pill, mirroring `connectionDirectionText`.
    public var directionLabel: String {
        if isPeerConnectionMode && channelReady { return "Connected to peer" }
        if isPeerConnectionMode && signalingReady { return "Connecting to peer" }
        if isPeerConnectionMode { return "Checking peer code" }
        if channelReady { return "Peer connected to this device" }
        return "Waiting for another device to connect"
    }

    /// The pairing code shown once paired, mirroring `connectedPairingCode`.
    public var pairedCode: String? {
        if !channelReady { return nil }
        if joinedRoom?.role == "peer" {
            return connectedPeerCode ?? createdCode
        }
        return createdCode ?? connectedPeerCode
    }

    public var pendingIncomingCount: Int {
        peerTransfers.filter { $0.actionLabel == TransferCardAction.accept }.count
    }

    public var sendCount: Int {
        channelReady ? localTransfers.count : 0
    }

    /// Drops the paired layout after the data channel or peer connection was
    /// lost, with the exact state teardown of `peer_left`. Returns whether the
    /// teardown applied: the `channelReady` guard keeps a late close event
    /// from an old channel from clobbering a freshly re-paired session.
    /// Queued send items are intentionally kept so they stay visible in the
    /// Share tab's pending panel and flush after the next pairing.
    @discardableResult
    public mutating func applyChannelLost() -> Bool {
        guard channelReady else { return false }
        channelReady = false
        peerDeviceLabel = ""
        connectionPath = .unknown
        // A failed send is about the connection that just ended; the next
        // session starts clean (Android `resetConnectionUiState`). The
        // conversation itself survives a reconnect, as on Android.
        chatFailure = ""
        return true
    }

    /// Clears everything tied to one paired session when a fresh room takes
    /// over, mirroring Android `resetConnectionUiState`: the conversation does
    /// not leak from a previous peer into the next one. The folder-pick note
    /// belongs to the old conversation's stream (Android
    /// `folderPickNotice` is reset with it), so it goes too. Files already
    /// saved under Documents/Received are not deleted; the Receive list is
    /// emptied by `resetPeerConnection(clearTransfers:)` before this runs.
    public mutating func clearChatForNewSession() {
        chatMessages = []
        chatFailure = ""
        folderPickNotice = nil
    }
}