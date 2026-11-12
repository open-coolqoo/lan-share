import SwiftUI

/// Root container: a persistent brand top bar over three tabs, mirroring the
/// Android `AppScreen` (TopBar + bottom navigation + tab content).
///
/// The bottom bar is hand-rolled rather than built from `TabView`: the system
/// tab bar (`.tabItem`) vertically compresses tab content on iOS 26, which
/// squashed the fixed-size pairing QR code. Android uses its own
/// `AppBottomNavigation`, so this mirrors it as well.
///
/// The bars live in safe-area insets instead of stack slots, so the page
/// content extends beneath them — what makes the iOS 26 Liquid Glass bar
/// surfaces (`TopBar`, `BottomTabBar`) blur what scrolls past. Below iOS 26
/// the bars keep their opaque surfaces and the under-scroll is invisible.
///
/// The conversation is no longer a full-screen takeover: 传输 **is** the
/// conversation (product decision 2026-10-20, same as Android). The bar is
/// drawn in every state — unpaired, paired, reconnecting — and the full-screen
/// route, the ‹ affordance and the 会话进行中 strip are retired.
struct RootView: View {
    @Bindable var model: AppViewModel
    @State private var selectedTab = Int(ProcessInfo.processInfo.environment["LANSHARE_DEBUG_TAB"] ?? "") ?? 0
    /// Set by the 连接详情 sheet's 设置 row: the sheet has to close first, and
    /// a sheet cannot select a tab and dismiss itself in the same frame.
    @State private var pendingSettingsTab = false
    /// The one-shot handoff from the sheet's 断开连接. The conversation runs
    /// the two-step confirmation; once the session is really gone this moves
    /// the shell to 连接.
    @State private var showConnectAfterDisconnect = false
    /// Sticky for one room: set by the first channel-ready, reset once the room
    /// is gone. A transient reconnect keeps the room, so its recovery is not a
    /// fresh pairing and must not move the user.
    @State private var hasPairedInSession = false

    private var primary: Color { Theme.primary(model.state.siteConfig) }

    var body: some View {
        home()
            .onChange(of: model.state.channelReady, initial: true) { _, ready in
                // A disconnect that already landed (the sheet's confirmation
                // ran inside the conversation) leaves 传输.
                if showConnectAfterDisconnect, !ready {
                    selectedTab = HomeTab.connect.rawValue
                    showConnectAfterDisconnect = false
                }
                // A fresh pairing opens 传输; a reconnect's recovery does not,
                // because `hasPairedInSession` stays set for the whole room.
                if let target = chatAutoSelectedTab(
                    current: chatHomeTab(selectedTab: selectedTab),
                    wasPaired: hasPairedInSession,
                    channelReady: ready,
                ) {
                    selectedTab = target.rawValue
                }
                if ready { hasPairedInSession = true }
            }
            // The room is the lifetime of a pairing attempt: once it is gone,
            // the next channel-ready is a fresh pairing again.
            .onChange(of: model.state.joinedRoom == nil) { _, noRoom in
                if noRoom { hasPairedInSession = false }
            }
            // Drives `Text(LocalizedStringKey)` lookups so the in-app language
            // picker re-renders the UI in the selected locale.
            .environment(\.locale, Locale(identifier: model.state.locale))
    }

    /// 连接详情 → 设置: closes the sheet, then selects the Settings tab. The
    /// index comes from `chatSettingsRouteTab` — with three items a number
    /// written down here would select nothing once the bar changes.
    private func openSettingsFromSheet() {
        pendingSettingsTab = true
    }

    /// The conversation's 断开连接, after its own confirmation has asked. The
    /// shell only records the landing tab; the conversation calls
    /// `model.disconnect()` itself.
    private func disconnectFromConversation() {
        showConnectAfterDisconnect = true
    }

