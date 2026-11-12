package com.lanshare.lan

import android.content.res.AssetManager
import com.lanshare.SignalingEnvelope
import com.lanshare.TransferLimits
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The browser guest's half of local pairing.
 *
 * A tiny HTTP/1.1 server serves the built web UI from `assets/web` and a
 * handful of JSON endpoints. SDP/ICE use long polling rather than a WebSocket:
 * signaling is a few frames per session and the data path is the WebRTC
 * DataChannel, so this avoids a hand-rolled RFC 6455 stack on two platforms.
 * The browser never derives the room key: the host mints an opaque token after
 * checking the 8-digit code, which also keeps the page working on plain HTTP
 * where `crypto.subtle` is unavailable.
 */
internal class LanWebBridge(
    private val session: LanRoomSession,
    private val assets: AssetManager,
    private val log: (String) -> Unit,
    private val addressProvider: (Int) -> List<LanAddress> = ::enumerateLanAddresses,
) : LanBridgeTransport {
    private companion object {
        const val longPollMs = 20_000L
        const val maxBodyBytes = 64 * 1024
        const val maxEvents = 256
        const val maxFailures = 10
        const val requestTimeoutMs = 20_000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pool = Executors.newFixedThreadPool(16) { runnable ->
        Thread(runnable, "lan-web-bridge").apply { isDaemon = true }
    }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Object()
    private val events = ArrayDeque<SignalingEnvelope>()
    private var server: ServerSocket? = null
    private var acceptJob: Job? = null
    private var guestToken = ""
    private var failures = 0
    @Volatile private var stopped = false

    /** Binds the bridge and returns its listening port, or null when it fails. */
    fun start(): Int? {
        stop()
        return try {
            val socket = ServerSocket(0)
            socket.reuseAddress = true
            stopped = false
            server = socket
            acceptJob = scope.launch { acceptLoop(socket) }
            log("lan: browser bridge listening on ${socket.localPort}")
            socket.localPort
        } catch (error: Exception) {
            log("lan: browser bridge failed ${error.message}")
            null
        }
    }

    /** Returns the browser-facing address for the current room, IPv4 first. */
    fun browserAddress(port: Int): LanAddress? {
        val addresses = addressProvider(port).filter { it.host.isNotBlank() }
        return addresses.firstOrNull { !it.host.contains(':') && !it.host.startsWith("127.") }
            ?: addresses.firstOrNull { it.host != "::1" && it.host != "::" }
    }

    fun stop() {
        session.bridgeDetach(this)
        stopped = true
        acceptJob?.cancel()
        acceptJob = null
        runCatching { server?.close() }
        server = null
        synchronized(lock) {
            events.clear()
            guestToken = ""
            lock.notifyAll()
        }
    }

    // MARK: LanBridgeTransport

    override fun send(envelope: SignalingEnvelope) {
        synchronized(lock) {
            if (events.size >= maxEvents) events.removeFirst()
            events.addLast(envelope)
            lock.notifyAll()
        }
    }

    /** Detach the browser guest without stopping the HTTP server. */
    override fun close() {
        synchronized(lock) {
            events.clear()
            guestToken = ""
            lock.notifyAll()
        }
    }

    // MARK: HTTP

    private suspend fun acceptLoop(server: ServerSocket) {
        while (scope.isActive && !stopped && !server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: Exception) {
                break
            }
            pool.execute {
                runCatching { handle(client) }.onFailure { log("lan: bridge request failed ${it.message}") }
                runCatching { client.close() }
            }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = requestTimeoutMs
        val request = readRequest(client.getInputStream()) ?: return
        val path = request.path
        if (path.startsWith("/__bridge__/")) {
            handleBridge(path.removePrefix("/__bridge__/"), request, client.getOutputStream())
            return
        }
        serveStatic(path, request.method, client.getOutputStream())
    }

    private data class Request(
        val method: String,
        val path: String,
        val body: ByteArray,
    ) { val query: String get() = path.substringAfter('?', "") }

    private fun readRequest(input: InputStream): Request? {
        val header = ByteArrayOutputStream()
        var total = 0
        var previous = 0
        while (total < 16 * 1024) {
            val next = input.read()
            if (next < 0) return null
            header.write(next)
            total += 1
            if (previous == '\r'.code && next == '\n'.code) {
                val bytes = header.toByteArray()
                if (bytes.size >= 4 &&
                    bytes[bytes.size - 4] == '\r'.code.toByte() &&
                    bytes[bytes.size - 3] == '\n'.code.toByte() &&
                    bytes[bytes.size - 2] == '\r'.code.toByte() &&
                    bytes[bytes.size - 1] == '\n'.code.toByte()
                ) {
                    break
                }
            }
            previous = next
        }
        val text = header.toString(StandardCharsets.UTF_8.name())
        val lines = text.split("\r\n")
        val method = lines.firstOrNull()?.substringBefore(' ')?.uppercase() ?: return null
        val target = lines.firstOrNull()?.substringAfter(' ', "")?.substringBefore(' ') ?: return null
        if (target.isBlank()) return null
        val contentLength = lines
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.toIntOrNull()
            ?: 0
        if (contentLength < 0 || contentLength > maxBodyBytes) return null
        val body = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val count = input.read(body, read, contentLength - read)
            if (count <= 0) break
            read += count
        }
        return Request(method, target, body.copyOf(read))
    }

    private fun handleBridge(action: String, request: Request, out: OutputStream) {
        when (action.substringBefore('?')) {
            "info" -> if (request.method == "GET" || request.method == "HEAD") sendInfo(request, out)
            "join" -> if (request.method == "POST") join(request, out) else respondError(out, 405, "method_not_allowed")
            "events" -> if (request.method == "POST") eventsFor(request, out) else respondError(out, 405, "method_not_allowed")
            "signal" -> if (request.method == "POST") signal(request, out) else respondError(out, 405, "method_not_allowed")
            "status" -> if (request.method == "POST") status(request, out) else respondError(out, 405, "method_not_allowed")
            "heartbeat" -> if (request.method == "POST") heartbeat(request, out) else respondError(out, 405, "method_not_allowed")
            "close" -> if (request.method == "POST") closeGuest(request, out) else respondError(out, 405, "method_not_allowed")
            else -> respondError(out, 404, "not_found")
        }
    }

    private fun sendInfo(request: Request, out: OutputStream) {
        val info = session.bridgeInfo()
        if (info == null) {
            respondJson(out, 404, buildJsonObject { put("code", "room_unavailable") })
            return
        }
        val body = buildJsonObject {
            put("mode", "lan-bridge")
            put("code", info.code)
            put("hostDeviceId", info.hostDeviceId)
            put("hostDeviceLabel", info.hostDeviceLabel)
            put("roomId", info.roomId)
            put("pairingExpiresAt", info.pairingExpiresAt)
            put("sessionExpiresAt", info.sessionExpiresAt)
            put("limits", limitsJson(info.limits))
        }
        respondJson(out, 200, body, request.method == "HEAD")
    }

    private fun join(request: Request, out: OutputStream) {
        val body = parseBody(request.body) ?: return respondJson(out, 400, errorJson("invalid_request", "Invalid JSON body."))
        val code = body.string("code")?.trim().orEmpty()
        val deviceId = body.string("deviceId")?.trim().orEmpty()
        val deviceLabel = body.string("deviceLabel")?.trim().orEmpty()
        val resumeToken = body.string("token")?.trim()
        if (code.isEmpty() || deviceId.isEmpty()) {
            return respondJson(out, 400, errorJson("invalid_request", "Missing code or device identity."))
        }
        val welcome = session.bridgeJoin(code, deviceId, deviceLabel, resumeToken)
        if (welcome == null) {
            val failuresNow = synchronized(lock) {
                failures += 1
                failures
            }
            if (failuresNow >= maxFailures) {
                return respondJson(out, 429, errorJson("too_many_attempts", "Too many failed pairing attempts."))
            }
            Thread.sleep(2_000)
            return respondJson(out, 403, errorJson("invalid_code", "The code is wrong or expired."))
        }
        synchronized(lock) {
            guestToken = welcome.token
            failures = 0
            events.clear()
        }
        // Attach after the welcome so the host's offer (emitted from the
        // peer_joined callback) is flushed into the bridge event queue.
        session.bridgeAttach(this)
        val response = buildJsonObject {
            put("token", welcome.token)
            put("sessionToken", welcome.token)
            put("roomId", welcome.roomId)
            put("deviceId", deviceId)
            put("role", "peer")
            put("peerCount", 2)
            put("pairingExpiresAt", welcome.pairingExpiresAt)
            put("sessionExpiresAt", welcome.sessionExpiresAt)
            put("limits", limitsJson(welcome.limits))
            put("host", buildJsonObject {
                put("deviceId", welcome.hostDeviceId)
                put("deviceLabel", welcome.hostDeviceLabel)
            })
        }
        respondJson(out, 200, response)
    }

    private fun eventsFor(request: Request, out: OutputStream) {
        val body = parseBody(request.body) ?: return respondJson(out, 400, errorJson("invalid_request", "Invalid JSON body."))
        if (!validToken(body.string("token"))) {
            return respondJson(out, 403, errorJson("invalid_session", "Invalid bridge token."))
        }
        val deadline = System.currentTimeMillis() + longPollMs
        val messages = synchronized(lock) {
            while (events.isEmpty() && !stopped) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                runCatching { lock.wait(remaining) }
            }
            val drained = ArrayList<JsonElement>(events.size)
            while (events.isNotEmpty()) {
                drained.add(json.encodeToJsonElement(SignalingEnvelope.serializer(), events.removeFirst()))
            }
            drained
        }
        respondJson(out, 200, buildJsonObject { put("messages", kotlinx.serialization.json.JsonArray(messages)) })
    }

    private fun signal(request: Request, out: OutputStream) {
        val body = parseBody(request.body) ?: return respondJson(out, 400, errorJson("invalid_request", "Invalid JSON body."))
        if (!validToken(body.string("token"))) {
            return respondJson(out, 403, errorJson("invalid_session", "Invalid bridge token."))
        }
        val message = body["message"] as? JsonObject
        if (message == null) {
            return respondJson(out, 400, errorJson("invalid_request", "Missing signal message."))
        }
        val envelope = runCatching {
            json.decodeFromJsonElement(SignalingEnvelope.serializer(), message)
        }.getOrNull() ?: return respondJson(out, 400, errorJson("invalid_request", "Invalid signal message."))
        session.bridgeIncoming(envelope)
        respondEmpty(out, 202)
    }

    private fun status(request: Request, out: OutputStream) {
        val body = parseBody(request.body) ?: return respondJson(out, 400, errorJson("invalid_request", "Invalid JSON body."))
        if (!validToken(body.string("token"))) {
            return respondJson(out, 403, errorJson("invalid_session", "Invalid bridge token."))
        }
        val info = session.bridgeInfo()
        if (info == null) return respondJson(out, 404, errorJson("room_unavailable", "Room is unavailable."))
        val response = buildJsonObject {
            put("roomId", info.roomId)
            put("peerCount", 2)
            put("paired", true)
            put("pairingExpiresAt", info.pairingExpiresAt)
            put("sessionExpiresAt", info.sessionExpiresAt)
            put("peers", kotlinx.serialization.json.JsonArray(listOf(
                buildJsonObject {
                    put("deviceId", info.hostDeviceId)
                    put("deviceLabel", info.hostDeviceLabel)
                    put("deviceType", "mobile")
                },
            )))
        }
        respondJson(out, 200, response)
    }

    private fun heartbeat(request: Request, out: OutputStream) {
        val body = parseBody(request.body) ?: return respondJson(out, 400, errorJson("invalid_request", "Invalid JSON body."))
        if (!validToken(body.string("token"))) {
            return respondJson(out, 403, errorJson("invalid_session", "Invalid bridge token."))
        }
        val info = session.bridgeInfo()
        respondJson(out, 200, buildJsonObject {
            put("ok", true)
            put("lastSeenAt", info?.pairingExpiresAt ?: "")
            put("sessionExpiresAt", info?.sessionExpiresAt)
        })
    }

    private fun closeGuest(request: Request, out: OutputStream) {
        val body = parseBody(request.body) ?: return respondJson(out, 400, errorJson("invalid_request", "Invalid JSON body."))
        if (!validToken(body.string("token"))) {
            return respondJson(out, 403, errorJson("invalid_session", "Invalid bridge token."))
        }
        session.bridgeClose()
        respondEmpty(out, 204)
    }

    // MARK: Static assets

    private fun serveStatic(path: String, method: String, out: OutputStream) {
        if (method != "GET" && method != "HEAD") {
            respondEmpty(out, 405)
            return
        }
        val clean = path.substringBefore('?').trimStart('/').ifEmpty { "index.html" }
        if (clean.split('/').any { it == ".." }) {
            respondEmpty(out, 404)
            return
        }
        val candidate = "web/$clean"
        val isAsset = clean.startsWith("assets/") || clean.endsWith(".webmanifest") || clean.endsWith(".png")
        val bytes = readAsset(candidate)
        if (bytes != null) {
            respondBytes(out, 200, contentType(clean), bytes, method == "HEAD")
            return
        }
        if (isAsset) {
            respondEmpty(out, 404)
            return
        }
        // The React app owns locale/page routes; serve the shell for them.
        val shell = readAsset("web/index.html")
        if (shell != null) {
            respondBytes(out, 200, "text/html; charset=utf-8", shell, method == "HEAD")
        } else {
            respondEmpty(out, 404)
        }
    }

    private fun readAsset(path: String): ByteArray? = runCatching {
        assets.open(path).use { it.readBytes() }
    }.getOrNull()

    private fun contentType(path: String): String = when (path.substringAfterLast('.').lowercase()) {
        "html" -> "text/html; charset=utf-8"
        "js", "mjs" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "webmanifest" -> "application/manifest+json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "ico" -> "image/x-icon"
        "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }

    // MARK: Responses

    private fun respondJson(out: OutputStream, status: Int, body: JsonObject, headOnly: Boolean = false) {
        respondBytes(out, status, "application/json; charset=utf-8", json.encodeToString(JsonObject.serializer(), body).toByteArray(), headOnly)
    }

    private fun respondEmpty(out: OutputStream, status: Int) {
        val text = "HTTP/1.1 ${statusLabel(status)}\r\n" +
            "Content-Length: 0\r\n" +
            "Connection: close\r\n" +
            "Cache-Control: no-store\r\n" +
            "\r\n"
        runCatching { out.write(text.toByteArray()) }
    }

    private fun respondBytes(out: OutputStream, status: Int, contentType: String, body: ByteArray, headOnly: Boolean) {
        val header = "HTTP/1.1 ${statusLabel(status)}\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n" +
            "Cache-Control: no-store\r\n" +
            "\r\n"
        runCatching {
            out.write(header.toByteArray())
            if (!headOnly) out.write(body)
            out.flush()
        }
    }

    private fun statusLabel(status: Int): String = when (status) {
        200 -> "200 OK"
        202 -> "202 Accepted"
        204 -> "204 No Content"
        400 -> "400 Bad Request"
        403 -> "403 Forbidden"
        404 -> "404 Not Found"
        405 -> "405 Method Not Allowed"
        429 -> "429 Too Many Requests"
        else -> "$status Error"
    }

    private fun errorJson(code: String, message: String): JsonObject = buildJsonObject {
        put("code", code)
        put("message", message)
    }

    private fun respondError(out: OutputStream, status: Int, code: String) {
        respondJson(out, status, errorJson(code, code))
    }

    private fun limitsJson(limits: TransferLimits): JsonObject = buildJsonObject {
        put("maxFileSize", limits.maxFileSize)
        put("maxActiveTransfers", limits.maxActiveTransfers)
        put("chunkSize", limits.chunkSize)
    }

    private fun parseBody(bytes: ByteArray): JsonObject? = runCatching {
        json.parseToJsonElement(String(bytes, StandardCharsets.UTF_8)).jsonObject
    }.getOrNull()

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun validToken(raw: String?): Boolean {
        val expected = synchronized(lock) { guestToken }
        return !raw.isNullOrBlank() && expected.isNotEmpty() &&
            MessageDigest.isEqual(expected.toByteArray(), raw.toByteArray())
    }

    /** Cancels pending long polls and releases the executor. */
    fun shutdown() {
        stop()
        pool.shutdownNow()
        runCatching { pool.awaitTermination(1, TimeUnit.SECONDS) }
    }
}
