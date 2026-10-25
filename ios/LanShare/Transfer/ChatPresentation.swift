import Foundation

/// Pure presentation rules for the conversation page — which lives inside
/// the 传输 tab under the always-visible bottom bar (product decision
/// 2026-10-20, matching Android). Everything here is value-in /
/// value-out so the view layer stays a thin renderer and every rule is
/// covered by `ChatPresentationTests` without SwiftUI.
///
/// The timeline, the one-action-per-bubble rule, the sender labels, the
/// badge and the size format live in `ChatModel.swift`; this file adds only
/// the rules the view needs on top of them.

// MARK: - Routing

/// Whether a session is alive enough to keep the conversation interactive. A
/// transient reconnect drops `channelReady` while the session, its transfers
/// and its history survive. This is no longer a routing question — the three-tab
/// shell and its bar are always drawn — only the composer and the details gate.
public func chatSessionAlive(channelReady: Bool, reconnecting: Bool, hasRoom: Bool) -> Bool {
    channelReady || (reconnecting && hasRoom)
}

/// One bottom-bar item of the home, in bar order. The old 分享 and 接收 tabs
/// are gone: both pages live in 传输 as two sections, and 传输 now *is* the
/// conversation once a device is paired.
public enum HomeTab: Int, CaseIterable, Equatable, Sendable {
    /// Code / QR pairing page.
    case connect = 0
    /// 传输: the conversation — a chat-style empty state with 前往连接 while
    /// unpaired, the message stream once paired.
    case transfer = 1
    case settings = 2

    /// The bar label, a catalog key (English is the key text).
    public var label: String {
        switch self {
        case .connect: return "Connect"
        case .transfer: return "Transfer"
        case .settings: return "Settings"
        }
    }

    public var systemImage: String {
        switch self {
        case .connect: return "wifi"
        case .transfer: return "arrow.left.arrow.right"
        case .settings: return "gearshape"
        }
    }

    /// The tab for a stored index, clamped into the real range so a stale
    /// selection (say, 3 — Settings in the four-tab bar) can never select a
    /// tab that no longer exists.
    public init(selection: Int) {
        self = HomeTab(rawValue: selection) ?? .connect
    }

    /// One merged-bar badge for the 传输 tab: files waiting to go out plus peer
    /// offers waiting to be accepted. The old bar had a badge on each of 分享
    /// and 接收; with both sections on one tab they share one count.
    public static func tabBadge(_ tab: HomeTab, sendCount: Int, receiveCount: Int) -> Int {
        switch tab {
        case .connect, .settings: return 0
        case .transfer: return max(0, sendCount) + max(0, receiveCount)
        }
    }
}

/// The bar items the home draws, left to right.
public let homeTabItems: [HomeTab] = HomeTab.allCases

/// The tab the home page renders. There is no longer a bar-less home: the
/// three-item bar is drawn in every state, so the selection always resolves
/// straight through `HomeTab(selection:)` and a live session never rewrites
/// the user's choice.
public func chatHomeTab(selectedTab: Int) -> HomeTab {
    HomeTab(selection: selectedTab)
}

/// The tab a fresh pairing moves the shell to, or `nil` when it must stay put.
/// Only the first successful pairing of a room counts: `wasPaired` is sticky
/// for the room, so recovering from a transient reconnect (which drops
/// `channelReady` but keeps the room) never yanks the user back, and a user
/// already looking at 传输 is left alone.
public func chatAutoSelectedTab(current: HomeTab, wasPaired: Bool, channelReady: Bool) -> HomeTab? {
    guard channelReady, !wasPaired, current != .transfer else { return nil }
    return .transfer
}

/// Whether the chat shell draws its bottom bar. Always: the conversation lives
/// inside 传输, so 连接 / 传输 / 设置 stays on screen while unpaired and while
/// paired (Android `AppScreen` draws the same bar in both states).
public func chatShellShowsTabBar() -> Bool {
    true
}

/// The 传输 page's content: the conversation, in both states. Unpaired it is
/// the chat-style empty state, paired it is the stream — the page itself never
/// changes, so the bar and the shell stay put across the pairing transition.
public enum TransferTabContent: Equatable, Sendable {
    /// Chat-style empty state, disabled composer, 前往连接.
    case unpairedConversation
    /// The message stream.
    case conversation
}