    private func home() -> some View {
        let tab = Binding(
            get: { chatHomeTab(selectedTab: selectedTab) },
            set: { selectedTab = $0.rawValue },
        )
        return Group {
            switch tab.wrappedValue {
            case .connect:
                // The pending-files card opens 传输, where the queue and
                // the conversation live.
                ConnectView(model: model, onOpenShare: { selectedTab = HomeTab.transfer.rawValue })
            case .transfer:
                // The page itself (not the shell) owns its composer inset; the
                // bars above and below are safe-area insets of this shell, so
                // the page spans the full height and scrolls beneath them.
                ChatConversationView(
                    model: model,
                    onGoToConnect: { selectedTab = HomeTab.connect.rawValue },
                    onOpenSettings: openSettingsFromSheet,
                    onDisconnect: disconnectFromConversation,
                )
            case .settings:
                SettingsView(model: model)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .safeAreaInset(edge: .top, spacing: 0) {
            TopBar(state: model.state, config: model.state.siteConfig)
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            BottomTabBar(
                selected: tab,
                primary: primary,
                sendCount: model.state.sendCount,
                receiveCount: model.state.pendingIncomingCount,
            )
        }
            // 设置 is requested from inside a sheet; dismissing it and moving
            // the selection in the same frame drops the selection.
            .sheet(isPresented: $pendingSettingsTab, onDismiss: {
                selectedTab = chatSettingsRouteTab.rawValue
            }) {
                Color.clear.onAppear {
                    // The sheet's only job is the handoff above.
                    pendingSettingsTab = false
                }
            }
    }
}

// MARK: - Bottom navigation

/// Three-item bottom navigation mirroring Android's Material3
/// `NavigationBar`: selected item gets a capsule behind its icon plus a
/// tinted label. 连接 / 传输 / 设置 — the 分享 and 接收 tabs were merged into
/// 传输, which is now the conversation itself.
///
/// iOS 26 draws the system Liquid Glass treatment (HIG: the tab bar is the
/// functional layer glass is for): one floating capsule, whose selected tab is
/// a tinted interactive glass pill that morphs between items — the pill is
/// `glassEffect` + `glassEffectID` inside one `GlassEffectContainer`, the way
/// the system tab bar animates its selection. Below iOS 26 the bar keeps the
/// opaque edge-to-edge surface.
///
/// The bar draws no keyboard handling of its own. The bars are safe-area
/// insets without `.ignoresSafeArea(.keyboard)`, so SwiftUI resolves the
/// keyboard as the shell's bottom inset and this bar rides above it, with the
/// conversation's composer (the tab content's own inset) above the bar.
struct BottomTabBar: View {
    @Binding var selected: HomeTab
    let primary: Color
    var sendCount = 0
    var receiveCount = 0

    /// Badge nudge moves outward on the trailing side, which flips with RTL.
    @Environment(\.layoutDirection) private var layoutDirection

    /// Morph target for the iOS 26 selection pill.
    @Namespace private var selectionPill

    var body: some View {
        if #available(iOS 26.0, *) {
            glassBar
        } else {
            solidBar
        }
    }

    /// The Liquid Glass bar: a floating capsule spanning the three items.
    @available(iOS 26.0, *)
    private var glassBar: some View {
        GlassEffectContainer(spacing: 0) {
            HStack(spacing: 0) {
                ForEach(homeTabItems, id: \.self) { tab in
                    glassItem(tab, badge: HomeTab.tabBadge(tab, sendCount: sendCount, receiveCount: receiveCount))
                }
            }
            .padding(.vertical, 4)
            .padding(.horizontal, 4)
        }
        .liquidGlass(in: Capsule())
        .padding(.horizontal, 14)
        .padding(.top, 8)
        .padding(.bottom, 4)
        // The floating bar keeps phone proportions on the Duo's inner display
        // instead of stretching three items across it.
        .adaptiveColumn(AdaptiveLayout.tabBarWidth)
    }

    /// The pre-26 opaque bar.
    private var solidBar: some View {
        HStack(spacing: 0) {
            ForEach(homeTabItems, id: \.self) { tab in
                item(tab, badge: HomeTab.tabBadge(tab, sendCount: sendCount, receiveCount: receiveCount))
            }
        }
        .padding(.top, 6)
        .padding(.bottom, 2)
        // Items stay phone-width on the Duo's inner display; the surface
        // behind them still spans the whole canvas.
        .adaptiveColumn(AdaptiveLayout.tabBarWidth)
        .background(Theme.surface)
        .overlay(alignment: .top) { Divider() }
        // Fills the home-indicator strip below the safe area.
        .background(Theme.surface.ignoresSafeArea(edges: .bottom))
    }

    @available(iOS 26.0, *)
    private func glassItem(_ tab: HomeTab, badge: Int) -> some View {
        let isSelected = selected == tab
        return Button {
            // The morph needs an animation to travel along.
            withAnimation { selected = tab }
        } label: {
            VStack(spacing: 3) {
                ZStack(alignment: .topTrailing) {
                    pillIcon(tab.systemImage, tab: tab, selected: isSelected)
                    if badge > 0 {
                        Text("\(min(badge, 99))")
                            .font(.system(size: 10, weight: .bold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 5)
                            .padding(.vertical, 1)
                            .background(Theme.danger, in: Capsule())
                            .offset(x: layoutDirection == .rightToLeft ? -4 : 4, y: -4)
                    }
                }
                Text(LocalizedStringKey(tab.label))
                    .font(.system(size: 11, weight: isSelected ? .bold : .medium))
                    .foregroundStyle(isSelected ? primary : Theme.muted)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    /// The icon slot; selected, it becomes the tinted interactive glass pill
    /// the container morphs from tab to tab.
    @available(iOS 26.0, *)
    @ViewBuilder
    private func pillIcon(_ systemImage: String, tab: HomeTab, selected: Bool) -> some View {
        let icon = Image(systemName: systemImage)
            .font(.system(size: 17, weight: .semibold))
            .foregroundStyle(selected ? Color.white : Theme.muted)
            .frame(width: 54, height: 30)
        if selected {
            icon
                .glassEffect(Glass.regular.tint(primary).interactive(), in: Capsule())
                .glassEffectID(tab, in: selectionPill)
        } else {
            icon
        }
    }

    private func item(_ tab: HomeTab, badge: Int) -> some View {
        let isSelected = selected == tab
        return Button {
            selected = tab
        } label: {
            VStack(spacing: 3) {
                ZStack(alignment: .topTrailing) {
                    Image(systemName: tab.systemImage)
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(isSelected ? Color.white : Theme.muted)
                        .frame(width: 54, height: 30)
                        .background(isSelected ? primary : Color.clear, in: Capsule())
                    if badge > 0 {
                        Text("\(min(badge, 99))")
                            .font(.system(size: 10, weight: .bold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 5)
                            .padding(.vertical, 1)
                            .background(Theme.danger, in: Capsule())
                            .offset(x: layoutDirection == .rightToLeft ? -4 : 4, y: -4)
                    }
                }
                Text(LocalizedStringKey(tab.label))
                    .font(.system(size: 11, weight: isSelected ? .bold : .medium))
                    .foregroundStyle(isSelected ? primary : Theme.muted)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

// MARK: - Top bar

struct TopBar: View {
    let state: AppState
    let config: PublicSiteConfig?

    var body: some View {
        if #available(iOS 26.0, *) {
            content
                .background(alignment: .top) {
                    // The glass strip covers the status-bar band too, the way
                    // a system bar does; scrolled content blurs beneath it.
                    Color.clear
                        .glassEffect(.regular, in: Rectangle())
                        .ignoresSafeArea(edges: .top)
                }
        } else {
            content
                .background(Theme.surface)
                .overlay(alignment: .bottom) { Divider() }
        }
    }

    private var content: some View {
        HStack(spacing: 10) {
            brandIcon
            VStack(alignment: .leading, spacing: 2) {
                Text(config?.siteName ?? "LanShare")
                    .font(.headline.weight(.bold))
                    .foregroundStyle(.primary)
                Text("Local pairing")
                    .font(.caption2)
                    .foregroundStyle(Theme.muted)
                    .lineLimit(1)
            }
            Spacer()
            StatusPill(state.phaseLabel, tint: phaseTint)
        }
        // Mirror the connect page's card grid (frame ≤ `wideWidth`, then 16pt
        // padding, then centre): the brand and status pill line up with the
        // cards' left/right edges on every canvas. The earlier 640pt cap read
        // as huge dead padding on an iPad Pro; edge-pinning overshot the other
        // way and ignored the grid.
        .frame(maxWidth: AdaptiveLayout.wideWidth)
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity)
    }

    private var brandIcon: some View {
        Image("BrandIcon")
            .resizable()
            .frame(width: 42, height: 42)
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(Theme.line, lineWidth: 1))
            .accessibilityLabel(config?.siteName ?? "LanShare")
    }

    private var phaseTint: Color {
        if state.channelReady { return .green }
        if state.phaseLabel == "Connecting" { return Theme.amber }
        return Theme.muted
    }
}

// MARK: - Panels and primitives

/// Card surface matching Android's `Panel` (rounded, bordered, 16pt padding).
struct Panel<Content: View>: View {
    private let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            content
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Theme.line, lineWidth: 1))
    }
}

struct FieldLabel: View {
    let text: String

    var body: some View {
        Text(LocalizedStringKey(text))
            .font(.caption.weight(.bold))
            .foregroundStyle(Theme.muted)
    }
}

struct StatusPill: View {
    let label: String
    var tint: Color = Theme.muted

    init(_ label: String, tint: Color = Theme.muted) {
        self.label = label
        self.tint = tint
    }

    var body: some View {
        Text(LocalizedStringKey(label))
            .font(.caption2.weight(.bold))
            .foregroundStyle(tint)
            .lineLimit(1)
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(Theme.surfaceAlt, in: Capsule())
            .overlay(Capsule().strokeBorder(Theme.line, lineWidth: 1))
    }
}

struct TtlBadge: View {
    let value: String
    let primary: Color

    var body: some View {
        Text("Secure \(value)")
            .font(.caption2.weight(.bold))
            .foregroundStyle(.white)
            .padding(.horizontal, 16)
            .padding(.vertical, 9)
            .background(value.hasPrefix("00:") ? Theme.danger : primary, in: RoundedRectangle(cornerRadius: 14))
    }
}

struct DeviceBox: View {
    let label: String
    /// Either a device name (shown verbatim) or, when `localizedValue` is set, a
    /// localization key such as "Waiting" so the placeholder follows the app
    /// language the way Android's `stringResource(R.string.wait)` does.
    let value: String
    var localizedValue = false

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(LocalizedStringKey(label))
                .font(.caption)
                .foregroundStyle(Theme.muted)
            Group {
                if localizedValue {
                    Text(LocalizedStringKey(value))
                } else {
                    Text(value)
                }
            }
            .font(.subheadline.weight(.bold))
            .lineLimit(2)
            .minimumScaleFactor(0.8)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .background(Theme.surfaceAlt, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Theme.line, lineWidth: 1))
    }
}

enum StepStatus {
    case wait, checking, passed, pairing, done, failed, success

    var label: String {
        switch self {
        case .wait: return "Waiting"
        case .checking: return "Checking"
        case .passed: return "Passed"
        case .pairing: return "Pairing"
        case .done: return "Done"
        case .failed: return "Failed"
        case .success: return "Success"
        }
    }

    var tint: Color {
        switch self {
        case .passed, .done, .success: return .green
        case .checking, .pairing: return Theme.amber
        case .failed: return Theme.danger
        case .wait: return Theme.muted
        }
    }
}

struct StepRow: View {
    let label: String
    let status: StepStatus

    var body: some View {
        HStack {
            Text(LocalizedStringKey(label)).foregroundStyle(.primary)
            Spacer()
            Text(LocalizedStringKey(status.label)).fontWeight(.bold).foregroundStyle(status.tint)
        }
        .padding(10)
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Theme.line, lineWidth: 1))
    }
}

