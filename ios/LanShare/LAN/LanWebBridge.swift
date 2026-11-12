import Foundation
import Network

/// The browser guest's half of local pairing.
///
/// A tiny HTTP/1.1 server serves the built web UI from the app bundle's
/// `web/` directory and a handful of JSON endpoints. SDP/ICE use long polling
/// rather than a WebSocket: signaling is a few frames per session and the data
/// path is the WebRTC DataChannel, so this avoids a hand-rolled RFC 6455 stack.
/// The browser never derives the room key: the host mints an opaque token after
/// checking the 8-digit code, which also keeps the page working on plain HTTP
/// where `crypto.subtle` is unavailable.
final class LanWebBridge: LanBridgeTransport, @unchecked Sendable {
    private static let longPollSeconds: TimeInterval = 20
    private static let maxBodyBytes = 64 * 1024
    private static let maxEvents = 256
    private static let maxFailures = 10

    private let session: LanRoomSession
    private let log: @Sendable (String) -> Void
    private let condition = NSCondition()
    private let queue = DispatchQueue(label: "com.lanshare.lan.web", attributes: .concurrent)
    private var events: [SignalingEnvelope] = []
    private var guestToken = ""
    private var failures = 0
    private var stopped = true
    private var listener: NWListener?

    init(
        session: LanRoomSession,
        log: @escaping @Sendable (String) -> Void,
    ) {
        self.session = session
        self.log = log
    }

    /// Binds the bridge and returns its listening port, or nil when it fails.
    func start() -> Int? {
        stop()
        let parameters = NWParameters.tcp
        parameters.allowLocalEndpointReuse = true
        guard let listener = try? NWListener(using: parameters, on: .any) else {
            log("lan: browser bridge failed to start")
            return nil
        }
        let ready = DispatchSemaphore(value: 0)
        let portBox = BridgeBox<Int?>(nil)
        listener.stateUpdateHandler = { state in
            switch state {
            case .ready:
                portBox.value = Int(listener.port?.rawValue ?? 0)
                ready.signal()
            case .failed:
                ready.signal()
            default:
                break
            }
        }
        listener.newConnectionHandler = { [weak self] connection in
            self?.handle(connection)
        }
        listener.start(queue: queue)
        _ = ready.wait(timeout: .now() + 3)
        guard let port = portBox.value, port > 0 else {
            listener.cancel()
            log("lan: browser bridge has no port")
            return nil
        }
        condition.lock()
        stopped = false
        condition.unlock()
        self.listener = listener
        log("lan: browser bridge listening on \(port)")
        return port
    }

    /// Returns the browser-facing address for the current room, IPv4 first.
    func browserAddress(port: Int) -> LanAddress? {
        let addresses = enumerateLanAddresses(port: UInt16(clamping: port))
        return addresses.first { address in
            !address.host.contains(":") && !address.host.hasPrefix("127.")
        } ?? addresses.first { address in
            address.host != "::1" && address.host != "::"
        }
    }

    func stop() {
        session.bridgeDetach(self)
        listener?.cancel()
        listener = nil
        condition.lock()
        stopped = true
        events.removeAll()
        guestToken = ""
        condition.broadcast()
        condition.unlock()
    }

    // MARK: LanBridgeTransport

    func send(_ envelope: SignalingEnvelope) {
        condition.lock()
        if events.count >= Self.maxEvents {
            events.removeFirst()
        }
        events.append(envelope)
        condition.broadcast()
        condition.unlock()
    }

    /// Detach the browser guest without stopping the HTTP server.
    func close() {
        condition.lock()
        events.removeAll()
        guestToken = ""
        condition.broadcast()
        condition.unlock()
    }

    // MARK: HTTP

    private struct HttpRequest {
        let method: String
        let path: String
        let body: Data
    }

    private func handle(_ connection: NWConnection) {
        connection.start(queue: queue)
        guard let request = readRequest(connection) else {
            connection.cancel()
            return
        }
        let path = request.path
        if path.hasPrefix("/__bridge__/") {
            handleBridge(
                action: String(path.dropFirst("/__bridge__/".count)).components(separatedBy: "?").first ?? "",
                request: request,
                connection: connection,
            )
            return
        }
        serveStatic(path: path, method: request.method, connection: connection)
    }

