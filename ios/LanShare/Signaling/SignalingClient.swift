import Foundation

/// WebSocket signaling client against the private signaling endpoint.
/// The first frame sent after opening is the `auth` envelope; the server
/// rejects any other first frame.
public final class SignalingClient: NSObject, @unchecked Sendable {
    private let origin: String
    private let onEvent: @Sendable (SignalingEnvelope) -> Void
    private let onDisconnect: @Sendable (String) -> Void

    private var session: URLSession?
    private var task: URLSessionWebSocketTask?
    private let stateLock = NSLock()
    private var closedByClient = false
    private var disconnectNotified = false

    public init(
        origin: String,
        onEvent: @escaping @Sendable (SignalingEnvelope) -> Void,
        onDisconnect: @escaping @Sendable (String) -> Void,
    ) {
        self.origin = origin
        self.onEvent = onEvent
        self.onDisconnect = onDisconnect
    }

    public func connect(auth: SignalingEnvelope) {
        guard let url = URL(string: RoomApiClient.signalingURL(for: origin)) else {
            notifyDisconnect("invalid signaling url")
            return
        }
        stateLock.lock()
        closedByClient = false
        disconnectNotified = false
        stateLock.unlock()
        let session = URLSession(configuration: .default, delegate: nil, delegateQueue: nil)
        self.session = session
        let task = session.webSocketTask(with: url)
        task.delegate = self
        self.task = task
        // Stage auth before resume: the didOpen callback fires on a session
        // queue and must never beat the payload it needs.
        pendingAuth = auth
        task.resume()
    }

    public func send(_ envelope: SignalingEnvelope) {
        guard let text = try? SignalingJson.encode(envelope) else { return }
        task?.send(.string(text)) { [weak self] error in
            if error != nil {
                self?.notifyDisconnect("send failed")
            }
        }
    }

    public func close() {
        stateLock.lock()
        closedByClient = true
        stateLock.unlock()
        task?.cancel(with: .normalClosure, reason: "client_close".data(using: .utf8))
        task = nil
        session?.invalidateAndCancel()
        session = nil
    }

    private var pendingAuth: SignalingEnvelope?

    private func notifyDisconnect(_ reason: String) {
        stateLock.lock()
        let clientClosed = closedByClient
        let alreadyNotified = disconnectNotified
        disconnectNotified = true
        stateLock.unlock()
        guard !clientClosed, !alreadyNotified else { return }
        onDisconnect(reason)
    }

    private func handleIncoming(_ text: String) {
        guard let envelope = try? SignalingJson.decode(text) else {
            onEvent(SignalingEnvelope(type: "__invalid__", error: "invalid message"))
            return
        }
        onEvent(envelope)
    }

    private func scheduleReceive() {
        task?.receive { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let message):
                switch message {
                case .string(let text):
                    self.handleIncoming(text)
                case .data(let data):
                    if let text = String(data: data, encoding: .utf8) {
                        self.handleIncoming(text)
                    }
                @unknown default:
                    break
                }
                self.scheduleReceive()
            case .failure(let error):
                self.notifyDisconnect(error.localizedDescription)
            }
        }
    }
}

extension SignalingClient: URLSessionWebSocketDelegate {
    public func urlSession(
        _ session: URLSession,
        webSocketTask: URLSessionWebSocketTask,
        didOpenWithProtocol protocol: String?,
    ) {
        guard let auth = pendingAuth else { return }
        pendingAuth = nil
        guard let text = try? SignalingJson.encode(auth) else { return }
        webSocketTask.send(.string(text)) { [weak self] error in
            if error != nil {
                self?.notifyDisconnect("auth send failed")
            }
        }
        scheduleReceive()
    }

    public func urlSession(
        _ session: URLSession,
        webSocketTask: URLSessionWebSocketTask,
        didCloseWith closeCode: URLSessionWebSocketTask.CloseCode,
        reason: Data?,
    ) {
        notifyDisconnect("closed \(closeCode.rawValue)")
    }

    public func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let error else { return }
        notifyDisconnect(error.localizedDescription)
    }
}
