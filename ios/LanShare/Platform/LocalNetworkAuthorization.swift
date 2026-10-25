import Foundation
import Network
import SwiftUI

/// Decides whether local network access was granted, from the only two signals
/// iOS offers.
///
/// There is no authorization-status API, so the answer has to be inferred. The
/// signals are noisy on their own:
///
/// * `NWBrowser` reports `.ready` once the browse is registered for discovery —
///   *not* once access is granted. On a first launch it goes ready while the
///   system alert is still on screen and the app's network path is still denied
///   (`NWPath` "Denied over Wi-Fi interface"), so `.ready` alone proves nothing.
/// * The same browse reports `kDNSServiceErr_PolicyDenied` when access is off,
///   but that error also arrives *while the system alert is still on screen*.
/// * The permission alert is app-modal, so it moves the scene to `.inactive`
///   and back to `.active` when it is answered. It can appear while the app is
///   still inactive from launch, in which case no transition is reported at all
///   and only the return to `.active` marks the answer.
///
/// The inference is kept here, apart from `NWBrowser`, so it can be unit tested
/// without a device.
struct LocalNetworkProbe {
    enum Outcome: Equatable {
        case pending
        case granted
        case refused
    }

    /// What the driver should do next. Delays ride along on the action so the
    /// driver stays a dumb executor.
    enum Action: Equatable {
        case cancelScheduled
        case schedule(after: Double)
        case resolve(Outcome)
    }

    enum BrowserEvent: Equatable {
        /// The browse is registered for discovery. Says nothing about access.
        case ready
        /// The browse reported `kDNSServiceErr_PolicyDenied`.
        case policyDenied
        case other
    }

    enum SceneEvent: Equatable {
        case becameActive
        case becameInactive
    }

    /// How long a registered browse must hold, with the app active and no alert
    /// seen, before access counts as granted. Keeps a browse that went ready
    /// just before the alert appeared from deciding anything.
    static let settleSeconds: Double = 0.8

    /// Grace period after the alert is answered, giving the browse time to
    /// report the result. Granting access activates the browse sessions almost
    /// immediately, so this only covers report latency.
    static let promptAnsweredGraceSeconds: Double = 1.0

    /// How long to wait for any signal before assuming access was refused on an
    /// earlier launch, where the system shows no alert at all.
    static let refusalWithoutPromptSeconds: Double = 2.0

    private(set) var outcome: Outcome = .pending
    /// Whether the system alert was actually observed on screen.
    private(set) var sawPrompt = false
    private var hasActivated = false
    private var appIsActive = false
    /// Whether the browse is currently registered. Not an access grant: it goes
    /// ready while the alert is up, and back to waiting when access is denied.
    private var browseLive = false

    mutating func handle(_ event: BrowserEvent) -> [Action] {
        guard outcome == .pending else { return [] }
        switch event {
        case .ready:
            browseLive = true
            // With an alert on screen, or one still on its way during launch,
            // the answer decides — not the browse. Only an app that is already
            // active with no alert in sight may settle on a live browse.
            guard !sawPrompt, appIsActive else { return [] }
            return [.schedule(after: Self.settleSeconds)]
        case .policyDenied:
            browseLive = false
            // Refused — unless the alert is up and this is just iOS reporting
            // the same denial it is asking the user about. Only act on it when
            // no alert was ever shown, which is the earlier-refusal case.
            guard !sawPrompt else { return [] }
            return [.schedule(after: Self.refusalWithoutPromptSeconds)]
        case .other:
            return []
        }
    }

    mutating func handle(_ event: SceneEvent) -> [Action] {
        guard outcome == .pending else { return [] }
        switch event {
        case .becameInactive:
            appIsActive = false
            // A first `.inactive` before the app has ever been active is part
            // of launching, not an alert. An alert can also appear while the
            // app is still inactive from launch; no event follows in that case,
            // and the pending decision simply waits for the answer below.
            guard hasActivated else { return [] }
            sawPrompt = true
            // Anything already counting down assumed no alert would appear, and
            // that assumption just stopped holding. Without this the splash
            // would dismiss while the alert was still on screen.
            return [.cancelScheduled]
        case .becameActive:
            let answeredPrompt = hasActivated && sawPrompt
            hasActivated = true
            appIsActive = true
            if answeredPrompt {
                return [.schedule(after: Self.promptAnsweredGraceSeconds)]
            }
            if browseLive {
                return [.schedule(after: Self.settleSeconds)]
            }
            // No alert (yet). Either access was refused on an earlier launch, or
            // the browser is about to report the refusal itself. Wait for
            // whichever comes first.
            return [.schedule(after: Self.refusalWithoutPromptSeconds)]
        }
    }