    private func readRequest(_ connection: NWConnection) -> HttpRequest? {
        var buffer = Data()
        var headerEnd: Range<Data.Index>?
        while headerEnd == nil, buffer.count < 16 * 1024 {
            guard let chunk = receive(connection) else { return nil }
            buffer.append(chunk)
            headerEnd = buffer.range(of: Data([13, 10, 13, 10]))
        }
        guard let headerEnd else { return nil }
        let headerData = buffer[..<headerEnd.lowerBound]
        let headerText = String(decoding: headerData, as: UTF8.self)
        let lines = headerText.components(separatedBy: "\r\n")
        guard let first = lines.first else { return nil }
        let parts = first.split(separator: " ")
        guard parts.count >= 2 else { return nil }
        let method = String(parts[0]).uppercased()
        let path = String(parts[1])
        let contentLength = lines.first { line in
            line.lowercased().hasPrefix("content-length:")
        }
        .flatMap { line in
            Int(line.split(separator: ":", maxSplits: 1).last?.trimmingCharacters(in: .whitespaces) ?? "")
        } ?? 0
        guard contentLength >= 0, contentLength <= Self.maxBodyBytes else { return nil }
        var body = Data(buffer[(headerEnd.upperBound)...])
        while body.count < contentLength {
            guard let chunk = receive(connection) else { break }
            body.append(chunk)
        }
        return HttpRequest(method: method, path: path, body: Data(body.prefix(contentLength)))
    }

    private func receive(_ connection: NWConnection) -> Data? {
        let semaphore = DispatchSemaphore(value: 0)
        let result = BridgeBox(Data())
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { data, _, _, _ in
            if let data {
                var current = result.value
                current.append(data)
                result.value = current
            }
            semaphore.signal()
        }
        _ = semaphore.wait(timeout: .now() + 20)
        return result.value.isEmpty ? nil : result.value
    }

    private func handleBridge(action: String, request: HttpRequest, connection: NWConnection) {
        switch action {
        case "info":
            guard request.method == "GET" || request.method == "HEAD" else {
                return respondJSON(connection, status: 405, object: ["code": "method_not_allowed"])
            }
            sendInfo(connection)
        case "join":
            guard request.method == "POST" else {
                return respondJSON(connection, status: 405, object: ["code": "method_not_allowed"])
            }
            join(request: request, connection: connection)
        case "events":
            guard request.method == "POST" else {
                return respondJSON(connection, status: 405, object: ["code": "method_not_allowed"])
            }
            eventsFor(request: request, connection: connection)
        case "signal":
            guard request.method == "POST" else {
                return respondJSON(connection, status: 405, object: ["code": "method_not_allowed"])
            }
            signal(request: request, connection: connection)
        case "status":
            guard request.method == "POST" else {
                return respondJSON(connection, status: 405, object: ["code": "method_not_allowed"])
            }
            status(request: request, connection: connection)
        case "heartbeat":
            guard request.method == "POST" else {
                return respondJSON(connection, status: 405, object: ["code": "method_not_allowed"])
            }
            heartbeat(request: request, connection: connection)
        case "close":
            guard request.method == "POST" else {
                return respondJSON(connection, status: 405, object: ["code": "method_not_allowed"])
            }
            closeGuest(request: request, connection: connection)
        default:
            respondJSON(connection, status: 404, object: ["code": "not_found"])
        }
    }

    private func sendInfo(_ connection: NWConnection) {
        guard let info = session.bridgeInfo() else {
            return respondJSON(connection, status: 404, object: ["code": "room_unavailable"])
        }
        respondJSON(connection, status: 200, object: [
            "mode": "lan-bridge",
            "code": info.code,
            "hostDeviceId": info.hostDeviceId,
            "hostDeviceLabel": info.hostDeviceLabel,
            "roomId": info.roomId,
            "pairingExpiresAt": info.pairingExpiresAt,
            "sessionExpiresAt": info.sessionExpiresAt,
            "limits": limitsObject(info.limits),
        ])
    }

