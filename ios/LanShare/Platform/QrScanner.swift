import AVFoundation
import SwiftUI

/// Camera authorization, reduced to the states the scanner UI reacts to.
/// `restricted` folds into `.denied` because Settings is the only remedy for
/// either, and that is what the denied panel offers.
enum CameraAccess: Equatable {
    case authorized
    case notDetermined
    case denied
}

enum CameraPermission {
    static func current() -> CameraAccess {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            return .authorized
        case .notDetermined:
            return .notDetermined
        default:
            return .denied
        }
    }

    /// Prompts when the user has not been asked yet, and reports the existing
    /// answer otherwise.
    static func request() async -> Bool {
        await withCheckedContinuation { continuation in
            AVCaptureDevice.requestAccess(for: .video) { granted in
                continuation.resume(returning: granted)
            }
        }
    }
}

/// Metadata QR scanner over a live camera preview.
///
/// Every distinct payload goes to `onPayload`, and scanning stops only when
/// that returns `true`. A QR that carries no pairing code therefore leaves the
/// camera running so the user can try another one, which is what Android does
/// when it toasts an invalid code and keeps scanning.
struct QrScannerView: UIViewControllerRepresentable {
    /// Returns true once the payload has been consumed, which ends the scan.
    let onPayload: (String) -> Bool
    /// Reports a camera that could not be configured at all.
    let onUnavailable: () -> Void

    /// Maps how the device is held to the `AVCaptureConnection.videoRotationAngle`
    /// that makes the preview appear upright.
    ///
    /// The sensor's native orientation is landscape with the top of the device
    /// to the left, so portrait needs a 90° rotation — 0° is *not* upright, and
    /// using it renders the preview sideways. Keyed on the device orientation
    /// because that is the space `AVCaptureVideoOrientation` lives in; matches
    /// Apple's AVCam sample.
    static func videoRotationAngle(for deviceOrientation: UIDeviceOrientation) -> CGFloat {
        switch deviceOrientation {
        case .portraitUpsideDown:
            return 270
        case .landscapeLeft:
            return 0
        case .landscapeRight:
            return 180
        default:
            // Portrait, plus face-up/face-down/unknown, where the UI keeps the
            // portrait layout anyway.
            return 90
        }
    }

    /// Prefer continuous autofocus after a tap so the camera keeps tracking
    /// when the QR code or phone moves; fall back to a single sweep only on
    /// devices that do not support continuous autofocus.
    static func focusModeAfterTap(
        supportsContinuousAutoFocus: Bool,
        supportsAutoFocus: Bool,
    ) -> AVCaptureDevice.FocusMode? {
        if supportsContinuousAutoFocus {
            return .continuousAutoFocus
        }
        if supportsAutoFocus {
            return .autoFocus
        }
        return nil
    }

    func makeUIViewController(context: Context) -> ScannerViewController {
        let controller = ScannerViewController()
        controller.onPayload = onPayload
        controller.onUnavailable = onUnavailable
        return controller
    }

    func updateUIViewController(_ controller: ScannerViewController, context: Context) {
        controller.onPayload = onPayload
        controller.onUnavailable = onUnavailable
    }

    @MainActor
    final class ScannerViewController: UIViewController {
        var onPayload: (String) -> Bool = { _ in true }
        var onUnavailable: () -> Void = {}

        // `startRunning`/`stopRunning` block, so they are dispatched off the main
        // thread. That crossing needs the session to be sendable; AVFoundation
        // predates strict concurrency and documents the session as safe to
        // control from any thread.
        nonisolated(unsafe) private let session = AVCaptureSession()
        private var previewLayer: AVCaptureVideoPreviewLayer?
        private var metadataOutput: AVCaptureMetadataOutput?
        private var device: AVCaptureDevice?
        private var focusIndicator: UIView?
        private var lastPayload = ""
        private var stopped = false

