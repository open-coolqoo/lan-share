import SwiftUI

/// Connect tab: join-by-code + scan (PeerCard), share-this-device code (SelfCard),
/// live connection status with pairing steps, paired summary, and diagnostics.
/// Mirrors Android's `ConnectionTabScreen` ordering.
struct ConnectView: View {
    @Bindable var model: AppViewModel
    var onOpenShare: () -> Void
    @State private var showScanner = false
    @State private var showHotspotGuide = false

    private var primary: Color { Theme.primary(model.state.siteConfig) }

    /// The Duo's unfolded display and any regular-width window get the
    /// two-column page; a phone-sized canvas keeps the single column.
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    private var wide: Bool { horizontalSizeClass == .regular }

    /// One half of the two-column page. Both halves are flexible, so the
    /// HStack splits the capped width evenly.
    private func column<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        VStack(spacing: 16) { content() }
            .frame(maxWidth: .infinity, alignment: .top)
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                if model.state.directConnectionFailed {
                    ErrorBanner(
                        message: "Lan-Share currently connects only on the local network. Keep both devices on the same Wi-Fi or LAN, turn off VPN or proxy, and check guest network, AP isolation, or firewall rules.",
                        onDismiss: { model.dismissDirectConnectionFailure() },
                    )
                }
                if model.state.channelReady {
                    if wide {
                        HStack(alignment: .top, spacing: 16) {
                            column { pairedSummaryPanel }
                            column { connectionStatusPanel }
                        }
                    } else {
                        pairedSummaryPanel
                        connectionStatusPanel
                    }
                } else if wide {
                    // Peer entry and this device's code sit side by side: on
                    // the unfolded display they are the two halves of pairing,
                    // and one column of cards would only stretch.
                    HStack(alignment: .top, spacing: 16) {
                        column {
                            peerCard
                            connectionStatusPanel
                        }
                        column {
                            selfCard
                            if !model.state.pendingSendItems.isEmpty {
                                pendingSharedFilesPanel
                            }
                        }
                    }
                } else {
                    peerCard
                    selfCard
                    connectionStatusPanel
                    if !model.state.pendingSendItems.isEmpty {
                        pendingSharedFilesPanel
                    }
                }
                if wide {
                    HStack(alignment: .top, spacing: 16) {
                        column { NetworkHelpCard(primary: primary) }
                        column { DiagnosticsPanel(state: model.state, primary: primary) }
                    }
                } else {
                    NetworkHelpCard(primary: primary)
                    DiagnosticsPanel(state: model.state, primary: primary)
                }
            }
            .frame(maxWidth: wide ? AdaptiveLayout.wideWidth : AdaptiveLayout.readableWidth)
            .padding(16)
            .frame(maxWidth: .infinity)
        }
        .background(Theme.pageBackground)
        .sheet(isPresented: $showScanner) {
            ScannerSheet(primary: primary, onScanned: { model.joinScannedCode($0) })
        }
        // Mirrors Android's DirectConnectionFailureDialog: the dialog grabs
        // attention once, while the banner above keeps showing the failure
        // until it recovers or is dismissed.
        .alert(
            "Direct connection failed",
            isPresented: Binding(
                get: { model.state.directConnectionFailureNotice },
                set: { if !$0 { model.clearDirectConnectionFailure() } },
            ),
        ) {
            Button("Not now", role: .cancel) { model.clearDirectConnectionFailure() }
            Button("Use the same phone hotspot") {
                model.clearDirectConnectionFailure()
                // iOS presents one alert at a time; the guide needs to wait for
                // this one to finish dismissing or it is silently dropped.
                Task {
                    try? await Task.sleep(nanoseconds: 500_000_000)
                    showHotspotGuide = true
                }
            }
        } message: {
            dialogText(
                "Lan-Share currently connects only on the local network. Keep both devices on the same Wi-Fi or LAN, turn off VPN or proxy, and check guest network, AP isolation, or firewall rules.",
                "If the network blocks peer connections, turn on a phone hotspot and connect both devices to that hotspot, then create a new pairing code.",
            )
        }
        // Mirrors Android's HotspotConnectionGuideDialog. iOS has no API to
        // deep-link into Personal Hotspot settings, so unlike Android there is
        // no settings button — the steps carry the guidance.
        .alert("Retry with the same phone hotspot", isPresented: $showHotspotGuide) {
            Button("Not now", role: .cancel) {}
            Button("Both devices are connected") { model.disconnect() }
        } message: {
            dialogText(
                "A hotspot is optional. Use it when the current Wi-Fi or LAN does not allow the two devices to connect directly.",
                "Option 1: turn on a hotspot on this device, then connect the other device to it. Option 2: connect both devices to an existing phone hotspot.",
                "After both devices are on the same hotspot, return here and confirm to create a new pairing code.",
            )
        }
    }

    /// Alert bodies take a single `Text`; joining the localized paragraphs with
    /// blank lines keeps each paragraph its own translation key.
    private func dialogText(_ keys: String...) -> Text {
        var result = Text("")
        for (index, key) in keys.enumerated() {
            if index > 0 { result = result + Text("\n\n") }
            result = result + Text(LocalizedStringKey(key))
        }
        return result
    }

    // MARK: Peer card

    private var peerCard: some View {
        Panel {
            HStack {
                Text("Connect").font(.title3.weight(.bold))
                Spacer()
                StatusPill("8 digits")
            }
            FieldLabel(text: "Peer code")
            TextField(
                "Enter peer code",
                text: Binding(get: { model.state.peerCode }, set: { model.setRoomCode($0) }),
            )
            .keyboardType(.numberPad)
            .textFieldStyle(.roundedBorder)
            .frame(maxWidth: .infinity)
            .disabled(model.state.connectionBusy)
            PrimaryButton(
                label: model.state.joiningRoom ? "Connecting" : "Connect",
                loading: model.state.joiningRoom,
                enabled: model.state.canJoinPeerCode && !model.state.connectionBusy,
                primary: primary,
                action: { model.joinRoom(code: model.state.peerCode) },
            )
            SecondaryButton(label: "Scan to connect", enabled: !model.state.connectionBusy, primary: primary) {
                showScanner = true
            }
            Text("Entering a peer code does not change this device's local code.")
                .font(.caption)
                .foregroundStyle(Theme.muted)
            if (model.state.peerCode.count == 8 || model.state.peerCode.isEmpty),
               !model.state.roomError.isEmpty {
                ErrorBanner(message: model.state.roomError)
            }
            if model.state.peerCode.count == 8, model.state.peerCodeValidation != .ok {
                ErrorBanner(message: "Peer code: \(validationLabel)")
            }
        }
    }

    private var validationLabel: String {
        switch model.state.peerCodeValidation {
        case .invalid: return "invalid"
        case .selfCode: return "self code"
        case .refreshed: return "refreshed code"
        case .ok: return ""
        }
    }

    // MARK: Self card

    private var selfCard: some View {
        Panel {
            HStack(alignment: .top) {
                Text("This device").font(.title3.weight(.bold))
                Spacer()
                TtlBadge(value: model.state.ttlLabel, primary: primary)
            }
            FieldLabel(text: "Local code")
            Text(model.state.createdCode ?? "")
                .font(.system(size: 42, weight: .black, design: .monospaced))
                .kerning(3)
                .frame(maxWidth: .infinity, minHeight: 82)
                .background(primary.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(primary.opacity(0.25), lineWidth: 1))
            // The code could not be fetched. Shown here, beside the code itself
            // and with a retry, rather than in the peer-code card where it read
            // as a bad peer code.
            if !model.state.codeError.isEmpty {
                ErrorBanner(message: model.state.codeError)
            }
            Text("For direct phone-to-computer transfer, open Lan-Share on the other device on the same network and enter this code. Same-LAN connections usually have the best success rate and speed.")
                .font(.caption)
                .foregroundStyle(Theme.muted)
            pairingQrBlock
            HStack(spacing: 10) {
                PrimaryButton(
                    label: model.state.createdCode == nil ? "Create code" : "Refresh code",
                    enabled: !model.state.connectionBusy,
                    primary: primary,
                    action: { model.createRoom() },
                )
                SecondaryButton(
                    label: model.state.codeCopied ? "Copied" : "Copy",
                    enabled: model.state.createdCode != nil && !model.state.connectionBusy,
                    primary: primary,
                    action: { model.copyCode() },
                )
            }
            // A missing code is usually the local network permission still being
            // unanswered, so the retry sits directly under the code with the
            // reason it is empty.
            if model.state.createdCode == nil, !model.state.codeError.isEmpty {
                SecondaryButton(label: "Retry", enabled: !model.state.joiningRoom, primary: primary) {
                    model.createRoom()
                }
            }
        }
    }

    private var pairingQrBlock: some View {
        HStack(spacing: 12) {
            if let code = model.state.createdCode,
               let qr = PairingQr.image(for: PairingCodes.buildPairingURL(
                   origin: model.state.origin,
                   code: code,
                   locale: model.state.locale,
               )) {
                Image(uiImage: qr)
                    .interpolation(.none)
                    .frame(width: 86, height: 86)
                    .padding(3)
                    .background(Theme.surface, in: RoundedRectangle(cornerRadius: 10))
                    .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(Theme.line, lineWidth: 1))
                    .accessibilityLabel("QR code for pairing this device")
            } else {
                Text("...")
                    .font(.title.weight(.black))
                    .foregroundStyle(primary)
                    .frame(minWidth: 86, maxWidth: 86, minHeight: 86)
                    .background(Theme.surface, in: RoundedRectangle(cornerRadius: 10))
                    .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(Theme.line, lineWidth: 1))
            }
            VStack(alignment: .leading, spacing: 4) {
                Text("Scan to pair").font(.subheadline.weight(.bold))
                Text("Scan with any QR app to open the link, pair automatically, and go straight to the connected page. The link uses this short-lived code and does not include a session token.")
                    .font(.caption)
                    .foregroundStyle(Theme.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .background(Theme.surfaceAlt, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Theme.line, lineWidth: 1))
    }

    // MARK: Connection status

    private var connectionStatusPanel: some View {
        Panel {
            HStack {
                Text("Connection status").font(.title3.weight(.bold))
                Spacer()
                StatusPill(model.state.directionLabel)
            }
            HStack(spacing: 10) {
                DeviceBox(label: "Local", value: model.state.localDeviceLabel)
                DeviceBox(
                    label: "Peer",
                    value: model.state.peerDeviceLabel.isEmpty ? "Waiting" : model.state.peerDeviceLabel,
                    localizedValue: model.state.peerDeviceLabel.isEmpty,
                )
            }
            if model.state.isPeerConnectionMode {
                StepRow(label: "Check network", status: model.state.joinedRoom != nil ? .passed : .checking)
                StepRow(label: "Check code", status: model.state.joinedRoom != nil ? .passed : .wait)
                StepRow(label: "Pair", status: model.state.channelReady ? .done : (model.state.signalingReady ? .pairing : .wait))
                StepRow(label: "Result", status: model.state.channelReady ? .success : .wait)
            } else {
                Text("Connection steps appear only when this device connects to another device.")
                    .font(.caption)
                    .foregroundStyle(Theme.muted)
            }
        }
    }

    // MARK: Paired summary

    private var pairedSummaryPanel: some View {
        Panel {
            FieldLabel(text: "Paired code")
            Text(model.state.pairedCode ?? "")
                .font(.system(size: 32, weight: .black, design: .monospaced))
                .kerning(3)
            Text("1-to-1 paired. A third browser using the same code is rejected.")
                .font(.caption)
                .foregroundStyle(Theme.muted)
            HStack(spacing: 10) {
                DeviceBox(label: "Local", value: model.state.localDeviceLabel)
                DeviceBox(label: "Peer", value: model.state.peerDeviceLabel.isEmpty ? "Web" : model.state.peerDeviceLabel)
            }
            SecondaryButton(label: "Disconnect", primary: primary, action: { model.disconnect() })
        }
    }

    // MARK: Pending shared files

    private var pendingSharedFilesPanel: some View {
        Button(action: onOpenShare) {
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text("Pending shared files").font(.subheadline.weight(.bold))
                    Text("\(model.state.pendingSendItems.count) file(s) will be sent after this device connects.")
                        .font(.caption)
                        .foregroundStyle(Theme.muted)
                }
                Spacer()
                Image(systemName: "chevron.forward").font(.title3.weight(.bold)).foregroundStyle(primary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(14)
            .background(Theme.surfaceAlt, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Theme.line, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}