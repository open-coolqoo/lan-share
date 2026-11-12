import SwiftUI

/// 连接详情 (design frame L11): the paired code, the two device labels, the
/// connection mode, the auto-reconnect statement, the four live diagnostics,
/// the Settings entry and 断开连接.
///
/// Presented by `ChatConversationView` with `.presentationDetents([.medium,
/// .large])` and a drag indicator, so the system sheet supplies the grabber,
/// the scrim and the dismissal (swipe down / tap outside). Every value comes
/// from `chatDetailsModel`; rows with no live source are left out rather
/// than faked.
struct ChatDetailsSheet: View {
    let model: AppViewModel
    let details: ChatDetailsModel
    /// Two-state field: the AppState itself is not Equatable.
    let locale: String
    /// Dismisses the sheet, then opens the existing settings screen.
    let onOpenSettings: () -> Void
    /// Dismisses the sheet, then asks the two-step confirmation.
    let onDisconnect: () -> Void

    @State private var diagnosticsExpanded = false
    private var theme: ChatTheme { ChatTheme(dark: model.state.darkTheme) }
    private var brand: Color { Theme.primary(model.state.siteConfig) }
    private var rows: [ChatDetailsRow] { chatDetailsRows(code: details.code) }

    /// 展开 / 收起, resolved through the catalog for the in-app locale.
    private var expandCollapseLabel: String {
        String(
            localized: String.LocalizationValue(
                diagnosticsExpanded ? "chat_details_collapse" : "chat_details_expand",
            ),
        )
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                header
                ForEach(rows, id: \.self) { row in
                    content(row)
                }
            }
            .padding(.horizontal, 18)
            .padding(.top, 4)
            .padding(.bottom, 24)
        }
        .background(theme.barBackground)
        .environment(\.locale, Locale(identifier: locale))
    }

    private var header: some View {
        HStack(spacing: 10) {
            Text("chat_details_title")
                .font(.headline.weight(.bold))
                .foregroundStyle(theme.ink)
            Spacer(minLength: 4)
            if details.live {
                ChatDetailsPill(theme: theme, label: "Connected", warning: false)
            } else {
                ChatDetailsPill(theme: theme, label: "chat_reconnecting", warning: true)
            }
        }
        .padding(.top, 8)
        .padding(.bottom, 6)
    }

    @ViewBuilder
    private func content(_ row: ChatDetailsRow) -> some View {
        switch row {
        case .code:
            codeCard
        case .devices:
            HStack(alignment: .top, spacing: 10) {
                deviceBox(label: "Local", value: details.localDevice)
                deviceBox(label: "Peer", value: details.peerDevice)
            }
        case .connectionMode:
            connectionModeRow
        case .reconnect:
            ChatDetailsInfoRow(                theme: theme,
                label: "chat_details_reconnect",
                value: String(localized: "chat_details_reconnect_value"),
            )
        case .diagnostics:
            diagnosticsBlock
        case .settings:
            ChatDetailsActionRow(
                theme: theme,
                label: "chat_details_settings",
                // Bidi mirrors the glyph itself inside an RTL paragraph.
                value: String(localized: "chat_details_open") + " ›",
                tint: brand,
                action: onOpenSettings,
            )
        case .disconnect:
            ChatDetailsActionRow(
                theme: theme,
                label: "Disconnect",
                // Blocked by a live transfer: say why instead of hiding value.
                value: details.canDisconnect
                    ? String(localized: "chat_details_open") + " ›"
                    : String(localized: "chat_details_disabled"),
                tint: Theme.danger,
                enabled: details.canDisconnect,
                action: onDisconnect,
            )
        }
    }

    /// This session's code, left; the same code as a QR on the right, so the
    /// other device can scan it. Rendered only when the code exists.
    private var codeCard: some View {
        HStack(alignment: .center, spacing: 14) {
            VStack(alignment: .leading, spacing: 4) {
                Text("chat_details_code_label")
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(theme.meta)
                Text(details.code ?? "")
                    .font(.system(size: 30, weight: .black, design: .rounded))
                    .tracking(3)
                    .foregroundStyle(theme.ink)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                    .textSelection(.enabled)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            qr
        }
        .padding(12)
        .background(Theme.surfaceAlt, in: RoundedRectangle(cornerRadius: 8))
        .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(theme.hairline, lineWidth: 1))
    }

    @ViewBuilder
    private var qr: some View {
        if details.code != nil,
           !model.state.lanQrPayload.isEmpty,
           let image = PairingQr.image(for: model.state.lanQrPayload, points: 80) {
            VStack(spacing: 4) {
                // Same pairing QR the home page draws (`PairingQr.image`); a
                // white quiet zone stays behind it in both appearances.
                Image(uiImage: image)
                    .interpolation(.none)
                    .resizable()
                    .frame(width: 80, height: 80)
                    .padding(5)
                    .background(Color.white, in: RoundedRectangle(cornerRadius: 8))
                    .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(theme.hairline, lineWidth: 1))
                    .accessibilityLabel(Text("QR code for pairing this device"))
                Text("chat_details_qr_label")
                    .font(.caption2)
                    .foregroundStyle(theme.meta)
            }
        }
    }

    private func deviceBox(label: LocalizedStringKey, value: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label)
                .font(.caption2.weight(.bold))
                .foregroundStyle(theme.meta)
            Text(value)
                .font(.footnote.weight(.bold))
                .foregroundStyle(theme.ink)
                .lineLimit(2)
                .truncationMode(.middle)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 11)
        .padding(.vertical, 9)
        .background(Theme.surfaceAlt, in: RoundedRectangle(cornerRadius: 8))
        .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(theme.hairline, lineWidth: 1))
    }

    /// 连接方式 — the localized path name, then the path's own raw tag
    /// ("Direct" / "Unknown") in the capsule, as Android and the web show it.
    /// The middle text is the one that goes through the catalog; the capsule
    /// repeats the raw label, so translating both would print 「直连 直连」.
    private var connectionModeRow: some View {
        HStack(spacing: 10) {
            Text("chat_details_connection_mode")
                .font(.footnote)
                .foregroundStyle(theme.meta)
                .frame(maxWidth: .infinity, alignment: .leading)
            Text(LocalizedStringKey(details.connectionPath.label))
                .font(.footnote.weight(.bold))
                .foregroundStyle(theme.ink)
                .lineLimit(1)
            Text(verbatim: details.connectionPath.label)
                .font(.caption.weight(.bold))
                .foregroundStyle(theme.ink)
                .lineLimit(1)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(theme.hairline, in: Capsule())
        }
        .frame(minHeight: 48)
        .padding(.horizontal, 11)
        .background(Theme.surfaceAlt, in: RoundedRectangle(cornerRadius: 8))
        .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(theme.hairline, lineWidth: 1))
    }

    /// Collapsed by default; 展开 / 收起 swaps the same chevron that rotates.
    private var diagnosticsBlock: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button {
                withAnimation(.easeInOut(duration: 0.2)) { diagnosticsExpanded.toggle() }
            } label: {
                HStack(spacing: 10) {
                    Text("Diagnostics")
                        .font(.footnote)
                        .foregroundStyle(theme.meta)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Text(verbatim: expandCollapseLabel + " ›")
                        .font(.footnote.weight(.bold))
                        .foregroundStyle(theme.ink)
                        .lineLimit(1)
                    Image(systemName: "chevron.down")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(brand)
                        .rotationEffect(.degrees(diagnosticsExpanded ? 180 : 0))
                }
                .frame(minHeight: 48)
                .padding(.horizontal, 11)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("Diagnostics"))
            .accessibilityHint(Text(diagnosticsExpanded ? "chat_details_collapse" : "chat_details_expand"))

            if diagnosticsExpanded {
                VStack(spacing: 8) {
                    Rectangle().fill(theme.hairline).frame(height: 0.5)
                    ForEach(details.diagnostics, id: \.label) { diagnostic in
                        HStack(spacing: 10) {
                            Text(LocalizedStringKey(diagnostic.label))
                                .font(.caption)
                                .foregroundStyle(theme.meta)
                            Spacer(minLength: 8)
                            Text(LocalizedStringKey(diagnostic.value))
                                .font(.caption.weight(.bold))
                                .foregroundStyle(theme.ink)
                                .multilineTextAlignment(.trailing)
                        }
                    }
                }
                .padding(.horizontal, 11)
                .padding(.bottom, 10)
            }
        }
        .background(Theme.surfaceAlt, in: RoundedRectangle(cornerRadius: 8))
        .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(theme.hairline, lineWidth: 1))
    }
}