public func transferTabContent(channelReady: Bool) -> TransferTabContent {
    channelReady ? .conversation : .unpairedConversation
}

/// The tab the 连接详情 sheet's 设置 row selects. With three items that is
/// index 2; `RootView` reads it from here so a number is never written down.
public let chatSettingsRouteTab = HomeTab.settings

// MARK: - Top bar

/// The live status dot. Green while the channel is open, amber while the
/// client reconnects, grey once the peer is gone. Mirrors the Android
/// `modeIsWarning` split (healthy path vs. reconnect).
public enum ChatConnectionStatus: Equatable, Sendable {
    case direct
    case reconnecting
    case disconnected
}

public func chatConnectionStatus(channelReady: Bool, reconnecting: Bool, hasRoom: Bool) -> ChatConnectionStatus {
    if channelReady { return .direct }
    if reconnecting && hasRoom { return .reconnecting }
    return .disconnected
}

/// The conversation's top-bar title: the peer's name once paired, a neutral
/// 未连接 while unpaired (Android `chat_disconnected_title`). The key is
/// returned untranslated for the same reason as the composer hint.
public let chatDisconnectedTitleKey = "chat_disconnected_title"

public func chatTopBarTitle(_ status: ChatConnectionStatus, peerName: String) -> String {
    status == .disconnected ? chatDisconnectedTitleKey : peerName
}

/// 连接详情 is offered only while the conversation is interactive (paired, or
/// in a transient reconnect with a room) and there is a session code to show
/// (Android `chatConnectionDetailsAvailable`).
public func chatDetailsAvailable(conversationVisible: Bool, sessionCode: String?) -> Bool {
    conversationVisible && !(sessionCode ?? "").isEmpty
}

/// Whether the session still has a peer on the other end. Drives the details
/// sheet and the composer, which keeps working through a transient reconnect
/// but stops once the peer is really gone.
public func chatPeerPresent(channelReady: Bool, reconnecting: Bool, hasRoom: Bool) -> Bool {
    channelReady || (reconnecting && hasRoom)
}

// MARK: - Composer

/// The composer accepts text through a transient reconnect (the send that
/// cannot go out is reported in the stream), but not while unpaired and not
/// after the peer left.
public func chatComposerEnabled(channelReady: Bool, reconnecting: Bool, hasRoom: Bool) -> Bool {
    channelReady || (reconnecting && hasRoom)
}

/// The ＋ attachment menu works while unpaired: every pick lands in the
/// pending queue (`AppViewModel.enqueueSendItems` appends first and only then
/// tries `flushPendingSendItems`, which is a no-op without an open channel),
/// so the files wait for the next session instead of being dropped — the same
/// guarantee Android's `enqueueSharedUris` gives. Text is the only part the
/// unpaired composer refuses.
public let chatAttachmentsEnabledWhileUnpaired = true

/// The unpaired composer's hint, where the paired composer shows nothing
/// extra: why the text field is inert and that files may still be queued.
/// The key is returned untranslated so the view resolves it through the
/// catalog with the in-app locale (and a test can pin it without the test
/// bundle's own locale standing in the way).
public let chatUnpairedComposerHintKey = "chat_composer_unpaired_hint"

public func chatComposerHint(paired: Bool) -> String? {
    paired ? nil : chatUnpairedComposerHintKey
}

/// Design §2 L1: an empty draft keeps Send at 55% and inert; a non-blank
/// draft lifts it to full strength. Reuses `chatSendEnabled` so the button
/// and the Return key share one gate.
public func chatSendOpacity(draft: String) -> Double {
    chatSendEnabled(draft) ? 1.0 : 0.55
}

/// The compact built-in emoji set (Android `CHAT_EMOJI_PALETTE`, same 30
/// glyphs). Three rows of ten, so the panel never needs 48pt-wide columns.
public let chatEmojiPalette: [String] = [
    "😀", "😄", "😊", "😉", "😍", "🤔", "😅", "😂", "😢", "😮",
    "👍", "👎", "👏", "🙏", "💪", "👌", "✌️", "🤝", "🎉", "🔥",
    "❤️", "💚", "💯", "⭐", "✅", "❌", "📎", "📁", "⏰", "☕",
]