struct DiagRow: View {
    let label: String
    let value: String

    var body: some View {
        HStack {
            Text(LocalizedStringKey(label)).font(.caption).foregroundStyle(Theme.muted)
            Spacer()
            Text(LocalizedStringKey(value)).font(.caption.weight(.bold)).foregroundStyle(.primary)
        }
    }
}

struct ErrorBanner: View {
    let message: String
    var onDismiss: (() -> Void)?

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(Theme.danger)
            Text(LocalizedStringKey(message))
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Theme.danger)
                .frame(maxWidth: .infinity, alignment: .leading)
            if let onDismiss {
                Button(action: onDismiss) {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(Theme.muted)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(10)
        .background(Theme.danger.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Theme.danger.opacity(0.3), lineWidth: 1))
    }
}

// MARK: - Buttons

/// Filled 40pt button matching Android's primary `WebButton`.
struct PrimaryButton: View {
    let label: String
    var loading = false
    var enabled = true
    let primary: Color
    var fullWidth = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if loading { ProgressView().controlSize(.small).tint(.white) }
                Text(LocalizedStringKey(label)).fontWeight(.bold)
            }
            .frame(maxWidth: fullWidth ? .infinity : nil)
            .frame(height: 40)
        }
        .prominentGlassButtonStyle()
        .tint(primary)
        .disabled(!enabled || loading)
    }
}