    private func join(request: HttpRequest, connection: NWConnection) {
        guard let body = jsonBody(request.body),
              let code = body["code"] as? String, !code.isEmpty,
              let deviceId = body["deviceId"] as? String, !deviceId.isEmpty
        else {
            return respondJSON(connection, status: 400, object: [
                "code": "invalid_request",
                "message": "Missing code or device identity.",
            ])
        }
        let deviceLabel = body["deviceLabel"] as? String ?? ""
        let token = body["token"] as? String
        guard let welcome = session.bridgeJoin(
            code: code,
            deviceId: deviceId,
            deviceLabel: deviceLabel,
            resumeToken: token,
        ) else {
            condition.lock()
            failures += 1
            let count = failures
            condition.unlock()
            if count >= Self.maxFailures {
                return respondJSON(connection, status: 429, object: [
                    "code": "too_many_attempts",
                    "message": "Too many failed pairing attempts.",
                ])
            }
            Thread.sleep(forTimeInterval: 2)
            return respondJSON(connection, status: 403, object: [
                "code": "invalid_code",
                "message": "The code is wrong or expired.",
            ])
        }
        condition.lock()
        guestToken = welcome.token
        failures = 0
        events.removeAll()
        condition.unlock()
        // Attach after the welcome so the host's offer (emitted from the
        // peer_joined callback) is flushed into the bridge event queue.
        session.bridgeAttach(self)
        respondJSON(connection, status: 200, object: [
            "token": welcome.token,
            "sessionToken": welcome.token,
            "roomId": welcome.roomId,
            "deviceId": deviceId,
            "role": "peer",
            "peerCount": 2,
            "pairingExpiresAt": welcome.pairingExpiresAt,
            "sessionExpiresAt": welcome.sessionExpiresAt,
            "limits": limitsObject(welcome.limits),
            "host": [
                "deviceId": welcome.hostDeviceId,
                "deviceLabel": welcome.hostDeviceLabel,
            ],
        ])
    }

    private func eventsFor(request: HttpRequest, connection: NWConnection) {
        guard let body = jsonBody(request.body),
              let token = body["token"] as? String,
              validToken(token)
        else {
            return respondJSON(connection, status: 403, object: [
                "code": "invalid_session",
                "message": "Invalid bridge token.",
            ])
        }
        let deadline = Date().addingTimeInterval(Self.longPollSeconds)
        condition.lock()
        while events.isEmpty, !stopped, Date() < deadline {
            condition.wait(until: deadline)
        }
        let drained = events
        events.removeAll()
        condition.unlock()
        let messages = drained.compactMap(envelopeObject)
        respondJSON(connection, status: 200, object: ["messages": messages])
    }

    private func signal(request: HttpRequest, connection: NWConnection) {
        guard let body = jsonBody(request.body),
              let token = body["token"] as? String,
              validToken(token)
        else {
            return respondJSON(connection, status: 403, object: [
                "code": "invalid_session",
                "message": "Invalid bridge token.",
            ])
        }
        guard let message = body["message"] as? [String: Any],
              let envelope = jsonEnvelope(message)
        else {
            return respondJSON(connection, status: 400, object: [
                "code": "invalid_request",
                "message": "Invalid signal message.",
            ])
        }
        session.bridgeIncoming(envelope)
        respondEmpty(connection, status: 202)
    }

    private func status(request: HttpRequest, connection: NWConnection) {
        guard let body = jsonBody(request.body),
              let token = body["token"] as? String,
              validToken(token),
              let info = session.bridgeInfo()
        else {
            return respondJSON(connection, status: 403, object: [
                "code": "invalid_session",
                "message": "Invalid bridge token.",
            ])
        }
        respondJSON(connection, status: 200, object: [
            "roomId": info.roomId,
            "peerCount": 2,
            "paired": true,
            "pairingExpiresAt": info.pairingExpiresAt,
            "sessionExpiresAt": info.sessionExpiresAt,
            "peers": [[
                "deviceId": info.hostDeviceId,
                "deviceLabel": info.hostDeviceLabel,
                "deviceType": "mobile",
            ]],
        ])
    }

    private func heartbeat(request: HttpRequest, connection: NWConnection) {
        guard let body = jsonBody(request.body),
              let token = body["token"] as? String,
              validToken(token)
        else {
            return respondJSON(connection, status: 403, object: [
                "code": "invalid_session",
                "message": "Invalid bridge token.",
            ])
        }
        let info = session.bridgeInfo()
        respondJSON(connection, status: 200, object: [
            "ok": true,
            "lastSeenAt": info?.pairingExpiresAt ?? "",
            "sessionExpiresAt": info?.sessionExpiresAt ?? "",
        ])
    }

    private func closeGuest(request: HttpRequest, connection: NWConnection) {
        guard let body = jsonBody(request.body),
              let token = body["token"] as? String,
              validToken(token)
        else {
            return respondJSON(connection, status: 403, object: [
                "code": "invalid_session",
                "message": "Invalid bridge token.",
            ])
        }
        session.bridgeClose()
        respondEmpty(connection, status: 204)
    }

    // MARK: Static assets