/// Appends a palette glyph at the end of the draft, keeping what was typed.
public func appendEmoji(_ draft: String, _ emoji: String) -> String {
    draft + emoji
}

// MARK: - File bubbles

/// `2.4 MB · 就绪` — the bubble's one meta line. Empty parts are skipped so
/// an unknown size never leaves a leading separator.
public func chatFileMeta(sizeText: String, statusText: String) -> String {
    [sizeText, statusText].filter { !$0.isEmpty }.joined(separator: " · ")
}

/// The file bubble's progress bar exists only while bytes move
/// (Android `shouldShowCompactTransferProgress` without a download request).
public func chatFileShowsProgress(status: String) -> Bool {
    status == TransferCardStatus.transferring || status == TransferCardStatus.receivingFromWeb
}

/// A rejected or failed transfer turns the meta line into a warning; every
/// other status is ordinary meta.
public func chatFileMetaIsWarning(status: String) -> Bool {
    status.hasPrefix(TransferCardStatus.rejectedPrefix) || status.hasPrefix(TransferCardStatus.errorPrefix)
}

/// What the type badge says: the extension, at most four characters,
/// upper-cased, or `FILE` when the name has none.
public func chatFileBadgeLabel(fileName: String) -> String {
    let trimmed = fileName.trimmingCharacters(in: .whitespacesAndNewlines)
    let parts = trimmed.split(separator: ".", omittingEmptySubsequences: false)
    guard parts.count > 1, let extensionPart = parts.last, !extensionPart.isEmpty else { return "FILE" }
    let upper = extensionPart.trimmingCharacters(in: .whitespaces).uppercased()
    guard !upper.isEmpty else { return "FILE" }
    return String(upper.prefix(4))
}

/// The badge's hue family, keyed by extension exactly like Android
/// `chatFileBadge` so both clients colour the same file the same way.
public enum ChatFileKind: String, Equatable, Sendable {
    case image, video, audio, pdf, doc, sheet, slide, archive, apk, text, file
}

public func chatFileKind(fileName: String) -> ChatFileKind {
    let ext = (fileName as NSString).pathExtension.uppercased()
    switch ext {
    case "PNG", "JPG", "JPEG", "GIF", "WEBP", "HEIC", "BMP", "SVG", "AVIF": return .image
    case "MP4", "MOV", "AVI", "MKV", "WEBM", "M4V": return .video
    case "MP3", "WAV", "FLAC", "M4A", "AAC", "OGG": return .audio
    case "PDF": return .pdf
    case "DOC", "DOCX", "PAGES": return .doc
    case "XLS", "XLSX", "CSV", "NUMBERS": return .sheet
    case "PPT", "PPTX", "KEY": return .slide
    case "ZIP", "RAR", "7Z", "TAR", "GZ", "BZ2", "XZ": return .archive
    case "APK": return .apk
    case "TXT", "MD", "JSON", "LOG": return .text
    default: return .file
    }
}

// MARK: - Long press

/// The single recall the long-press menu offers for an entry, or `nil` when
/// the entry is not ours or is already a note. Own files are recallable while
/// they are live — which also aborts the transfer in flight, as on the web.
public func chatLongPressRecallTarget(entry: ChatTimelineEntry) -> RecallTarget? {
    switch entry {
    case .text(let message): return message.outgoing ? .text : nil
    case .file(_, let incoming): return incoming ? nil : .file
    case .recalled: return nil
    }
}

/// The id a long-press recall acts on.
public func chatLongPressRecallId(entry: ChatTimelineEntry) -> String? {
    switch entry {
    case .text(let message): return message.id
    case .file(let card, _): return card.fileId
    case .recalled: return nil
    }
}

/// Only text can be copied; a file bubble has no clipboard form and a recall
/// note is just a system line.
public func chatCopyableText(entry: ChatTimelineEntry) -> String? {
    guard case .text(let message) = entry, !message.recalled else { return nil }
    let text = message.text
    return text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : text
}

/// One entry of a bubble's long-press menu.
public enum ChatLongPressAction: Equatable, Sendable {
    case copy
    case recall
}

