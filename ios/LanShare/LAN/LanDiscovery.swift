import Foundation
import Network

public struct LanDiscoveredService: Sendable {
    public let endpoint: NWEndpoint
    public let txt: [String: String]

    public init(endpoint: NWEndpoint, txt: [String: String]) {
        self.endpoint = endpoint
        self.txt = txt
    }
}

private final class OnceFlag: @unchecked Sendable {
    private let lock = UnfairLock()
    private var done = false

    func take() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if done { return false }
        done = true
        return true
    }
}

/// Bonjour browse for `_lanshare._tcp`. The TXT record carries the salted
/// room identity, so the caller can match a code without advertising it.
public final class LanBrowser: @unchecked Sendable {
    public init() {}

    public func browse(
        timeout: TimeInterval,
        match: @escaping @Sendable (LanDiscoveredService) -> Bool,
    ) async -> LanDiscoveredService? {
        await withCheckedContinuation { continuation in
            let queue = DispatchQueue(label: "com.lanshare.lan.browser")
            let parameters = NWParameters.tcp
            let browser = NWBrowser(
                for: .bonjourWithTXTRecord(type: LanPairing.serviceType, domain: nil),
                using: parameters,
            )
            let once = OnceFlag()
            @Sendable func finish(_ value: LanDiscoveredService?) {
                guard once.take() else { return }
                browser.cancel()
                continuation.resume(returning: value)
            }
            browser.browseResultsChangedHandler = { results, _ in
                for result in results {
                    guard case let .bonjour(txtRecord) = result.metadata else { continue }
                    let service = LanDiscoveredService(
                        endpoint: result.endpoint,
                        txt: txtRecord.dictionary,
                    )
                    if match(service) {
                        finish(service)
                        return
                    }
                }
            }
            browser.stateUpdateHandler = { state in
                if case .failed = state {
                    finish(nil)
                }
            }
            browser.start(queue: queue)
            queue.asyncAfter(deadline: .now() + timeout) {
                finish(nil)
            }
        }
    }
}
