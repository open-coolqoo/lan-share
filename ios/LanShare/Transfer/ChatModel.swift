import Foundation

/// One chat line, either typed by the user or received from the peer.
/// Mirrors the Android `ChatMessageState` and the web `PeerMessageItem`.
public struct ChatMessageState: Equatable, Sendable, Identifiable {
    public let id: String
    public let outgoing: Bool
    public let text: String
    public let atMs: Int64
    public var recalled: Bool

    public init(id: String, outgoing: Bool, text: String, atMs: Int64, recalled: Bool = false) {
        self.id = id
        self.outgoing = outgoing
        self.text = text
        self.atMs = atMs
        self.recalled = recalled
    }
}

/// A retraction the chat layer reflects in place (web `onRecalled`, Android
/// `AndroidTransferSession.Recall`).
public struct TransferRecall: Equatable, Sendable {
    public let target: RecallTarget
    public let targetId: String

    public init(target: RecallTarget, targetId: String) {
        self.target = target
        self.targetId = targetId
    }
}

/// One chronological conversation: text messages and file bubbles share a
/// single ordered list, so a message sent after a file never lands above that
/// file. Recalled entries stay in place as a system note.
public enum ChatTimelineEntry: Equatable, Sendable {
    case text(message: ChatMessageState)
    case file(card: TransferCardState, incoming: Bool)
    /// A system note that replaces a recalled entry in place. `sourceKey` is
    /// the recalled entry's original key, so two notes never collide (web
    /// `recalled-text-<id>` / `recalled-file-<fileId>`).
    case recalled(sourceKey: String, atMs: Int64, byMe: Bool)

    /// Stable identity for the view layer and for deduplicating entries.
    public var key: String {
        switch self {
        case .text(let message): return "text-\(message.id)"
        case .file(let card, let incoming): return "\(incoming ? "remote" : "local")-\(card.fileId)"
        case .recalled(let sourceKey, _, _): return "recalled-\(sourceKey)"
        }
    }

    public var atMs: Int64 {
        switch self {
        case .text(let message): return message.atMs
        case .file(let card, _): return card.atMs
        case .recalled(_, let atMs, _): return atMs
        }
    }

    /// Whether the entry came from the peer (used by the sender-label rule).
    public var isIncoming: Bool {
        switch self {
        case .text(let message): return !message.outgoing
        case .file(_, let incoming): return incoming
        case .recalled: return false
        }
    }

    public var isRecalledNote: Bool {
        if case .recalled = self { return true }
        return false
    }
}

public enum ChatTimeline {
    /// Builds the conversation in time order. Equal timestamps keep the
    /// production order — text, then local files, then peer files, each in
    /// list order — matching the web's (stable) `Array.sort` and the Android
    /// `chatTimeline`. Swift's `sorted` is not documented stable, so every
    /// entry is decorated with its insertion index before sorting.
    public static func build(
        messages: [ChatMessageState],
        local: [TransferCardState],
        peer: [TransferCardState],
    ) -> [ChatTimelineEntry] {
        var decorated: [(entry: ChatTimelineEntry, order: Int)] = []
        decorated.reserveCapacity(messages.count + local.count + peer.count)
        for message in messages {
            let live = ChatTimelineEntry.text(message: message)
            let entry: ChatTimelineEntry = message.recalled
                ? .recalled(sourceKey: live.key, atMs: message.atMs, byMe: message.outgoing)
                : live
            decorated.append((entry, decorated.count))
        }
        for (cards, incoming) in [(local, false), (peer, true)] {
            for card in cards {
                let live = ChatTimelineEntry.file(card: card, incoming: incoming)
                let entry: ChatTimelineEntry = card.recalled
                    ? .recalled(sourceKey: live.key, atMs: card.atMs, byMe: !incoming)
                    : live
                decorated.append((entry, decorated.count))
            }
        }
        decorated.sort { left, right in
            left.entry.atMs != right.entry.atMs ? left.entry.atMs < right.entry.atMs : left.order < right.order
        }
        return decorated.map(\.entry)
    }
}

/// The single action a file bubble offers for its status at most. Mirrors the
/// web (`App.tsx:1752` passes `onCancel` for incoming files, `:2081-2095`
/// draws the ✕): accept an incoming offer, cancel an incoming transfer that
/// is already receiving, share a received file, or recall an outgoing file
/// still on the wire. Everything else (including a recalled entry) offers
/// nothing.
public enum ChatFileAction: String, Equatable, Sendable {
    case accept
    /// Abort an incoming transfer in flight. The bubble stays in place and
    /// shows 已取消 (design O3), unlike the web, which drops the row.
    case cancel
    case openDownloads
    case recall
}

public func chatFileAction(card: TransferCardState, incoming: Bool, hasExport: Bool = true) -> ChatFileAction? {
    if card.recalled { return nil }
    switch card.status {
    case TransferCardStatus.readyToAccept:
        return incoming ? .accept : nil
    case TransferCardStatus.savedOnIOS:
        return incoming && hasExport ? .openDownloads : nil
    case TransferCardStatus.transferring:
        return incoming ? nil : .recall
    case TransferCardStatus.receivingFromWeb:
        // An own card never carries this status (it means bytes are arriving),
        // but the rule stays keyed on direction like every other slot.
        return incoming ? .cancel : nil
    default:
        return nil
    }
}