/// The long-press menu, in order. Copy is offered on own and peer text (the
/// web and Android both copy a bubble); 撤回 only on an own live entry, and it
/// is destructive so it comes last. A file bubble keeps its Recall-only menu
/// (the file itself has no clipboard form) and a recall note has none.
public func chatLongPressActions(entry: ChatTimelineEntry) -> [ChatLongPressAction] {
    var actions: [ChatLongPressAction] = []
    if chatCopyableText(entry: entry) != nil {
        actions.append(.copy)
    }
    if chatLongPressRecallTarget(entry: entry) != nil {
        actions.append(.recall)
    }
    return actions
}

// MARK: - Failure banner

/// A failure that makes the composer unusable gets the inline red banner and
/// the "not connected" placeholder immediately. A pure send failure (the
/// channel itself is still open) keeps the composer live and only shows the
/// banner.
public func chatComposerBlocked(failure: String, channelReady: Bool) -> Bool {
    failure == chatChannelNotReady && !channelReady
}

// MARK: - 连接详情 sheet

/// What the sheet shows (design frame L11). Mirrors the Android
/// `ConnectionDetailsState`; the values are read from real state only, never
/// from placeholders, so a row with no live source can be left out by the
/// view.
public struct ChatDetailsModel: Equatable, Sendable {
    /// The code both devices share once connected (`AppState.pairedCode`).
    public let code: String?
    public let localDevice: String
    public let peerDevice: String
    public let connectionPath: ConnectionPath
    /// 连接服务 / 连接状态 / 文件通道 / 连接方式 — the four live rows Android
    /// shows in this sheet.
    public let diagnostics: [ChatDiagnosticRow]

    /// True while the peer is really there. A transient reconnect keeps it
    /// true: the session and its code survive it.
    public let live: Bool
    public let canDisconnect: Bool
}

/// One key/value line of the diagnostics block.
public struct ChatDiagnosticRow: Equatable, Sendable {
    public let label: String
    /// Where the value comes from; the view translates it through the
    /// catalog (`Connected` / `Signal not connected` / `Open` /
    /// `Data channel not open` / `Direct` / `Unknown`).
    public let value: String

    public init(label: String, value: String) {
        self.label = label
        self.value = value
    }
}

/// The diagnostics block's copy, kept as keys so the tests can pin it and the
/// view never has to decide.
public enum ChatDiagnosticValues {
    public static let connected = "Connected"
    public static let signalingNotConnected = "Signaling not connected"
    public static let dataChannelOpen = "Open"
    public static let dataChannelNotOpen = "Data channel not open"
    public static let pathDirect = "Direct"
    public static let pathUnknown = "Unknown"
}

public func chatDiagnosticRows(
    signalingReady: Bool,
    channelReady: Bool,
    connectionPath: ConnectionPath,
) -> [ChatDiagnosticRow] {
    [
        ChatDiagnosticRow(
            label: "Signaling",
            value: signalingReady ? ChatDiagnosticValues.connected : ChatDiagnosticValues.signalingNotConnected,
        ),
        ChatDiagnosticRow(
            label: "Session",
            value: channelReady ? ChatDiagnosticValues.connected : ChatDiagnosticValues.signalingNotConnected,
        ),
        ChatDiagnosticRow(
            label: "DataChannel",
            value: channelReady ? ChatDiagnosticValues.dataChannelOpen : ChatDiagnosticValues.dataChannelNotOpen,
        ),
        ChatDiagnosticRow(
            label: "Path",
            // A path is only known while the channel is up; before that the
            // real state is "unknown", as on Android.
            value: channelReady && connectionPath == .direct
                ? ChatDiagnosticValues.pathDirect
                : ChatDiagnosticValues.pathUnknown,
        ),
    ]
}

/// A live file transfer blocks the teardown: 断开连接 stays visible but says
/// 暂不可用 instead of cutting bytes mid-flight (Android
/// `canDisconnect`, minus its `!channelReady` escape which the iOS session
/// never reaches with a code to show).
public func chatDetailsCanDisconnect(localTransfers: [TransferCardState], peerTransfers: [TransferCardState]) -> Bool {
    !(localTransfers + peerTransfers).contains { $0.status == TransferCardStatus.transferring }
}