    /// Called when a scheduled wait elapses. Returns whether it took effect,
    /// which is false if the browse resolved in the meantime.
    mutating func confirmScheduled() -> Bool {
        guard outcome == .pending else { return false }
        outcome = browseLive ? .granted : .refused
        return true
    }
}

/// Drives the local network permission prompt and reports its outcome.
///
/// Starting the Bonjour browse is what triggers the prompt; ``LocalNetworkProbe``
/// explains how the answer is inferred from the browse state and the scene
/// phase. The app uses the outcome only to hold the splash until the user has
/// answered, and to retry room setup the permission blocked.
@MainActor
@Observable
final class LocalNetworkAuthorization {
    typealias Status = LocalNetworkProbe.Outcome

    private(set) var status: Status = .pending

    private var probe = LocalNetworkProbe()
    private var browser: NWBrowser?
    private var resolutionTask: Task<Void, Never>?

    /// `kDNSServiceErr_PolicyDenied`, reported when local network access is off.
    private static let policyDeniedCode: Int32 = -65570

    /// Starts the probe and, with it, the system prompt. Safe to call twice.
    func start() {
        guard browser == nil, status == .pending else { return }
        let parameters = NWParameters()
        parameters.includePeerToPeer = true
        let browser = NWBrowser(for: .bonjour(type: "_lanshare._tcp", domain: nil), using: parameters)
        browser.stateUpdateHandler = { [weak self] state in
            Task { @MainActor [weak self] in
                self?.handle(browserState: state)
            }
        }
        browser.start(queue: .main)
        self.browser = browser
    }

    /// Feed this from the app's scene phase observer, including the initial
    /// phase, so an answered alert can be told apart from one still on screen.
    func handleScenePhase(_ phase: ScenePhase) {
        switch phase {
        case .active:
            run(probe.handle(.becameActive))
        case .inactive:
            run(probe.handle(.becameInactive))
        default:
            break
        }
    }

    private func handle(browserState state: NWBrowser.State) {
        switch state {
        case .ready:
            run(probe.handle(.ready))
        case .waiting(let error), .failed(let error):
            run(probe.handle(Self.classify(error)))
        default:
            break
        }
    }

    private func run(_ actions: [LocalNetworkProbe.Action]) {
        for action in actions {
            switch action {
            case .cancelScheduled:
                resolutionTask?.cancel()
                resolutionTask = nil
            case .schedule(let seconds):
                resolutionTask?.cancel()
                resolutionTask = Task { [weak self] in
                    try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
                    guard !Task.isCancelled else { return }
                    self?.confirmScheduled()
                }
            case .resolve(let outcome):
                finish(outcome)
            }
        }
    }

    private func confirmScheduled() {
        resolutionTask = nil
        guard probe.confirmScheduled() else { return }
        finish(probe.outcome)
    }

    private func finish(_ outcome: LocalNetworkProbe.Outcome) {
        resolutionTask?.cancel()
        resolutionTask = nil
        browser?.cancel()
        browser = nil
        status = outcome
    }

    /// Maps a browse error to its probe event.
    ///
    /// Only `kDNSServiceErr_PolicyDenied` may read as a denial. The nearby
    /// `kDNSServiceErr_NoAuth` (-65555) means the build is missing its Bonjour
    /// service declaration or usage description, which is a mistake to fix
    /// rather than a user answer to report.
    static func classify(_ error: NWError) -> LocalNetworkProbe.BrowserEvent {
        guard case .dns(let code) = error else { return .other }
        return code == policyDeniedCode ? .policyDenied : .other
    }
}