/// A 48pt row: label left, an optional trailing value right. Used for the
/// reconnect statement, 设置 and 断开连接.
struct ChatDetailsActionRow: View {
    let theme: ChatTheme
    let label: String
    let value: String?
    let tint: Color
    var enabled = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Text(LocalizedStringKey(label))
                    .font(.footnote)
                    .foregroundStyle(theme.meta)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if let value {
                    Text(value)
                        .font(.footnote.weight(.bold))
                        .foregroundStyle(enabled ? tint : theme.meta)
                        .lineLimit(1)
                }
            }
            .frame(minHeight: 48)
            .padding(.horizontal, 11)
            .background(Theme.surfaceAlt, in: RoundedRectangle(cornerRadius: 8))
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .strokeBorder(label == "Disconnect" && enabled ? Theme.danger.opacity(0.4) : theme.hairline, lineWidth: 1),
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityLabel(Text(LocalizedStringKey(label)))
    }
}

/// A statement row (网络中断时 · 5 分钟内自动重连): a label and one value.
struct ChatDetailsInfoRow: View {
    let theme: ChatTheme
    let label: String
    let value: String

    var body: some View {
        HStack(spacing: 10) {
            Text(LocalizedStringKey(label))
                .font(.footnote)
                .foregroundStyle(theme.meta)
                .frame(maxWidth: .infinity, alignment: .leading)
            Text(value)
                .font(.footnote.weight(.bold))
                .foregroundStyle(theme.ink)
                .multilineTextAlignment(.trailing)
        }
        .frame(minHeight: 48)
        .padding(.horizontal, 11)
        .background(Theme.surfaceAlt, in: RoundedRectangle(cornerRadius: 8))
        .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(theme.hairline, lineWidth: 1))
    }
}

/// 已连接 / 网络中断… in the sheet header.
struct ChatDetailsPill: View {
    let theme: ChatTheme
    let label: String
    let warning: Bool

    var body: some View {
        Text(LocalizedStringKey(label))
            .font(.caption2.weight(.heavy))
            .foregroundStyle(warning ? theme.warning : theme.statusGreen)
            .lineLimit(1)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(warning ? theme.warningFill : theme.statusGreen.opacity(0.14), in: Capsule())
    }
}