/// Outlined 40pt button matching Android's secondary `WebButton`.
struct SecondaryButton: View {
    let label: String
    var enabled = true
    let primary: Color
    var fullWidth = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(LocalizedStringKey(label)).fontWeight(.bold)
                .frame(maxWidth: fullWidth ? .infinity : nil)
                .frame(height: 40)
        }
        .secondaryGlassButtonStyle()
        .tint(primary)
        .disabled(!enabled)
    }
}

// MARK: - Transfer list

/// One file row, matching Android's `FileList` card (index, bold title,
/// compact action icons, and a progress bar only while bytes move).
struct TransferCardView: View {
    let card: TransferCardState
    let index: Int
    let primary: Color
    var onAccept: ((String) -> Void)?
    var onCancel: ((String) -> Void)?

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Text("\(index + 1)")
                .font(.caption)
                .foregroundStyle(Theme.muted)
                .frame(width: 18, alignment: .leading)
                .padding(.top, 10)
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(card.title)
                        .font(.subheadline.weight(.bold))
                        .lineLimit(1)
                    Spacer()
                    actionIcons
                }
                Text(LocalizedStringKey(card.status))
                    .font(.caption2)
                    .foregroundStyle(statusColor)
                if showsProgress {
                    ProgressView(value: Double(card.progressPercent ?? 0), total: 100)
                        .progressViewStyle(.linear)
                        .tint(primary)
                }
            }
        }
        .padding(.leading, 12)
        .padding(.trailing, 8)
        .padding(.vertical, 8)
        .background(Theme.surfaceAlt, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Theme.line, lineWidth: 1))
    }

    private var showsProgress: Bool {
        card.status == TransferCardStatus.transferring
            || card.status == TransferCardStatus.receivingFromWeb
    }

    private var statusColor: Color {
        if card.status.hasPrefix(TransferCardStatus.errorPrefix)
            || card.status.hasPrefix(TransferCardStatus.rejectedPrefix) { return Theme.danger }
        return Theme.muted
    }

    @ViewBuilder
    private var actionIcons: some View {
        if card.actionLabel == TransferCardAction.accept, let onAccept {
            Button { onAccept(card.fileId) } label: {
                Image(systemName: "arrow.down.circle.fill")
                    .font(.title3)
                    .foregroundStyle(primary)
            }
            .buttonStyle(.plain)
            .disabled(card.actionLabel != TransferCardAction.accept)
        }
        if card.canCancel, let onCancel {
            Button { onCancel(card.fileId) } label: {
                Image(systemName: "xmark.circle.fill")
                    .font(.title3)
                    .foregroundStyle(Theme.danger)
            }
            .buttonStyle(.plain)
        }
    }
}

