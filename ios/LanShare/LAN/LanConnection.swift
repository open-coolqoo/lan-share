import Foundation
import Network

enum LanConnectionError: Error, LocalizedError, Equatable {
    case readyFailed(String)
    case closed(String)
    case invalidFrame
    case timeout

    var errorDescription: String? {
        switch self {
        case .readyFailed(let reason): return "LAN connection failed: \(reason)"
        case .closed(let reason): return "LAN connection closed: \(reason)"
        case .invalidFrame: return "Invalid LAN frame"
        case .timeout: return "LAN connection timed out"
        }
    }
}

/// A length-prefixed JSON connection over `NWConnection`. Sends are serialized
/// by the owning session; `receive()` is called sequentially (handshake first,
/// then the read loop) from one task.
final class LanConnection: @unchecked Sendable {
    private let connection: NWConnection
    private let queue = DispatchQueue(label: "com.lanshare.lan.connection")
    private let lock = UnfairLock()
    private let receiveLock = UnfairLock()
    private var buffer = Data()
    private var readyContinuation: CheckedContinuation<Void, Error>?
    private var streamContinuation: AsyncThrowingStream<LanMessage, Error>.Continuation?
    private var iterator: AsyncThrowingStream<LanMessage, Error>.Iterator?
    private var closeHandler: (@Sendable (String) -> Void)?
    private var closed = false
    private var ready = false
    private var started = false
    private var idleSeconds: TimeInterval = 30
    private var idleWorkItem: DispatchWorkItem?

    init(connection: NWConnection) {
        self.connection = connection
    }

    /// After the handshake the peer pings every 10 s; a longer silence means
    /// the socket is gone even if the kernel has not noticed yet.
    func setIdleTimeout(_ seconds: TimeInterval) {
        lock.lock()
        idleSeconds = seconds
        lock.unlock()
        scheduleIdleTimeout()
    }

    /// The next frame, or nil when the stream ended cleanly. Only one task may
    /// wait here at a time.
    func receive() async throws -> LanMessage? {
        receiveLock.lock()
        var current = iterator
        receiveLock.unlock()
        guard var currentIterator = current else { return nil }
        let message = try await currentIterator.next()
        receiveLock.lock()
        if !closed {
            current = currentIterator
            iterator = current
        }
        receiveLock.unlock()
        return message
    }

    private func installStreamIfNeeded() {
        receiveLock.lock()
        if iterator != nil {
            receiveLock.unlock()
            return
        }
        let stream = AsyncThrowingStream<LanMessage, Error> { continuation in
            lock.lock()
            if closed {
                lock.unlock()
                continuation.finish(throwing: LanConnectionError.closed("closed"))
                return
            }
            streamContinuation = continuation
            lock.unlock()
            continuation.onTermination = { [weak self] _ in
                self?.cancel()
            }
        }
        iterator = stream.makeAsyncIterator()
        receiveLock.unlock()
    }

    func open(timeout: TimeInterval = 6) async throws {
        installStreamIfNeeded()
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            lock.lock()
            if ready {
                lock.unlock()
                continuation.resume()
                return
            }
            if closed {
                lock.unlock()
                continuation.resume(throwing: LanConnectionError.closed("closed"))
                return
            }
            readyContinuation = continuation
            let shouldStart = !started
            started = true
            lock.unlock()
            if shouldStart {
                connection.stateUpdateHandler = { [weak self] state in
                    guard let self else { return }
                    switch state {
                    case .ready:
                        self.markReady()
                    case .failed(let error):
                        self.finish("failed: \(error.localizedDescription)")
                    case .cancelled:
                        self.finish("cancelled")
                    default:
                        // `.waiting` is often a transient route/permission
                        // state; the timer below owns giving up.
                        break
                    }
                }
                connection.start(queue: queue)
            }
            queue.asyncAfter(deadline: .now() + timeout) { [weak self] in
                guard let self else { return }
                self.lock.lock()
                let isReady = self.ready
                self.lock.unlock()
                if !isReady { self.finish("timeout") }
            }
        }
    }

    func send(_ message: LanMessage) async throws {
        let frame = LanFraming.frame(try LanCodec.encode(message))
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            connection.send(content: frame, completion: .contentProcessed { error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume()
                }
            })
        }
    }

    func onClose(_ handler: @escaping @Sendable (String) -> Void) {
        lock.lock()
        closeHandler = handler
        lock.unlock()
    }

    func cancel() {
        finish("cancelled")
    }

    private func markReady() {
        lock.lock()
        ready = true
        let continuation = readyContinuation
        readyContinuation = nil
        lock.unlock()
        continuation?.resume()
        scheduleIdleTimeout()
        receiveLoop()
    }

    private func scheduleIdleTimeout() {
        lock.lock()
        idleWorkItem?.cancel()
        let seconds = idleSeconds
        let item = DispatchWorkItem { [weak self] in
            self?.finish("idle timeout")
        }
        idleWorkItem = item
        lock.unlock()
        queue.asyncAfter(deadline: .now() + seconds, execute: item)
    }

    private func receiveLoop() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) {
            [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let data, !data.isEmpty {
                do {
                    let messages = try self.append(data)
                    let continuation = self.currentStreamContinuation()
                    for message in messages {
                        continuation?.yield(message)
                    }
                    self.scheduleIdleTimeout()
                } catch {
                    self.finish("invalid frame")
                    return
                }
            }
            if let error {
                self.finish("receive: \(error.localizedDescription)")
                return
            }
            if isComplete {
                self.finish("closed")
                return
            }
            self.receiveLoop()
        }
    }

    private func append(_ data: Data) throws -> [LanMessage] {
        lock.lock()
        buffer.append(data)
        let payloads: [Data]
        do {
            payloads = try LanFraming.payloads(from: &buffer)
        } catch {
            lock.unlock()
            throw error
        }
        lock.unlock()
        return try payloads.map { try LanCodec.decode($0) }
    }

    private func currentStreamContinuation() -> AsyncThrowingStream<LanMessage, Error>.Continuation? {
        lock.lock()
        defer { lock.unlock() }
        return streamContinuation
    }

    private func finish(_ reason: String) {
        lock.lock()
        if closed {
            lock.unlock()
            return
        }
        closed = true
        idleWorkItem?.cancel()
        idleWorkItem = nil
        let readyContinuation = self.readyContinuation
        let streamContinuation = self.streamContinuation
        let closeHandler = self.closeHandler
        self.readyContinuation = nil
        self.streamContinuation = nil
        self.closeHandler = nil
        lock.unlock()
        receiveLock.lock()
        iterator = nil
        receiveLock.unlock()
        readyContinuation?.resume(throwing: LanConnectionError.readyFailed(reason))
        streamContinuation?.finish(throwing: LanConnectionError.closed(reason))
        closeHandler?(reason)
        connection.cancel()
    }
}