/// A short, dependency-free avatar label, matching the web `deviceBadge`
/// (`App.tsx:1545`): "PC Chrome" → "PC", "Chrome" → "CH", "MacBook-Pro" →
/// "MP", "iPhone 手机" → "IP", "手机" → "手", blank → "?". The web slices the
/// first two UTF-16 units of the whole label; this counts ASCII words and
/// glyphs instead, which gives the same answer on real labels and, unlike a
/// UTF-16 slice, never splits a CJK or emoji glyph.
public func deviceBadge(_ label: String) -> String {
    let words = label
        .trimmingCharacters(in: .whitespacesAndNewlines)
        .components(separatedBy: String.wordSeparators)
        .filter { !$0.isEmpty }
    guard !words.isEmpty else { return "?" }
    let ascii = words.filter { word in
        word.unicodeScalars.allSatisfy { scalar in (0x20 ... 0x7E).contains(scalar.value) }
    }
    if ascii.count >= 2 {
        // The second ASCII word's first glyph (or, for a label whose second
        // "word" is not ASCII, the label's second glyph). The web instead
        // slices the first two UTF-16 units of the label, which agrees here:
        // "MacBook-Pro" → "MP", "iPhone 手机" → "IP".
        let second = ascii[1].first.map(String.init) ?? takeGlyphs(label, count: 2)
        return (takeGlyphs(ascii[0], count: 1) + second).uppercased()
    }
    if ascii.count == 1 {
        return takeGlyphs(ascii[0], count: 2).uppercased()
    }
    return takeGlyphs(words[0], count: 1)
}

private extension String {
    /// The web splits on the regex `[\s_-]+`; JS `\s` is exactly
    /// `[\t\n\v\f\r \u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000\ufeff]`.
    static var wordSeparators: CharacterSet {
        var set = CharacterSet.whitespacesAndNewlines
        set.insert(charactersIn: "\u{0B}\u{0C}\u{1680}\u{2028}\u{2029}\u{202F}\u{205F}\u{3000}\u{FEFF}_-")
        set.insert(charactersIn: "\u{2000}" ... "\u{200A}")
        return set
    }
}

/// The first `count` glyphs as a String, never splitting a scalar. Swift keeps
/// a leading lowercase `i` as its own scalar, where the web's `str[0]` would
/// take it together with the next ASCII letter — those two are joined back.
private func takeGlyphs(_ value: String, count: Int) -> String {
    String(String.UnicodeScalarView(value.unicodeScalars.prefix(count)))
}

/// Placeholder the view substitutes with the peer's device label.
public let peerNameLabel = "\u{0}peer"

/// The peer name sits above a peer bubble only at the start of a run (design
/// frame 02, web `App.tsx:1724-1734`, Android `chatSenderLabel`): shown when
/// there is no previous entry, when the previous entry is one of ours, and
/// when the previous entry is a recall note — a note is a break, so the name
/// comes back after it. Own entries and recall notes carry no label.
public func chatSenderLabel(entry: ChatTimelineEntry, previous: ChatTimelineEntry?) -> String? {
    if entry.isRecalledNote || !entry.isIncoming { return nil }
    return previous?.isIncoming == true ? nil : peerNameLabel
}

/// Send is enabled only with a non-blank draft, so an empty tap neither sends
/// nor shifts the bar.
public func chatSendEnabled(_ draft: String) -> Bool {
    !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
}

/// Bytes as the file bubble meta shows them: B / KB / MB / GB with one decimal
/// from MB up. Unknown or negative bytes render as an empty string.
public func formatFileSize(_ bytes: Int?) -> String {
    guard let bytes, bytes >= 0 else { return "" }
    if bytes < 1024 { return "\(bytes) B" }
    let kb = Double(bytes) / 1024.0
    if kb < 1024 { return "\(oneDecimal(kb)) KB" }
    let mb = kb / 1024.0
    if mb < 1024 { return "\(oneDecimal(mb)) MB" }
    return "\(oneDecimal(mb / 1024.0)) GB"
}

private func oneDecimal(_ value: Double) -> String {
    String(format: "%.1f", value)
}

/// The UTC stamp the web builds with
/// `new Date().toISOString().slice(0, 19).replace(/[T:]/g, '-')`:
/// `2026-10-19-08-41-07`. Always UTC, so both sides name the same draft alike.
public func formatChatDraftTimestamp(_ atMs: Int64) -> String {
    let date = Date(timeIntervalSince1970: Double(atMs) / 1000.0)
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.calendar = Calendar(identifier: .gregorian)
    formatter.timeZone = TimeZone(identifier: "UTC")
    formatter.dateFormat = "yyyy-MM-dd-HH-mm-ss"
    return formatter.string(from: date)
}

/// An oversized draft offered as the same `.txt` file the web builds:
/// `message-yyyy-MM-dd-HH-mm-ss.txt`, UTC.
public func oversizeDraftName(at atMs: Int64) -> String {
    "message-\(formatChatDraftTimestamp(atMs)).txt"
}
