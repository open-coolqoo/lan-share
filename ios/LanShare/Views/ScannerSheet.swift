import SwiftUI

/// Full-screen camera scanner for pairing QR codes.
///
/// Mirrors Android's `QrScannerScreen`: a dark live preview with a back button
/// and a reticle, an invalid-code hint that leaves the camera running, and
/// panels for the camera permission and for a camera that cannot be used.
struct ScannerSheet: View {
    let primary: Color
    let onScanned: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @Environment(\.scenePhase) private var scenePhase
    @State private var access = CameraPermission.current()
    @State private var unavailable = false
    @State private var hintVisible = false
    @State private var hintTask: Task<Void, Never>?

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if access == .authorized, !unavailable {
                scanner
            } else {
                panel
            }
        }
        // One back button for every state, pinned to the screen rather than to
        // whichever content block happens to be showing. Overlaying it on the
        // centered panels put it beside the message instead of the top corner.
        .overlay(alignment: .topLeading) { backButton }
        .preferredColorScheme(.dark)
        .onChange(of: scenePhase) { _, phase in
            // Coming back from Settings is how a denied permission gets fixed,
            // so re-read it rather than leaving the user staring at the panel.
            if phase == .active { access = CameraPermission.current() }
        }
        .onDisappear { hintTask?.cancel() }
    }

    private var scanner: some View {
        QrScannerView(onPayload: consume, onUnavailable: { unavailable = true })
            .ignoresSafeArea()
            .overlay { reticle.allowsHitTesting(false) }
    }

    private var reticle: some View {
        VStack(spacing: 16) {
            RoundedRectangle(cornerRadius: 24)
                .strokeBorder(Color.white.opacity(0.85), lineWidth: 3)
                .frame(width: 236, height: 236)
            Text("Scan the connection QR code shown on the other device.")
                .font(.subheadline.weight(.medium))
                .foregroundStyle(.white.opacity(0.9))
                .multilineTextAlignment(.center)
            if hintVisible {
                Text("QR code error. Scan the connection code QR code.")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.white)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .background(Theme.danger.opacity(0.92), in: Capsule())
                    .transition(.opacity)
            }
        }
        .padding(.horizontal, 32)
        .animation(.easeInOut(duration: 0.2), value: hintVisible)
    }

    private var backButton: some View {
        Button { dismiss() } label: {
            if #available(iOS 26.0, *) {
                // The camera-app treatment: a round interactive Liquid Glass
                // button over the live preview.
                Image(systemName: "chevron.backward")
                    .font(.system(size: 17, weight: .bold))
                    .foregroundStyle(.white)
                    .frame(width: 34, height: 34)
            } else {
                Image(systemName: "chevron.backward")
                    .font(.system(size: 17, weight: .bold))
                    .foregroundStyle(Color(white: 0.54))
                    .frame(width: 34, height: 34)
                    .background(Color.white, in: Circle())
            }
        }
        // No-op below iOS 26, where the white disc above is the button.
        .liquidGlass(in: Circle(), interactive: true)
        .padding(.leading, 12)
        .padding(.top, 14)
        .accessibilityLabel("Back")
    }

    // MARK: - Permission and availability panels

    @ViewBuilder
    private var panel: some View {
        VStack(spacing: 16) {
            if unavailable {
                message("QR code scanner is unavailable. Check camera permission and try again.")
                outlinedButton("Cancel transfer") { dismiss() }
            } else if access == .denied {
                message("Camera permission needed", font: .title3.weight(.bold))
                message("Enable camera permission in system settings to scan the connection QR code.")
                filledButton("Open settings") { openSettings() }
                outlinedButton("Cancel transfer") { dismiss() }
            } else {
                message("QR code scanner is unavailable. Check camera permission and try again.")
                filledButton("Scan to connect") { requestAccess() }
                outlinedButton("Cancel transfer") { dismiss() }
            }
        }
        .padding(28)
    }

    private func message(_ key: String, font: Font = .subheadline) -> some View {
        Text(LocalizedStringKey(key))
            .font(font)
            .foregroundStyle(.white)
            .multilineTextAlignment(.center)
    }

    /// The primary panel action: tinted glass over the black preview on
    /// iOS 26, the drawn brand capsule below.
    @ViewBuilder
    private func filledButton(_ label: String, action: @escaping () -> Void) -> some View {
        if #available(iOS 26.0, *) {
            Button(action: action) {
                Text(LocalizedStringKey(label))
                    .fontWeight(.bold)
                    .frame(maxWidth: .infinity)
                    .frame(height: 44)
            }
            .buttonStyle(.glassProminent)
            .tint(primary)
        } else {
            Button(action: action) {
                Text(LocalizedStringKey(label))
                    .fontWeight(.bold)
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .frame(height: 44)
                    .background(primary, in: Capsule())
            }
        }
    }

    private func outlinedButton(_ label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(LocalizedStringKey(label))
                .fontWeight(.bold)
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity)
                .frame(height: 44)
                .overlay(Capsule().strokeBorder(Color.white.opacity(0.5), lineWidth: 1))
        }
    }

    // MARK: - Behaviour

    /// A payload is consumed only when it carries a usable pairing code, which
    /// stops the scan. Anything else shows the invalid-code hint and leaves the
    /// camera live for another attempt.
    private func consume(_ payload: String) -> Bool {
        guard let code = PairingCodes.pairingCode(fromScanResult: payload) else {
            showInvalidHint()
            return false
        }
        onScanned(code)
        dismiss()
        return true
    }

    private func showInvalidHint() {
        hintVisible = true
        hintTask?.cancel()
        hintTask = Task {
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            guard !Task.isCancelled else { return }
            hintVisible = false
        }
    }

    private func requestAccess() {
        Task {
            let granted = await CameraPermission.request()
            access = granted ? .authorized : .denied
        }
    }

    private func openSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        openURL(url)
    }
}