/// File list with count + batch action, matching Android's `FileList`.
struct FileList: View {
    let title: String
    let emptyText: String
    let cards: [TransferCardState]
    let batchLabel: String
    let batchEnabled: Bool
    let primary: Color
    let onBatch: () -> Void
    var onAccept: ((String) -> Void)?
    var onCancel: ((String) -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(LocalizedStringKey(title)).font(.headline.weight(.bold))
                Text("\(cards.count)").font(.caption).foregroundStyle(Theme.muted)
                Spacer()
                if batchEnabled {
                    Button { onBatch() } label: {
                        Text(LocalizedStringKey(batchLabel)).font(.caption.weight(.bold))
                    }
                    .foregroundStyle(primary)
                }
            }
            if cards.isEmpty {
                Text(LocalizedStringKey(emptyText))
                    .font(.footnote)
                    .foregroundStyle(Theme.muted)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 18)
                    .padding(.vertical, 14)
                    .background(Theme.surfaceAlt, in: RoundedRectangle(cornerRadius: 12))
                    .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Theme.line, lineWidth: 1))
            } else {
                ForEach(Array(cards.enumerated()), id: \.element.fileId) { index, card in
                    TransferCardView(card: card, index: index, primary: primary, onAccept: onAccept, onCancel: onCancel)
                }
            }
        }
    }
}

