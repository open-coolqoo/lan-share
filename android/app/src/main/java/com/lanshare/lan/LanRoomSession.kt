package com.lanshare.lan

import com.lanshare.CreatedRoom
import com.lanshare.JoinedRoom
import com.lanshare.RoomApiException
import com.lanshare.RoomHeartbeatResponse
import com.lanshare.RoomService
import com.lanshare.SignalingEnvelope
import com.lanshare.SignalingLink
import com.lanshare.defaultAndroidCapabilities
import com.lanshare.defaultTransferLimits
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private enum class LanRole { HOST, GUEST }

/**
 * One local room. The creator owns a TCP listener and advertises it over
 * Bonjour; the joiner either uses the addresses embedded in the QR or finds
 * the same advertised `sid` from the code. After the challenge/HMAC
 * handshake, SDP/ICE travel over the socket as `signal` frames and the
 * existing WebRTC/transport stack is untouched.
 */
internal class LanRoomSession(
    private val deviceId: String,
    private val deviceLabel: String,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    private val advertiserFactory: () -> LanAdvertiser,
    private val addressProvider: (Int) -> List<LanAddress> = ::enumerateLanAddresses,
) : RoomService, SignalingLink {
    private val lock = Any()
    private val handshakeBusy = AtomicBoolean(false)

    @Volatile private var role = LanRole.HOST
    @Volatile private var active = false
    @Volatile private var paired = false
    @Volatile private var code = ""
    @Volatile private var roomKey = ""
    @Volatile private var roomId = ""
    @Volatile private var sessionToken = ""
    @Volatile private var pairingExpiresAt = ""
    @Volatile private var sessionExpiresAt = ""
    @Volatile private var remoteDeviceId = ""
    @Volatile private var remoteDeviceLabel = ""
    @Volatile private var addresses: List<LanAddress> = emptyList()
    @Volatile private var room: JoinedRoom? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var listener: ServerSocket? = null
    @Volatile private var bridgeTransport: LanBridgeTransport? = null
    @Volatile private var bridgeToken: String = ""
    @Volatile private var pairingDeadlineMs: Long = 0
    /** Invalidates late callbacks from a socket that has already been replaced. */
    @Volatile private var socketGeneration = 0L

    private var advertiser: LanAdvertiser? = null
    private var acceptJob: Job? = null
    private var readJob: Job? = null
    private var writerJob: Job? = null
    private var pingJob: Job? = null
    private var deadlineJob: Job? = null
    private var resumeJob: Job? = null
    private var outgoing: Channel<LanMessage>? = null
    private var failures = 0
    private val pendingEvents = ArrayDeque<SignalingEnvelope>()
    private val pendingSignals = ArrayDeque<SignalingEnvelope>()
    private var eventSink: ((SignalingEnvelope) -> Unit)? = null
    private var disconnectSink: ((String) -> Unit)? = null

    // MARK: RoomService

    override suspend fun create(deviceId: String, deviceLabel: String): CreatedRoom = withContext(Dispatchers.IO) {
        teardown()
        role = LanRole.HOST
        code = LanPairing.generateCode()
        roomKey = LanPairing.roomKey(code)
        roomId = "room_" + LanPairing.randomToken(12)
        sessionToken = LanPairing.randomToken(24)
        remoteDeviceId = ""
        remoteDeviceLabel = ""
        paired = false
        failures = 0
        bridgeTransport = null
        bridgeToken = LanPairing.randomToken(24)
        pairingDeadlineMs = System.currentTimeMillis() + LAN_PAIRING_TTL_MS
        pairingExpiresAt = isoNow(LAN_PAIRING_TTL_MS)
        sessionExpiresAt = isoNow(LAN_SESSION_TTL_MS)

        val server = ServerSocket(0)
        server.reuseAddress = true
        listener = server
        addresses = addressProvider(server.localPort)
        val salt = LanPairing.randomToken(8)
        val txt = mapOf(
            "v" to "1",
            "salt" to salt,
            "sid" to LanPairing.mdnsSid(roomKey, salt),
        )
        advertiser = advertiserFactory().also {
            it.advertise("LanShare-${LanPairing.randomToken(3)}", server.localPort, txt)
        }
        val created = CreatedRoom(
            code = code,
            roomId = roomId,
            deviceId = deviceId,
            role = "initiator",
            peerCount = 1,
            sessionToken = sessionToken,
            pairingExpiresAt = pairingExpiresAt,
            sessionExpiresAt = sessionExpiresAt,
            limits = defaultTransferLimits(),
        )
        room = created.joined()
        active = true
        acceptJob = scope.launch(Dispatchers.IO) { acceptLoop(server) }
        deadlineJob = scope.launch {
            delay(LAN_PAIRING_TTL_MS)
            if (!paired) {
                log("lan: pairing window expired")
                stopAccepting()
            }
        }
        log("lan: room $roomId listening on ${server.localPort}, ${addresses.size} address(es)")
        created
    }

    override suspend fun join(
        target: LanJoinTarget,
        deviceId: String,
        deviceLabel: String,
    ): JoinedRoom = withContext(Dispatchers.IO) {
        teardown()
        role = LanRole.GUEST
        code = target.code
        roomKey = target.roomKey
        paired = false
        failures = 0
        active = true
        val candidates = target.addresses.ifEmpty { discoverAddresses(target.roomKey) }
        if (candidates.isEmpty()) {
            active = false
            throw RoomApiException("no_local_device", "No local device found for this code.")
        }
        // Keep the tried addresses for the reconnect/resume loop after the
        // initial socket drops.
        addresses = candidates
        var lastError: Throwable? = null
        for (address in candidates) {
            val joined = runCatching { joinAt(address, deviceId, deviceLabel) }.getOrElse {
                lastError = it
                null
            }
            if (joined != null) return@withContext joined
        }
        active = false
        val error = lastError
        throw when (error) {
            is RoomApiException -> error
            else -> RoomApiException("connection_failed", error?.message ?: "Unable to reach the local device.")
        }
    }

    override suspend fun heartbeat(room: JoinedRoom): RoomHeartbeatResponse =
        RoomHeartbeatResponse(ok = true, lastSeenAt = isoNow(0), sessionExpiresAt = room.sessionExpiresAt)

    /** The addresses the host advertised in its QR, or an empty list off-Wi-Fi. */
    internal fun localAddresses(): List<LanAddress> = addresses

    // MARK: Browser bridge

    /** Current host room fields for the browser guest's info endpoint. */
    internal fun bridgeInfo(): LanBridgeRoomInfo? {
        val currentRoom = room ?: return null
        if (role != LanRole.HOST || !active || code.isEmpty()) return null
        return LanBridgeRoomInfo(
            code = code,
            hostDeviceId = deviceId,
            hostDeviceLabel = deviceLabel,
            roomId = roomId,
            pairingExpiresAt = pairingExpiresAt,
            sessionExpiresAt = sessionExpiresAt,
            limits = currentRoom.limits,
        )
    }

    /** Routes host signaling to the browser guest instead of the TCP socket. */
    internal fun bridgeAttach(transport: LanBridgeTransport) {
        val queued = synchronized(lock) {
            bridgeTransport = transport
            val pending = pendingSignals.toList()
            pendingSignals.clear()
            pending
        }
        queued.forEach(transport::send)
    }

    /**
     * Authenticates a browser guest with the room code (or resumes with the
     * token minted by an earlier join) and announces it to the host so the
     * existing `peer_joined` path creates the offer.
     */
    internal fun bridgeJoin(
        joinCode: String,
        guestDeviceId: String,
        guestDeviceLabel: String,
        resumeToken: String?,
    ): LanBridgeWelcome? {
        val welcome = synchronized(lock) {
            if (role != LanRole.HOST || !active || code.isEmpty()) return@synchronized null
            if (!paired && System.currentTimeMillis() > pairingDeadlineMs) return@synchronized null
            if (!MessageDigest.isEqual(code.toByteArray(), joinCode.toByteArray())) return@synchronized null
            val resuming = paired &&
                !resumeToken.isNullOrBlank() &&
                MessageDigest.isEqual(bridgeToken.toByteArray(), resumeToken.toByteArray()) &&
                remoteDeviceId == guestDeviceId
            if (paired && !resuming) return@synchronized null
            if (bridgeToken.isEmpty()) bridgeToken = LanPairing.randomToken(24)
            remoteDeviceId = guestDeviceId
            remoteDeviceLabel = guestDeviceLabel
            paired = true
            LanBridgeWelcome(
                token = bridgeToken,
                roomId = roomId,
                hostDeviceId = deviceId,
                hostDeviceLabel = deviceLabel,
                pairingExpiresAt = pairingExpiresAt,
                sessionExpiresAt = sessionExpiresAt,
                limits = room?.limits ?: defaultTransferLimits(),
            )
        } ?: return null
        emitAuthAndPeerJoined(guestDeviceLabel)
        log("lan: browser guest ${guestDeviceLabel.take(32)} paired")
        return welcome
    }

    /** Relays an answer/ICE envelope from the browser to the host's AppViewModel. */
    internal fun bridgeIncoming(envelope: SignalingEnvelope) {
        val from = synchronized(lock) {
            if (bridgeTransport == null) return
            remoteDeviceId
        }
        emit(envelope.copy(roomId = roomId, from = from))
    }

    /** Drop a bridge transport that is shutting down, without a peer_left event. */
    internal fun bridgeDetach(transport: LanBridgeTransport) {
        synchronized(lock) {
            if (bridgeTransport !== transport) return
            bridgeTransport = null
            bridgeToken = ""
            paired = false
            remoteDeviceId = ""
            remoteDeviceLabel = ""
        }
    }

    /** The browser tab closed: keep the room and code, drop the guest. */
    internal fun bridgeClose() {
        val transport = synchronized(lock) {
            val current = bridgeTransport
            bridgeTransport = null
            current
        }
        transport?.close()
        val from = synchronized(lock) {
            paired = false
            bridgeToken = ""
            val previous = remoteDeviceId
            remoteDeviceId = ""
            remoteDeviceLabel = ""
            previous
        }
        emit(SignalingEnvelope(type = "peer_left", roomId = roomId, from = from))
    }

    /** Non-suspending teardown for `ViewModel.onCleared`. */
    internal fun shutdown() = teardown()

    override suspend fun close(room: JoinedRoom) {
        val bridge = synchronized(lock) { bridgeTransport }
        if (bridge != null) {
            bridge.send(SignalingEnvelope(type = "room_closed", roomId = roomId, deviceId = deviceId))
            bridge.close()
        } else {
            sendFrame(LanMessage(t = "close"))
            delay(60)
        }
        teardown()
    }

    override fun signalingLink(log: (String) -> Unit): SignalingLink = this

    // MARK: SignalingLink

    override fun connect(
        room: JoinedRoom,
        onEvent: (SignalingEnvelope) -> Unit,
        onDisconnect: (String) -> Unit,
    ) {
        val buffered = synchronized(lock) {
            this.room = room
            eventSink = onEvent
            disconnectSink = onDisconnect
            val events = pendingEvents.toList()
            pendingEvents.clear()
            events
        }
        buffered.forEach { event -> runCatching { onEvent(event) } }
        if (role == LanRole.GUEST && !isSocketAlive()) startResume()
    }

    override fun send(message: SignalingEnvelope) {
        val envelope = message.copy(roomId = roomId, from = deviceId)
        val transport = synchronized(lock) { bridgeTransport }
        if (transport != null) {
            transport.send(envelope)
            return
        }
        val channel = synchronized(lock) { outgoing }
        if (channel != null) {
            channel.trySend(LanMessage(t = "signal", e = envelope))
        } else {
            synchronized(lock) {
                if (pendingSignals.size >= 64) pendingSignals.removeFirst()
                pendingSignals.addLast(envelope)
            }
        }
    }

    override fun close() {
        closeSocket()
    }

    // MARK: Host

    private suspend fun acceptLoop(server: ServerSocket) {
        while (scope.isActive && active && !server.isClosed) {
            val client = try {
                server.accept()
            } catch (error: Exception) {
                if (active) log("lan: accept stopped ${error.message}")
                break
            }
            scope.launch(Dispatchers.IO) {
                if (!handshakeBusy.compareAndSet(false, true)) {
                    runCatching { client.close() }
                    return@launch
                }
                try {
                    handleIncoming(client)
                } finally {
                    handshakeBusy.set(false)
                }
            }
        }
    }

    private fun handleIncoming(client: Socket) {
        client.soTimeout = 10_000
        val input = DataInputStream(client.getInputStream())
        val output = DataOutputStream(client.getOutputStream())
        val first = try {
            LanFrameReader(input).read()
        } catch (_: Exception) {
            null
        }
        when (first?.t) {
            "hello" -> handleHello(client, input, output, first)
            "resume" -> handleResume(client, input, output, first)
            else -> runCatching { client.close() }
        }
    }

    private fun handleHello(
        client: Socket,
        input: DataInputStream,
        output: DataOutputStream,
        hello: LanMessage,
    ) {
        if (paired) {
            runCatching { LanFrameWriter(output).write(LanMessage(t = "reject", code = "room_full")) }
            runCatching { client.close() }
            return
        }
        if (hello.deviceId.isNullOrBlank() || hello.v != LAN_PROTOCOL_VERSION) {
            runCatching { LanFrameWriter(output).write(LanMessage(t = "reject", code = "invalid_request")) }
            runCatching { client.close() }
            return
        }
        val nonce = LanPairing.randomToken(16)
        runCatching { LanFrameWriter(output).write(LanMessage(t = "challenge", nonce = nonce)) }
        val auth = try {
            LanFrameReader(input).read()
        } catch (_: Exception) {
            null
        }
        val expected = LanPairing.authProof(roomKey, nonce, hello.deviceId)
        val supplied = auth?.proof.orEmpty()
        if (auth?.t != "auth" || !MessageDigest.isEqual(expected.toByteArray(), supplied.toByteArray())) {
            failures += 1
            log("lan: rejected handshake ($failures)")
            runCatching { LanFrameWriter(output).write(LanMessage(t = "reject", code = "invalid_code")) }
            runCatching { client.close() }
            Thread.sleep(2_000)
            if (failures >= 10) {
                log("lan: too many failed handshakes")
                stopAccepting()
            }
            return
        }
        remoteDeviceId = hello.deviceId
        remoteDeviceLabel = hello.deviceLabel.orEmpty()
        val welcome = welcomeFrame(hostProof = LanPairing.hostProof(roomKey, nonce, deviceId))
        runCatching { LanFrameWriter(output).write(welcome) }
        paired = true
        pairingExpiresAt = isoNow(0)
        advertiser?.stop()
        advertiser = null
        attachSocket(client, input, output)
        emitAuthAndPeerJoined(remoteDeviceLabel)
        log("lan: guest ${hello.deviceLabel.orEmpty()} paired")
    }

    private fun handleResume(
        client: Socket,
        input: DataInputStream,
        output: DataOutputStream,
        resume: LanMessage,
    ) {
        val valid = paired &&
            resume.deviceId == remoteDeviceId &&
            resume.sessionToken == sessionToken
        if (!valid) {
            runCatching { LanFrameWriter(output).write(LanMessage(t = "reject", code = "invalid_session")) }
            runCatching { client.close() }
            return
        }
        // The session token proves the guest; this proof lets the guest verify
        // it reconnected to the same host rather than an impostor.
        val welcome = welcomeFrame(
            hostProof = LanPairing.resumeHostProof(roomKey, sessionToken, deviceId),
        )
        runCatching { LanFrameWriter(output).write(welcome) }
        attachSocket(client, input, output)
        emitAuthAndPeerJoined(remoteDeviceLabel)
        log("lan: guest resumed")
    }

    private fun welcomeFrame(hostProof: String?): LanMessage = LanMessage(
        t = "welcome",
        v = LAN_PROTOCOL_VERSION,
        deviceId = deviceId,
        deviceLabel = deviceLabel,
        roomId = roomId,
        role = "peer",
        sessionToken = sessionToken,
        pairingExpiresAt = pairingExpiresAt,
        sessionExpiresAt = sessionExpiresAt,
        limits = defaultTransferLimits(),
        proof = hostProof,
    )

    // MARK: Guest

    private suspend fun discoverAddresses(roomKey: String): List<LanAddress> {
        val local = advertiserFactory()
        advertiser = local
        val match = try {
            local.discover(5_000) { service ->
                val salt = service.txt["salt"].orEmpty()
                val sid = service.txt["sid"].orEmpty()
                service.txt["v"] == "1" && salt.isNotBlank() && sid.isNotBlank() &&
                    LanPairing.matchesSid(roomKey, salt, sid)
            }
        } catch (_: Exception) {
            null
        } finally {
            advertiser?.stop()
            advertiser = null
        }
        return match?.let { listOf(LanAddress(it.host, it.port)) }.orEmpty()
    }

    private suspend fun joinAt(
        address: LanAddress,
        deviceId: String,
        deviceLabel: String,
    ): JoinedRoom? {
        log("lan: trying ${address.authority}")
        val client = try {
            Socket().apply {
                connect(InetSocketAddress(address.host, address.port), 2_000)
                soTimeout = 8_000
            }
        } catch (error: Exception) {
            log("lan: connect failed ${error.message}")
            return null
        }
        try {
            val input = DataInputStream(client.getInputStream())
            val output = DataOutputStream(client.getOutputStream())
            val writer = LanFrameWriter(output)
            val reader = LanFrameReader(input)
            writer.write(
                LanMessage(
                    t = "hello",
                    v = LAN_PROTOCOL_VERSION,
                    deviceId = deviceId,
                    deviceLabel = deviceLabel,
                    deviceType = "mobile",
                    capabilities = defaultAndroidCapabilities(),
                ),
            )
            val challenge = reader.read()
            if (challenge?.t != "challenge" || challenge.nonce.isNullOrBlank()) {
                client.close()
                return null
            }
            val nonce = challenge.nonce
            writer.write(LanMessage(t = "auth", proof = LanPairing.authProof(roomKey, nonce, deviceId)))
            val welcome = reader.read()
            if (welcome?.t != "welcome") {
                client.close()
                if (welcome?.t == "reject") {
                    throw RoomApiException(
                        welcome.code ?: "join_rejected",
                        welcome.message ?: "The local device rejected the code.",
                    )
                }
                return null
            }
            val expectedProof = LanPairing.hostProof(roomKey, nonce, welcome.deviceId.orEmpty())
            if (welcome.deviceId.isNullOrBlank() ||
                !MessageDigest.isEqual(expectedProof.toByteArray(), welcome.proof.orEmpty().toByteArray())
            ) {
                client.close()
                return null
            }
            roomId = welcome.roomId.orEmpty().ifBlank { "room_" + LanPairing.randomToken(12) }
            sessionToken = welcome.sessionToken.orEmpty().ifBlank { LanPairing.randomToken(24) }
            remoteDeviceId = welcome.deviceId.orEmpty()
            remoteDeviceLabel = welcome.deviceLabel.orEmpty()
            pairingExpiresAt = welcome.pairingExpiresAt.orEmpty().ifBlank { isoNow(LAN_PAIRING_TTL_MS) }
            sessionExpiresAt = welcome.sessionExpiresAt.orEmpty().ifBlank { isoNow(LAN_SESSION_TTL_MS) }
            val joined = JoinedRoom(
                roomId = roomId,
                deviceId = deviceId,
                role = welcome.role ?: "peer",
                peerCount = 2,
                sessionToken = sessionToken,
                pairingExpiresAt = pairingExpiresAt,
                sessionExpiresAt = sessionExpiresAt,
                limits = welcome.limits ?: defaultTransferLimits(),
            )
            room = joined
            paired = true
            attachSocket(client, input, output)
            emitAuthAndPeerJoined(remoteDeviceLabel)
            log("lan: joined $roomId")
            return joined
        } catch (error: Throwable) {
            runCatching { client.close() }
            if (error is RoomApiException) throw error
            if (error is CancellationException) throw error
            log("lan: handshake failed ${error.message}")
            return null
        }
    }

    // MARK: Socket plumbing

    private fun attachSocket(
        client: Socket,
        input: DataInputStream,
        output: DataOutputStream,
    ) {
        closeSocket()
        val generation = synchronized(lock) {
            socketGeneration += 1
            socketGeneration
        }
        client.soTimeout = 30_000
        val writer = LanFrameWriter(output)
        val channel = Channel<LanMessage>(Channel.UNLIMITED)
        val queued = synchronized(lock) {
            socket = client
            outgoing = channel
            val pending = pendingSignals.toList()
            pendingSignals.clear()
            pending
        }
        queued.forEach { envelope -> channel.trySend(LanMessage(t = "signal", e = envelope)) }
        writerJob = scope.launch(Dispatchers.IO) {
            for (message in channel) {
                runCatching { writer.write(message) }.onFailure {
                    log("lan: write failed ${it.message}")
                    break
                }
            }
        }
        readJob = scope.launch(Dispatchers.IO) { readLoop(LanFrameReader(input), generation) }
        pingJob = scope.launch {
            var counter = 0
            while (isActive) {
                delay(10_000)
                channel.trySend(LanMessage(t = "ping", n = counter++))
            }
        }
    }

    private fun readLoop(reader: LanFrameReader, generation: Long) {
        try {
            while (scope.isActive && active && generation == socketGeneration) {
                val message = reader.read() ?: break
                if (generation != socketGeneration) return
                when (message.t) {
                    "signal" -> message.e?.let { envelope ->
                        emit(envelope.copy(roomId = roomId, from = remoteDeviceId))
                    }
                    "ping" -> sendFrame(LanMessage(t = "pong", n = message.n))
                    "pong" -> Unit
                    "close" -> {
                        emit(SignalingEnvelope(type = "room_closed", roomId = roomId, from = remoteDeviceId))
                        closeSocket()
                        return
                    }
                    "reject" -> {
                        log("lan: peer rejected ${message.code}")
                        closeSocket()
                        return
                    }
                    "error" -> log("lan: peer error ${message.code} ${message.message}")
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) return
            log("lan: read stopped ${error.message}")
        }
        onSocketClosed(generation)
    }

    private fun onSocketClosed(generation: Long) {
        if (generation != socketGeneration) return
        val sink = synchronized(lock) { disconnectSink }
        if (sink != null && active) {
            runCatching { sink("lan signaling closed") }
        }
    }

    private fun closeSocket() {
        synchronized(lock) { socketGeneration += 1 }
        val current = socket
        socket = null
        runCatching { current?.close() }
        synchronized(lock) {
            outgoing?.close()
            outgoing = null
        }
        readJob?.cancel()
        readJob = null
        writerJob?.cancel()
        writerJob = null
        pingJob?.cancel()
        pingJob = null
    }

    private fun isSocketAlive(): Boolean {
        val current = socket ?: return false
        return current.isConnected && !current.isClosed
    }

    private fun sendFrame(message: LanMessage) {
        val channel = synchronized(lock) { outgoing }
        channel?.trySend(message)
    }

    private fun emit(event: SignalingEnvelope) {
        val sink = synchronized(lock) { eventSink }
        if (sink != null) {
            runCatching { sink(event) }.onFailure { log("lan: event failed ${it.message}") }
            return
        }
        synchronized(lock) {
            if (pendingEvents.size >= 64) pendingEvents.removeFirst()
            pendingEvents.addLast(event)
        }
    }

    private fun emitAuthAndPeerJoined(label: String) {
        emit(SignalingEnvelope(type = "auth_ok", roomId = roomId, deviceId = deviceId))
        emit(
            SignalingEnvelope(
                type = "peer_joined",
                roomId = roomId,
                from = remoteDeviceId,
                deviceId = remoteDeviceId,
                payload = buildJsonObject { put("deviceLabel", label) },
            ),
        )
    }

    private fun startResume() {
        if (resumeJob?.isActive == true) return
        val targets = addresses
        if (targets.isEmpty()) return
        resumeJob = scope.launch(Dispatchers.IO) {
            val generation = socketGeneration
            val deadline = System.currentTimeMillis() + 60_000
            var attempt = 0
            while (isActive && active && !isSocketAlive() && System.currentTimeMillis() < deadline) {
                for (address in targets) {
                    if (!active || isSocketAlive()) break
                    val client = try {
                        Socket().apply {
                            connect(InetSocketAddress(address.host, address.port), 2_000)
                            soTimeout = 8_000
                        }
                    } catch (_: Exception) {
                        continue
                    }
                    val resumed = runCatching { resumeHandshake(client) }.getOrDefault(false)
                    if (resumed) return@launch
                    runCatching { client.close() }
                }
                attempt += 1
                delay(minOf(10_000L, 1_000L * attempt))
            }
            if (active && !isSocketAlive() && generation == socketGeneration) {
                onSocketClosed(generation)
            }
        }
    }

    private fun resumeHandshake(client: Socket): Boolean {
        val input = DataInputStream(client.getInputStream())
        val output = DataOutputStream(client.getOutputStream())
        val writer = LanFrameWriter(output)
        val reader = LanFrameReader(input)
        writer.write(LanMessage(t = "resume", deviceId = deviceId, sessionToken = sessionToken))
        val welcome = reader.read()
        if (welcome?.t != "welcome") return false
        val expectedProof = LanPairing.resumeHostProof(roomKey, sessionToken, welcome.deviceId.orEmpty())
        if (!MessageDigest.isEqual(expectedProof.toByteArray(), welcome.proof.orEmpty().toByteArray())) {
            return false
        }
        attachSocket(client, input, output)
        emitAuthAndPeerJoined(welcome.deviceLabel.orEmpty().ifBlank { remoteDeviceLabel })
        log("lan: resumed")
        return true
    }

    private fun stopAccepting() {
        val current = listener
        listener = null
        runCatching { current?.close() }
        advertiser?.stop()
        advertiser = null
    }

    private fun teardown() {
        active = false
        paired = false
        val bridge = synchronized(lock) {
            val current = bridgeTransport
            bridgeTransport = null
            bridgeToken = ""
            current
        }
        bridge?.close()
        acceptJob?.cancel()
        acceptJob = null
        deadlineJob?.cancel()
        deadlineJob = null
        resumeJob?.cancel()
        resumeJob = null
        closeSocket()
        stopAccepting()
        synchronized(lock) {
            pendingEvents.clear()
            pendingSignals.clear()
            eventSink = null
            disconnectSink = null
        }
        room = null
        code = ""
        roomKey = ""
        roomId = ""
        sessionToken = ""
        pairingExpiresAt = ""
        sessionExpiresAt = ""
        remoteDeviceId = ""
        remoteDeviceLabel = ""
        addresses = emptyList()
        failures = 0
    }

    private fun isoNow(offsetMs: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return formatter.format(Date(System.currentTimeMillis() + offsetMs))
    }
}
