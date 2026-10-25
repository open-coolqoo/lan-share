import SwiftUI

/// One stream row: avatar, optional peer name, and one bubble (design
/// `docs/ios/chat-ui-design.md` frames L3/L4/L5/L6/L7/L9).
///
/// The peer avatar keeps a 34pt slot at the leading edge and the own avatar
/// one at the trailing edge, so a run of bubbles reads as one column and the
/// row height never depends on the avatar.
struct ChatBubbleRow<Bubble: View>: View {
    let theme: ChatTheme
    let incoming: Bool
    let avatar: String
    let senderName: String?
    let accessibilityText: String
    /// What a long press offers: the Copy/Recall sheet for a text bubble, the
    /// Recall confirmation for a file bubble, nothing for a note.
    var longPress: (() -> Void)?
    @ViewBuilder let bubble: () -> Bubble

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            if incoming {
                ChatAvatar(theme: theme, incoming: true, label: avatar)
                content
                Spacer(minLength: 0)
            } else {
                Spacer(minLength: 0)
                content
                ChatAvatar(theme: theme, incoming: false, label: avatar)
            }
        }
        .frame(maxWidth: .infinity, alignment: incoming ? .leading : .trailing)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityText)
    }

    private var content: some View {
        VStack(alignment: incoming ? .leading : .trailing, spacing: 3) {
            if let senderName {
                Text(senderName)
                    .font(.caption2)
                    .foregroundStyle(theme.meta)
                    .lineLimit(1)
                    .padding(.horizontal, 2)
            }
            // The long-press menu answers to the bubble alone; the sender name
            // above stays inert (design L8: long-press the bubble).
            bubble()
                .modifier(ChatLongPressMenu(onLongPress: longPress))
        }
        // A bubble never takes more than 78% of the stream, and never more
        // than the design's 520pt text width — on the Duo's inner display the
        // percentage alone would turn a short message into a wide paragraph.
        .containerRelativeFrame(.horizontal, alignment: incoming ? .leading : .trailing) { width, _ in
            min(width * 0.78, AdaptiveLayout.bubbleWidth)
        }
        .fixedSize(horizontal: false, vertical: true)
    }
}

/// Long press → the bubble's menu (design L8: Copy + Recall on own text, Copy
/// on peer text; Recall on an own file). `simultaneousGesture` rather than
/// `contextMenu`: the menu is two items at most and iOS would otherwise also
/// show its own preview lift, which the design does not draw.
private struct ChatLongPressMenu: ViewModifier {
    let onLongPress: (() -> Void)?

    func body(content: Content) -> some View {
        content.onLongPressGesture(minimumDuration: 0.45) {
            guard let onLongPress else { return }
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            onLongPress()
        }
    }
}

/// 34×34 rounded-square avatar with the device badge (`deviceBadge`). Purely
/// decorative: the row speaks the whole sentence.
struct ChatAvatar: View {
    let theme: ChatTheme
    let incoming: Bool
    let label: String

    var body: some View {
        Text(label)
            .font(.caption2.weight(.heavy))
            .foregroundStyle(incoming ? Color.white : theme.ownAvatarInk)
            .lineLimit(1)
            .minimumScaleFactor(0.6)
            .frame(width: 34, height: 34)
            .background(
                incoming ? theme.peerAvatarFill : theme.ownAvatarFill,
                in: RoundedRectangle(cornerRadius: 6),
            )
            .accessibilityHidden(true)
    }
}

/// An own or peer text bubble, 8pt radius everywhere, 9/12 padding.
struct ChatTextBubble: View {
    let theme: ChatTheme
    let outgoing: Bool
    let text: String

    var body: some View {
        Text(text)
            .font(.subheadline)
            .foregroundStyle(outgoing ? theme.ownTextInk : theme.ink)
            .textSelection(.enabled)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 12)
            .padding(.vertical, 9)
            .background(outgoing ? theme.ownTextFill : theme.peerBubble, in: RoundedRectangle(cornerRadius: 8))
            .overlay {
                if !outgoing {
                    RoundedRectangle(cornerRadius: 8).strokeBorder(theme.hairline, lineWidth: 1)
                }
            }
    }
}

/// A file bubble: type badge, name, one meta line, a 4pt progress bar while
/// bytes move, and at most one action slot (`chatFileAction`).
struct ChatFileBubble: View {
    let theme: ChatTheme
    let card: TransferCardState
    let incoming: Bool
    let statusText: String
    let action: ChatFileAction?
    let onAction: () -> Void