    private func serveStatic(path: String, method: String, connection: NWConnection) {
        guard method == "GET" || method == "HEAD" else {
            return respondEmpty(connection, status: 405)
        }
        let clean = path.components(separatedBy: "?").first?.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let relative = (clean?.isEmpty == false ? clean! : "index.html")
        guard !relative.split(separator: "/").contains("..") else {
            return respondEmpty(connection, status: 404)
        }
        if let file = assetData(relative) {
            return respond(connection, status: 200, contentType: contentType(relative), body: file)
        }
        let isAsset = relative.hasPrefix("assets/") ||
            relative.hasSuffix(".webmanifest") ||
            relative.hasSuffix(".png")
        if isAsset {
            return respondEmpty(connection, status: 404)
        }
        // The React app owns locale/page routes; serve the shell for them.
        if let shell = assetData("index.html") {
            respond(connection, status: 200, contentType: "text/html; charset=utf-8", body: shell)
        } else {
            respondEmpty(connection, status: 404)
        }
    }

    private func assetData(_ relative: String) -> Data? {
        let full = "web/\(relative)" as NSString
        let directory = full.deletingLastPathComponent
        let file = full.lastPathComponent as NSString
        let name = file.deletingPathExtension
        let ext = file.pathExtension
        guard let url = Bundle.main.url(
            forResource: name,
            withExtension: ext.isEmpty ? nil : ext,
            subdirectory: directory,
        ) else { return nil }
        return try? Data(contentsOf: url)
    }

    private func contentType(_ path: String) -> String {
        switch path.components(separatedBy: ".").last?.lowercased() {
        case "html": return "text/html; charset=utf-8"
        case "js", "mjs": return "text/javascript; charset=utf-8"
        case "css": return "text/css; charset=utf-8"
        case "json": return "application/json; charset=utf-8"
        case "webmanifest": return "application/manifest+json"
        case "svg": return "image/svg+xml"
        case "png": return "image/png"
        case "ico": return "image/x-icon"
        case "woff2": return "font/woff2"
        default: return "application/octet-stream"
        }
    }

    // MARK: Responses

    private func respondJSON(_ connection: NWConnection, status: Int, object: [String: Any]) {
        guard let body = try? JSONSerialization.data(withJSONObject: object) else {
            return respondEmpty(connection, status: 500)
        }
        respond(connection, status: status, contentType: "application/json; charset=utf-8", body: body)
    }

    private func respondEmpty(_ connection: NWConnection, status: Int) {
        respond(connection, status: status, contentType: "text/plain", body: Data())
    }

    private func respond(_ connection: NWConnection, status: Int, contentType: String, body: Data) {
        let header = "HTTP/1.1 \(statusLabel(status))\r\n" +
            "Content-Type: \(contentType)\r\n" +
            "Content-Length: \(body.count)\r\n" +
            "Connection: close\r\n" +
            "Cache-Control: no-store\r\n" +
            "\r\n"
        var payload = Data(header.utf8)
        payload.append(body)
        connection.send(content: payload, completion: .contentProcessed { _ in
            connection.cancel()
        })
    }

    private func statusLabel(_ status: Int) -> String {
        switch status {
        case 200: return "200 OK"
        case 202: return "202 Accepted"
        case 204: return "204 No Content"
        case 400: return "400 Bad Request"
        case 403: return "403 Forbidden"
        case 404: return "404 Not Found"
        case 405: return "405 Method Not Allowed"
        case 429: return "429 Too Many Requests"
        case 500: return "500 Internal Server Error"
        default: return "\(status) Error"
        }
    }

    // MARK: JSON

    private func jsonBody(_ data: Data) -> [String: Any]? {
        (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    private func jsonEnvelope(_ object: [String: Any]) -> SignalingEnvelope? {
        guard let data = try? JSONSerialization.data(withJSONObject: object) else { return nil }
        return try? JSONDecoder().decode(SignalingEnvelope.self, from: data)
    }

    private func envelopeObject(_ envelope: SignalingEnvelope) -> [String: Any]? {
        guard let text = try? SignalingJson.encode(envelope),
              let data = text.data(using: .utf8)
        else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    private func validToken(_ raw: String) -> Bool {
        condition.lock()
        let expected = guestToken
        condition.unlock()
        return !raw.isEmpty && !expected.isEmpty && LanPairing.constantTimeEquals(expected, raw)
    }

    private func limitsObject(_ limits: TransferLimits) -> [String: Any] {
        [
            "maxFileSize": limits.maxFileSize,
            "maxActiveTransfers": limits.maxActiveTransfers,
            "chunkSize": limits.chunkSize,
        ]
    }
}

/// A Sendable box for values that Network.framework's state handlers write on
/// another queue while the caller waits on a semaphore.
private final class BridgeBox<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var stored: T

    init(_ value: T) {
        stored = value
    }

    var value: T {
        get {
            lock.lock()
            defer { lock.unlock() }
            return stored
        }
        set {
            lock.lock()
            stored = newValue
            lock.unlock()
        }
    }
}