/// The sheet's whole content, from the real state only.
public func chatDetailsModel(
    state: AppState,
    reconnecting: Bool,
    // Injected so the tests can pin the code against a deterministic room.
    pairedCode: String? = nil,
    webBrowserLabel: String = "Web browser",
) -> ChatDetailsModel {
    let code = pairedCode ?? state.pairedCode
    let live = chatPeerPresent(
        channelReady: state.channelReady,
        reconnecting: reconnecting,
        hasRoom: state.joinedRoom != nil,
    )
    let peer = state.peerDeviceLabel.trimmingCharacters(in: .whitespacesAndNewlines)
    return ChatDetailsModel(
        code: code,
        localDevice: state.localDeviceLabel,
        peerDevice: peer.isEmpty ? webBrowserLabel : peer,
        connectionPath: state.connectionPath,
        diagnostics: chatDiagnosticRows(
            signalingReady: state.signalingReady,
            channelReady: state.channelReady,
            connectionPath: state.connectionPath,
        ),
        live: live,
        canDisconnect: live && chatDetailsCanDisconnect(
            localTransfers: state.localTransfers,
            peerTransfers: state.peerTransfers,
        ),
    )
}

/// The sheet's vertical order (frame L11 / Android `ChatConnectionDetailsSheet`):
/// code card, device boxes, mode row, reconnect row, diagnostics, settings,
/// disconnect.
public enum ChatDetailsRow: Equatable, Sendable {
    case code
    case devices
    case connectionMode
    case reconnect
    case diagnostics
    case settings
    case disconnect

    public static let all: [ChatDetailsRow] = [
        .code, .devices, .connectionMode, .reconnect, .diagnostics, .settings, .disconnect,
    ]
}

/// Only the code row disappears without a code (a session without one never
/// offers the sheet); every other row always has a real source.
public func chatDetailsRows(code: String?) -> [ChatDetailsRow] {
    (code ?? "").isEmpty ? ChatDetailsRow.all.filter { $0 != .code } : ChatDetailsRow.all
}

// MARK: - Stream tail

/// Everything appended below the timeline that can change its height, so a
/// status change on an older bubble or a new note also scrolls to the end.
/// Mirrors Android `chatStreamTailNoteKeys`.
public func chatStreamTailNoteKeys(failure: String, channelReady: Bool) -> [String] {
    var keys: [String] = []
    if !failure.isEmpty { keys.append("chat-failure") }
    if !channelReady { keys.append("chat-session-note") }
    return keys
}

// MARK: - Folder notice

/// The stream note a folder pick leaves, already localized.
///
/// `chat_folder_too_many` carries a `%1$d` placeholder, so it must go through
/// the format initializer: `Text(LocalizedStringKey:)` would render the
/// placeholder literally. `chat_folder_empty` has no placeholder and stays a
/// plain lookup.
public func chatFolderNoticeText(_ notice: FolderPickNotice, maxFiles: Int = maxFolderFiles) -> String {
    switch notice {
    case .empty:
        return String(localized: "chat_folder_empty")
    case .tooMany:
        return String(format: String(localized: "chat_folder_too_many"), maxFiles)
    }
}

/// The text of the note for an optional notice (`nil` while a pick leaves
/// none).
public func chatFolderNoticeText(_ notice: FolderPickNotice?, maxFiles: Int = maxFolderFiles) -> String? {
    notice.map { chatFolderNoticeText($0, maxFiles: maxFiles) }
}

// MARK: - Accessibility

/// One spoken sentence per bubble: 「Pixel 8 说：你好」 for text, and the file
/// name, size and status for a file. The avatar is decorative and carries
/// no label of its own.
public func chatBubbleAccessibilityText(entry: ChatTimelineEntry, peerName: String) -> String {
    switch entry {
    case .text(let message):
        let speaker = message.outgoing ? ownSpeakerLabel : peerName
        return "\(speaker): \(message.text)"
    case .file(let card, _):
        return [card.title, formatFileSize(card.sizeBytes), card.status]
            .filter { !$0.isEmpty }
            .joined(separator: ", ")
    case .recalled(_, _, let byMe):
        return byMe ? "You recalled a message" : "The peer recalled a message"
    }
}

/// Placeholder speaker for own bubbles in the accessibility sentence.
public let ownSpeakerLabel = "Me"