// MARK: - Diagnostics

struct DiagnosticsPanel: View {
    let state: AppState
    let primary: Color
    @State private var expanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button {
                withAnimation(.easeInOut(duration: 0.2)) { expanded.toggle() }
            } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Diagnostics").font(.headline.weight(.bold)).foregroundStyle(.primary)
                        Text(LocalizedStringKey(state.directConnectionFailed ? "Direct connection failed" : state.phaseLabel))
                            .font(.caption)
                            .foregroundStyle(state.directConnectionFailed ? Theme.danger : Theme.muted)
                            .lineLimit(1)
                    }
                    Spacer()
                    Image(systemName: "chevron.down")
                        .foregroundStyle(primary)
                        .rotationEffect(.degrees(expanded ? 180 : 0))
                }
                .padding(16)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if expanded {
                Divider()
                VStack(spacing: 10) {
                    DiagRow(label: "Signaling", value: state.channelReady ? "Connected" : state.phaseLabel)
                    DiagRow(label: "Session", value: state.channelReady ? "Connected" : "Not connected")
                    DiagRow(label: "DataChannel", value: state.channelReady ? "Open" : "Not connected")
                    DiagRow(label: "Path", value: state.channelReady ? state.connectionPath.label : "Unknown")
                    DiagRow(label: "Failure", value: state.directConnectionFailed ? "Direct connection failed" : "None")
                }
                .padding(16)
            }
        }
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Theme.line, lineWidth: 1))
    }
}

struct NetworkHelpCard: View {
    let primary: Color

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "network")
                .foregroundStyle(primary)
            VStack(alignment: .leading, spacing: 4) {
                Text("Network connection help")
                    .font(.subheadline.weight(.bold))
                Text("If the current network blocks device-to-device connections, connect both devices to the same phone hotspot and pair again.")
                    .font(.caption)
                    .foregroundStyle(Theme.muted)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(Theme.surfaceAlt, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Theme.line, lineWidth: 1))
    }
}