        override func viewDidLoad() {
            super.viewDidLoad()
            view.backgroundColor = .black
            // The tap lands wherever it falls over the SwiftUI overlay; SwiftUI
            // buttons (back, cancel) run their own action in addition.
            view.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(handleTap(_:))))
            configure()
        }

        override func viewDidLayoutSubviews() {
            super.viewDidLayoutSubviews()
            previewLayer?.frame = view.bounds
            applyRotation()
        }

        override func viewWillTransition(to size: CGSize, with coordinator: UIViewControllerTransitionCoordinator) {
            super.viewWillTransition(to: size, with: coordinator)
            // Re-apply with the transition rather than waiting for the layout
            // pass, or the preview visibly lags one rotation behind.
            coordinator.animate(alongsideTransition: { [self] _ in applyRotation() })
        }

        override func viewWillAppear(_ animated: Bool) {
            super.viewWillAppear(animated)
            // Re-assert before the pipeline starts: attaching an input switches
            // the device format, and a format switch resets device settings.
            if let device { configureFocus(on: device) }
            start()
        }

        override func viewWillDisappear(_ animated: Bool) {
            super.viewWillDisappear(animated)
            stop()
        }

        private func configure() {
            guard let device = AVCaptureDevice.default(for: .video),
                  let input = try? AVCaptureDeviceInput(device: device)
            else {
                reportUnavailable()
                return
            }
            self.device = input.device
            session.beginConfiguration()
            // The full-sensor photo pipeline previews noticeably sharper than the
            // 1080p video presets, which are upscaled to fill a phone screen.
            if session.canSetSessionPreset(.photo) {
                session.sessionPreset = .photo
            }
            guard session.canAddInput(input) else {
                session.commitConfiguration()
                reportUnavailable()
                return
            }
            session.addInput(input)
            let output = AVCaptureMetadataOutput()
            guard session.canAddOutput(output) else {
                session.commitConfiguration()
                reportUnavailable()
                return
            }
            session.addOutput(output)
            metadataOutput = output
            // The object types are only accepted once the output has a
            // connection, so this must follow `addOutput`.
            output.metadataObjectTypes = [.qr]
            output.setMetadataObjectsDelegate(self, queue: .main)
            session.commitConfiguration()
            // Only meaningful once the session has settled its preset, since the
            // format it picks is what the focus settings attach to.
            configureFocus(on: input.device)

            let previewLayer = AVCaptureVideoPreviewLayer(session: session)
            previewLayer.videoGravity = .resizeAspectFill
            previewLayer.frame = view.bounds
            view.layer.insertSublayer(previewLayer, at: 0)
            self.previewLayer = previewLayer
            applyRotation()
        }

        /// Continuous focus and exposure are what keep a QR held at arm's length
        /// sharp; without an explicit setting the lens can settle out of focus.
        private func configureFocus(on device: AVCaptureDevice) {
            guard device.isFocusModeSupported(.continuousAutoFocus)
                || device.isExposureModeSupported(.continuousAutoExposure)
            else { return }
            do {
                try device.lockForConfiguration()
                if device.isFocusModeSupported(.continuousAutoFocus) {
                    // The reticle is centered, so start the autofocus search there
                    // instead of leaving the device on its default subject area.
                    if device.isFocusPointOfInterestSupported {
                        device.focusPointOfInterest = CGPoint(x: 0.5, y: 0.5)
                    }
                    device.focusMode = .continuousAutoFocus
                    device.isSubjectAreaChangeMonitoringEnabled = true
                }
                if device.isExposureModeSupported(.continuousAutoExposure) {
                    if device.isExposurePointOfInterestSupported {
                        device.exposurePointOfInterest = CGPoint(x: 0.5, y: 0.5)
                    }
                    device.exposureMode = .continuousAutoExposure
                }
                device.unlockForConfiguration()
            } catch {
                // Non-fatal: the preview still runs on the device defaults.
            }
        }

        /// Deferred so a failure during `viewDidLoad` cannot mutate SwiftUI
        /// state in the middle of a view update.
        private func reportUnavailable() {
            Task { @MainActor [self] in onUnavailable() }
        }

        private func start() {
            guard previewLayer != nil else { return }
            DispatchQueue.global(qos: .userInitiated).async { [self] in startSession() }
        }

        private func stop() {
            DispatchQueue.global(qos: .userInitiated).async { [self] in stopSession() }
        }

        /// Non-isolated so the blocking capture-session calls can run off the
        /// main thread; `session` is `nonisolated(unsafe)` for the same reason.
        private nonisolated func startSession() {
            guard !session.isRunning else { return }
            session.startRunning()
        }

        private nonisolated func stopSession() {
            guard session.isRunning else { return }
            session.stopRunning()
        }

        /// Both connections need the angle: the preview to display upright, the
        /// metadata output so detection matches what is on screen.
        private func applyRotation() {
            let angle = QrScannerView.videoRotationAngle(for: UIDevice.current.orientation)
            applyRotation(to: previewLayer?.connection, angle: angle)
            applyRotation(to: metadataOutput?.connection(with: .metadata), angle: angle)
        }

        private func applyRotation(to connection: AVCaptureConnection?, angle: CGFloat) {
            guard let connection, connection.isVideoRotationAngleSupported(angle) else { return }
            connection.videoRotationAngle = angle
        }

        // MARK: - Tap to focus

        /// A tap moves the focus area to the QR code while keeping continuous
        /// autofocus enabled, so the lens can continue tracking distance changes.
        @objc private func handleTap(_ gesture: UITapGestureRecognizer) {
            guard let device, let previewLayer, device.isFocusPointOfInterestSupported else { return }
            let location = gesture.location(in: view)
            let devicePoint = previewLayer.captureDevicePointConverted(fromLayerPoint: location)
            do {
                try device.lockForConfiguration()
                defer { device.unlockForConfiguration() }
                if let focusMode = QrScannerView.focusModeAfterTap(
                    supportsContinuousAutoFocus: device.isFocusModeSupported(.continuousAutoFocus),
                    supportsAutoFocus: device.isFocusModeSupported(.autoFocus),
                ) {
                    device.focusPointOfInterest = devicePoint
                    device.focusMode = focusMode
                }
                if device.isExposurePointOfInterestSupported,
                   device.isExposureModeSupported(.continuousAutoExposure) {
                    device.exposurePointOfInterest = devicePoint
                    device.exposureMode = .continuousAutoExposure
                }
            } catch {
                // Non-fatal: continuous focus keeps running.
            }
            showFocusIndicator(at: location)
        }

        private func showFocusIndicator(at point: CGPoint) {
            focusIndicator?.removeFromSuperview()
            let indicator = UIView(frame: CGRect(x: 0, y: 0, width: 68, height: 68))
            indicator.center = point
            indicator.layer.borderColor = UIColor.white.cgColor
            indicator.layer.borderWidth = 1.5
            indicator.layer.cornerRadius = 10
            indicator.alpha = 0
            view.addSubview(indicator)
            focusIndicator = indicator
            UIView.animate(withDuration: 0.15) { indicator.alpha = 0.9 }
            UIView.animate(
                withDuration: 0.45,
                delay: 0.7,
                options: [.beginFromCurrentState],
                animations: {
                    indicator.alpha = 0
                    indicator.transform = CGAffineTransform(scaleX: 0.85, y: 0.85)
                },
                completion: { _ in indicator.removeFromSuperview() },
            )
        }
    }
}

// Metadata callbacks are registered on the main queue, so the body hops into
// the main actor explicitly via `MainActor.assumeIsolated`.
extension QrScannerView.ScannerViewController: AVCaptureMetadataOutputObjectsDelegate {
    nonisolated func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection,
    ) {
        // Extract the Sendable payload on the callback thread before hopping
        // into the main actor, so the non-Sendable objects never cross.
        guard let object = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              object.type == .qr,
              let value = object.stringValue
        else { return }
        MainActor.assumeIsolated {
            guard !stopped, value != lastPayload else { return }
            lastPayload = value
            if onPayload(value) {
                stopped = true
                stop()
            }
        }
    }
}