    private var outgoing: Bool { !incoming }
    private var fileNameInk: Color { outgoing ? theme.ownFileInk : theme.ink }
    private var metaInk: Color {
        chatFileMetaIsWarning(status: card.status) ? theme.warning : (outgoing ? theme.ownFileMeta : theme.meta)
    }
    private var kind: ChatFileKind { chatFileKind(fileName: card.title) }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .center, spacing: 10) {
                typeBadge
                VStack(alignment: .leading, spacing: 3) {
                    Text(card.title)
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(fileNameInk)
                        .lineLimit(2)
                        .truncationMode(.middle)
                    Text(metaLine)
                        .font(.caption)
                        .foregroundStyle(metaInk)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if let action {
                    actionButton(action)
                }
            }
            if chatFileShowsProgress(status: card.status) {
                ChatProgressBar(
                    value: Double(card.progressPercent ?? 0) / 100.0,
                    track: outgoing ? theme.ownFileTrack : theme.peerFileTrack,
                    fill: outgoing ? theme.ownFileProgress : theme.peerFileProgress,
                )
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(outgoing ? theme.ownFileFill : theme.peerBubble, in: RoundedRectangle(cornerRadius: 8))
        .overlay {
            if !outgoing {
                RoundedRectangle(cornerRadius: 8).strokeBorder(theme.hairline, lineWidth: 1)
            }
        }
    }

    /// `2.4 MB · 就绪`, plus `(1.3 MB)` while bytes are in flight.
    private var metaLine: String {
        let size = bidiIsolatedLtr(formatFileSize(card.sizeBytes))
        var meta = chatFileMeta(sizeText: size, statusText: statusText)
        if chatFileShowsProgress(status: card.status), let percent = card.progressPercent, card.sizeBytes > 0 {
            meta += " (\(bidiIsolatedLtr(formatFileSize(card.sizeBytes * percent / 100))))"
        }
        return meta
    }

    /// Keeps an LTR value intact inside an RTL paragraph: without the isolate
    /// "128.0 KB" renders as "KB 128.0".
    private func bidiIsolatedLtr(_ value: String) -> String {
        value.isEmpty ? value : "\u{2066}\(value)\u{2069}"
    }

    private var typeBadge: some View {
        Text(chatFileBadgeLabel(fileName: card.title))
            .font(.caption2.weight(.heavy))
            .foregroundStyle(theme.badgeInk(for: kind))
            .lineLimit(1)
            .minimumScaleFactor(0.7)
            .frame(width: 40, height: 40)
            .background(theme.badgeFill(for: kind), in: RoundedRectangle(cornerRadius: 6))
            .accessibilityHidden(true)
    }

    /// The one slot: a 36pt disc inside a 44pt target (design A1). A cancel
    /// reads in the danger tone (design L6); every other slot uses the bubble's
    /// ordinary action ink.
    private func actionButton(_ action: ChatFileAction) -> some View {
        Button(action: onAction) {
            Image(systemName: actionSymbol(action))
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(action == .accept ? theme.peerBubble : actionInk(action))
                .frame(width: 36, height: 36)
                .background(actionFill(action), in: Circle())
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(actionLabel(action))
    }

    private func actionInk(_ action: ChatFileAction) -> Color {
        action == .cancel ? theme.errorInk : (outgoing ? theme.ownFileAction : theme.peerAction)
    }

    private func actionSymbol(_ action: ChatFileAction) -> String {
        switch action {
        case .accept: return "arrow.down.circle.fill"
        case .cancel: return "xmark.circle.fill"
        case .openDownloads: return "square.and.arrow.up"
        case .recall: return "arrow.uturn.backward"
        }
    }

    private func actionLabel(_ action: ChatFileAction) -> String {
        switch action {
        case .accept: return String(localized: "chat_file_accept")
        // The existing 取消传输 copy, the same words the Receive card uses.
        case .cancel: return String(localized: "Cancel transfer")
        case .openDownloads: return String(localized: "Share")
        case .recall: return String(localized: "chat_recall")
        }
    }

    private func actionFill(_ action: ChatFileAction) -> Color {
        action == .accept ? theme.peerAction : .clear
    }
}

/// 4pt, fully rounded progress bar for one file bubble.
struct ChatProgressBar: View {
    let value: Double
    let track: Color
    let fill: Color

    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Capsule().fill(track)
                Capsule()
                    .fill(fill)
                    .frame(width: max(0, min(1, value)) * proxy.size.width)
            }
        }
        .frame(height: 4)
        .accessibilityHidden(true)
    }
}

/// A centred system note: 「会话已建立」, a recall line, or the disconnected
/// note (design frames L1/L9/L12).
struct ChatSystemNote: View {
    let theme: ChatTheme
    let text: String
    var warning = false

    var body: some View {
        Text(text)
            .font(.caption2)
            .multilineTextAlignment(.center)
            .foregroundStyle(warning ? theme.warning : theme.noteInk)
            .padding(.horizontal, 12)
            .padding(.vertical, 4)
            .background(warning ? theme.warningFill : theme.noteFill, in: Capsule())
            .frame(maxWidth: .infinity, alignment: .center)
    }
}

/// Picks whose bytes are still being copied out of the photo library or a
/// Files provider, as spinner rows at the end of the stream.
struct ChatPreparingRow: View {
    let theme: ChatTheme
    let items: [PreparingSendItem]

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(items) { item in
                HStack(spacing: 8) {
                    ProgressView().controlSize(.mini)
                    Text(item.name)
                        .font(.caption)
                        .foregroundStyle(theme.meta)
                        .lineLimit(1)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .background(theme.peerBubble, in: RoundedRectangle(cornerRadius: 8))
    }
}
