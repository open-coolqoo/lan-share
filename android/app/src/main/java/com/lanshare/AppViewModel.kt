package com.lanshare

import android.net.Uri
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.File
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.webrtc.DataChannel
import org.webrtc.PeerConnectionFactory
import org.webrtc.PeerConnectionFactory.InitializationOptions

enum class AppNavigationEvent {
    OpenShare,
}

data class AppUiState(
    val origin: String = defaultServerOrigin(),
    val locale: String = defaultAndroidLocale(),
    val localeManuallySelected: Boolean = false,
    val darkTheme: Boolean = false,
    val roomCode: String = "",
    val connectedPeerCode: String? = null,
    val createdCode: String? = null,
    val invalidatedCodes: List<String> = emptyList(),
    val pairingExpiresAtMs: Long? = null,
    val pairingCountdownPausedAtMs: Long? = null,
    val codeCopied: Boolean = false,
    internal val roomCreateFailureCount: Int = 0,
    internal val roomCreateFailureNotice: Boolean = false,
    internal val roomCreateFailureSeen: Boolean = false,
    val joiningRoom: Boolean = false,
    val roomError: String = "",
    val joinedRoom: JoinedRoom? = null,
    val siteConfig: PublicSiteConfig? = null,
    val selectedPage: PublicPage? = null,
    val pageError: String = "",
    val localDeviceLabel: String = "Android",
    val peerDeviceLabel: String = "",
    val localTransfers: List<TransferCardState> = emptyList(),
    val peerTransfers: List<TransferCardState> = emptyList(),
    val chatMessages: List<ChatMessageState> = emptyList(),
    val chatFailure: String = "",
    /** A folder pick that added nothing useful (empty or capped); the screen localizes it. */
    val folderPickNotice: FolderPickNotice? = null,
    val pendingSharedFileCount: Int = 0,
    val connectionPath: ConnectionPath = ConnectionPath.UNKNOWN,
    val directConnectionFailed: Boolean = false,
    val directConnectionFailureNotice: Boolean = false,
    val downloadCompleteToastId: Long? = null,
    val signalingReady: Boolean = false,
    val channelReady: Boolean = false,
    val reviewPromptVisible: Boolean = false,
    internal val transientTransportReconnecting: Boolean = false,
    val logs: List<String> = listOf("ready"),
)

internal const val APP_PREFERENCES = "lanshare_android_preferences"
internal const val APP_LOCALE_KEY = "app_locale"
internal const val APP_DARK_THEME_KEY = "app_dark_theme"
internal const val REVIEW_PROMPT_LAST_SHOWN_AT_KEY = "review_prompt_last_shown_at_ms"
internal const val REVIEW_PROMPT_INTERVAL_MS = 30L * 24L * 60L * 60L * 1_000L

internal fun shouldShowReviewPrompt(lastShownAtMs: Long?, nowMs: Long, promptVisible: Boolean): Boolean =
    !promptVisible && (lastShownAtMs == null || nowMs - lastShownAtMs >= REVIEW_PROMPT_INTERVAL_MS)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val publicContent = PublicContentRepository.fromAssets(application)
    private val preferences = application.getSharedPreferences(APP_PREFERENCES, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(
        initialAppUiState(
            persistedLocale = preferences.getString(APP_LOCALE_KEY, null),
            persistedDarkTheme = preferences.getBoolean(APP_DARK_THEME_KEY, false),
        ).let { initial ->
            initial.copy(
                siteConfig = publicContent.siteConfig,
                locale = if (initial.localeManuallySelected) initial.locale else localeWithFallback(publicContent.siteConfig.defaultLocale),
            )
        },
    )
    val state: StateFlow<AppUiState> = _state
    private val navigationEventsChannel = Channel<AppNavigationEvent>(capacity = Channel.BUFFERED)
    val navigationEvents: Flow<AppNavigationEvent> = navigationEventsChannel.receiveAsFlow()

    private val deviceId = "android_${UUID.randomUUID()}"
    private val deviceLabel = "Android ${Build.MODEL}"
    private val downloadStore = AndroidDownloadStore(application.contentResolver)
    private val reviewPromptLock = Any()
    private var nextDownloadCompleteToastId = 1L
    private var signaling: SignalingClient? = null
    private var peer: AndroidPeerRuntime? = null
    private var transfer: AndroidTransferSession? = null
    private val pendingSharedUrisLock = Any()
    private val pendingSharedUris = mutableListOf<Uri>()
    private var lastPeerId: String = ""
    private var offeredPeerId: String = ""
    private var pairingRefreshJob: Job? = null
    private var heartbeatJob: Job? = null
    private var directConnectionFailureJob: Job? = null
    private var signalingReconnectJob: Job? = null
    private var signalingReconnectDeadlineMs = 0L
    private var signalingReconnectAttempt = 0
    private var peerGeneration = 0L
    private var sharedQueueGeneration = 0L
    private var shouldOpenShareOnConnect = false
    private var createRetryJob: Job? = null
    private var createRetryAttempt = 0
    private var createInFlight = false
    private var lastCreateAttemptMs = 0L

    init {
        cleanupTemporaryFiles(application)
        PeerConnectionFactory.initialize(
            InitializationOptions.builder(application)
                .setEnableInternalTracer(false)
                .createInitializationOptions(),
        )
        createRoom()
        startPairingCodeAutoRefresh()
    }

    fun setOrigin(value: String) {
        _state.update { it.copy(origin = value) }
    }

    fun setRoomCode(value: String) {
        _state.update { it.copy(roomCode = value.filter { ch -> ch.isDigit() }.take(8), roomError = "") }
    }

    fun createRoom(): Job {
        lastCreateAttemptMs = System.currentTimeMillis()
        createInFlight = true
        return viewModelScope.launch {
            try {
                runCatching {
                    val previousCode = state.value.createdCode
                    val created = api().create(deviceId, deviceLabel)
                    cancelCreateRetry()
                    _state.update {
                        clearRoomCreateFailure(
                            it.copy(
                                createdCode = created.code,
                                joinedRoom = created.joined(),
                                pairingExpiresAtMs = pairingDisplayExpiresAtMs(System.currentTimeMillis()),
                                pairingCountdownPausedAtMs = null,
                                codeCopied = false,
                                invalidatedCodes = listOfNotNull(previousCode) + it.invalidatedCodes,
                            ),
                        )
                    }
                    log("room: created ${created.code}")
                    restartHeartbeat()
                    connectSignaling()
                }.onFailure {
                    log("room: create failed ${it.message}")
                    _state.update(::markRoomCreateFailure)
                    scheduleCreateRetry()
                }
            } finally {
                createInFlight = false
            }
        }
    }

    /**
     * Keeps retrying room setup until a local code exists.
     *
     * The launch attempt runs before the network stack is necessarily usable
     * (no Wi-Fi association yet, a captive portal, or a momentarily unreachable
     * server), and nothing else retries it: the pairing auto-refresh only runs
     * once a code exists, so the Connect tab would stay empty until the user
     * pressed "Create code" by hand.
     */
    private fun scheduleCreateRetry() {
        if (createRetryJob != null || state.value.createdCode != null || state.value.joinedRoom != null) return
        val delayMs = createRetryDelayMs(createRetryAttempt)
        if (delayMs == null) {
            log("room: create retries exhausted")
            return
        }
        createRetryAttempt += 1
        log("room: create retry $createRetryAttempt in ${delayMs / 1_000}s")
        createRetryJob = viewModelScope.launch {
            delay(delayMs)
            createRetryJob = null
            if (state.value.createdCode != null || state.value.joinedRoom != null) return@launch
            createRoom()
        }
    }

    private fun cancelCreateRetry() {
        createRetryJob?.cancel()
        createRetryJob = null
        createRetryAttempt = 0
    }

    /** The user acknowledged the unreachable-server dialog for this episode. */
    fun dismissCreateFailureNotice() {
        _state.update(::dismissRoomCreateFailure)
    }

    /** Called when the app comes back to the foreground. */
    fun onAppForegrounded() {
        val nowMs = System.currentTimeMillis()
        if (!shouldRetryCreateOnForeground(createInFlight, state.value.createdCode, state.value.joinedRoom, lastCreateAttemptMs, nowMs)) {
            return
        }
        // Foreground cycling must not turn into a create storm; a pending
        // scheduled retry already covers the case where this is too soon.
        cancelCreateRetry()
        log("room: create retry after foreground")
        createRoom()
    }

    /** Called when the app leaves the foreground. */
    fun onAppBackgrounded() {
        cancelCreateRetry()
    }

    fun joinRoom() {
        joinRoom(state.value.roomCode, openShareOnConnect = true)
    }

    fun joinScannedCode(rawValue: String?, noPairingCodeMessage: String) {
        val code = pairingCodeFromScanResult(rawValue)
        if (code == null) {
            _state.update { it.copy(roomError = noPairingCodeMessage) }
            log("scan: no pairing code found")
            return
        }
        joinRoom(code, openShareOnConnect = true)
    }

    fun reportScanFailure(message: String) {
        _state.update { it.copy(roomError = message) }
        log("scan: failed $message")
    }

    private fun joinRoom(code: String, openShareOnConnect: Boolean = false) {
        val normalizedCode = code.filter { it.isDigit() }.take(8)
        val validationState = state.value.copy(roomCode = normalizedCode)
        when (validatePeerCode(normalizedCode, validationState)) {
            PeerCodeValidation.INVALID -> {
                _state.update { it.copy(roomCode = normalizedCode, roomError = "Enter a valid 8-digit code.") }
                log("room: enter a valid 8-digit code")
                return
            }
            PeerCodeValidation.SELF -> {
                _state.update { it.copy(roomCode = normalizedCode, roomError = "Peer code cannot match this device's code.") }
                log("room: peer code cannot match local code")
                return
            }
            PeerCodeValidation.REFRESHED -> {
                _state.update { it.copy(roomCode = normalizedCode, roomError = "This code was refreshed. Enter the latest code.") }
                log("room: code was refreshed")
                return
            }
            PeerCodeValidation.OK -> Unit
        }
        if (openShareOnConnect) shouldOpenShareOnConnect = true
        _state.update { pausePairingCountdown(it.copy(roomCode = normalizedCode, joiningRoom = true, roomError = ""), System.currentTimeMillis()) }
        viewModelScope.launch {
            runCatching {
                val joined = api().join(deriveRoomKey(normalizedCode), deviceId, deviceLabel)
                val nowMs = System.currentTimeMillis()
                _state.update {
                    pausePairingCountdown(it.copy(
                        joinedRoom = joined,
                        connectedPeerCode = normalizedCode,
                        pairingExpiresAtMs = pairingDisplayExpiresAtMs(nowMs),
                        pairingCountdownPausedAtMs = null,
                        connectionPath = ConnectionPath.UNKNOWN,
                        directConnectionFailed = false,
                        joiningRoom = false,
                        transientTransportReconnecting = false,
                        roomError = "",
                    ), nowMs)
                }
                log("room: joined ${joined.roomId} as ${joined.role}")
                restartHeartbeat()
                startDirectConnectionFailureTimer()
                connectSignaling()
            }.onFailure {
                shouldOpenShareOnConnect = false
                _state.update { state -> resetFailedPeerJoinState(state, it.message ?: "Room join failed.") }
                log("room: join failed ${it.message}")
            }
        }
    }

    fun copyCode() {
        val code = state.value.createdCode ?: return
        if (code.isBlank()) return
        val app = getApplication<Application>()
        val clipboard = app.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(app.getString(R.string.local_code), code))
        _state.update { it.copy(codeCopied = true) }
        log("room: code copied")
    }

    fun clearCodeCopied() {
        _state.update { it.copy(codeCopied = false) }
    }

    fun setLocale(value: String) {
        preferences.edit().putString(APP_LOCALE_KEY, value).apply()
        _state.update { it.copy(locale = value, localeManuallySelected = true) }
    }

    fun setDarkTheme(value: Boolean) {
        preferences.edit().putBoolean(APP_DARK_THEME_KEY, value).apply()
        _state.update { it.copy(darkTheme = value) }
    }

    fun clearDownloadCompleteToast(id: Long) {
        _state.update {
            if (it.downloadCompleteToastId == id) it.copy(downloadCompleteToastId = null) else it
        }
    }

    private fun maybeShowReviewPrompt(nowMs: Long = System.currentTimeMillis()) {
        synchronized(reviewPromptLock) {
            val lastShownAtMs = preferences.getLong(REVIEW_PROMPT_LAST_SHOWN_AT_KEY, -1L).takeIf { it >= 0L }
            if (!shouldShowReviewPrompt(lastShownAtMs, nowMs, state.value.reviewPromptVisible)) return
            preferences.edit().putLong(REVIEW_PROMPT_LAST_SHOWN_AT_KEY, nowMs).apply()
            _state.update { it.copy(reviewPromptVisible = true) }
        }
    }

    fun dismissReviewPrompt() {
        _state.update { it.copy(reviewPromptVisible = false) }
    }

    fun onLocalTransferCompleted(card: TransferCardState?) {
        if (card == null) return
        val previous = state.value.localTransfers.firstOrNull { it.fileId == card.fileId }
        _state.update { applyChatTransferCardUpdate(it, card, isLocal = true) }
        if (card.status == TransferCardStatus.SentToWeb && previous?.status != TransferCardStatus.SentToWeb) {
            maybeShowReviewPrompt()
        }
    }

    fun disconnect() {
        shouldOpenShareOnConnect = false
        cleanupPendingSharedUris()
        viewModelScope.launch {
            val room = state.value.joinedRoom
            closeActiveRoom(room, "disconnect")
            closeSignaling()
            resetPeerConnection()
            lastPeerId = ""
            offeredPeerId = ""
            stopHeartbeat()
            _state.update { resetConnectionUiState(it).copy(transientTransportReconnecting = false) }
            log("room: disconnected")
        }
    }

    fun clearDirectConnectionFailure() {
        _state.update { it.copy(directConnectionFailureNotice = false) }
        log("webrtc: direct failure dismissed")
    }

    fun retryAfterNetworkChange() {
        shouldOpenShareOnConnect = false
        cleanupPendingSharedUris()
        stopDirectConnectionFailureTimer()
        val room = state.value.joinedRoom
        closeSignaling()
        resetPeerConnection()
        stopHeartbeat()
        _state.update { resetAfterNetworkChange(it) }
        log("network: retrying with a fresh pairing code")
        viewModelScope.launch {
            if (room != null) closeActiveRoom(room, "network-change")
            createRoom()
        }
    }

    fun connectSignaling() {
        val room = state.value.joinedRoom ?: return
        closeSignaling(clearReconnect = false)
        signaling = SignalingClient(
            origin = state.value.origin,
            room = room,
            onEvent = ::handleSignalingEvent,
            onDisconnect = ::handleSignalingDisconnect,
            onLog = ::log,
        ).also { it.connect() }
    }

    fun createOffer() {
        if (lastPeerId.isBlank()) {
            log("webrtc: no peer id yet")
            return
        }
        offeredPeerId = lastPeerId
        ensurePeer().createOffer(lastPeerId)
    }

    private fun handleSignal(event: SignalingEnvelope) {
        when (event.type) {
            "auth_ok" -> {
                clearSignalingReconnect()
                _state.update { it.copy(signalingReady = true) }
                log("signaling: auth ok")
            }
            "peer_joined" -> {
                lastPeerId = event.from ?: event.deviceId ?: ""
                val label = peerLabelFromSignalPayload(event.payload)
                _state.update {
                    pausePairingCountdown(
                        it.copy(peerDeviceLabel = label.ifBlank { getApplication<Application>().getString(R.string.web_browser) }),
                        System.currentTimeMillis(),
                    )
                }
                log("signaling: peer joined $lastPeerId")
                if (shouldAutoCreateOfferForPeer(
                        peerId = lastPeerId,
                        offeredPeerId = offeredPeerId,
                        role = state.value.joinedRoom?.role.orEmpty(),
                        transientTransportReconnecting = state.value.transientTransportReconnecting,
                    )
                ) {
                    createOffer()
                }
            }
            "peer_left" -> {
                if (event.from == lastPeerId) {
                    lastPeerId = ""
                    offeredPeerId = ""
                    resetPeerConnection(clearTransfers = false)
                    _state.update {
                        resumePairingCountdown(
                            it.copy(
                                peerDeviceLabel = "",
                                peerTransfers = emptyList(),
                                transientTransportReconnecting = false,
                            ),
                            System.currentTimeMillis(),
                        )
                    }
                }
                log("signaling: peer left ${event.from}")
            }
            "room_closed" -> {
                log("signaling: room closed")
                recoverClosedRoom()
            }
            "offer", "answer", "ice_candidate" -> ensurePeer().handleSignal(event)
            "error" -> log("signaling: error ${event.error}")
        }
    }

    private fun handleSignalingEvent(client: SignalingClient, event: SignalingEnvelope) {
        viewModelScope.launch {
            if (signaling === client) handleSignal(event)
        }
    }

    private fun ensurePeer(): AndroidPeerRuntime {
        val existing = peer
        if (existing != null) return existing
        val generation = ++peerGeneration
        val factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        val runtime = AndroidPeerRuntime(
            factory = factory,
            iceServers = state.value.siteConfig?.rtc?.toPeerConnectionIceServers().orEmpty(),
            sendSignal = { message -> dispatchPeerCallback(generation) { signaling?.send(message) } },
            onRemoteDataChannel = { channel -> dispatchPeerCallback(generation) { attachDataChannel(channel, generation) } },
            onConnectionState = { peerState -> dispatchPeerCallback(generation) { handlePeerConnectionState(peerState) } },
            onLog = ::log,
        )
        peer = runtime
        startDirectConnectionFailureTimer()
        return runtime
    }

    private fun resetPeerConnection(clearTransfers: Boolean = true) {
        stopDirectConnectionFailureTimer()
        peerGeneration += 1
        val reconnectingAfterTransientLoss = !clearTransfers && state.value.joinedRoom != null
        _state.update {
            it.copy(
                channelReady = false,
                directConnectionFailed = false,
                transientTransportReconnecting = reconnectingAfterTransientLoss,
                localTransfers = if (clearTransfers) emptyList() else it.localTransfers,
                peerTransfers = if (clearTransfers) emptyList() else it.peerTransfers,
            )
        }
        if (clearTransfers) {
            lastPeerId = ""
        }
        peer?.close()
        peer = null
        transfer?.cleanup()
        transfer = null
        // 注意：待发送队列不在这里清空。瞬断/直连失败/房间被关闭等自动恢复
        // 路径必须保留队列，否则刚入队的已选文件会在重连前被销毁；
        // 它只在用户明确退出会话（disconnect / retryAfterNetworkChange /
        // onCleared）时由调用方清理。
        offeredPeerId = ""
    }

    private fun handlePeerConnectionState(peerState: org.webrtc.PeerConnection.PeerConnectionState) {
        when (peerState) {
            org.webrtc.PeerConnection.PeerConnectionState.CONNECTED -> {
                _state.update {
                    val connected = if (it.connectionPath == ConnectionPath.UNKNOWN) {
                        it.copy(connectionPath = ConnectionPath.DIRECT)
                    } else {
                        it
                    }
                    connected.copy(directConnectionFailed = false)
                }
            }
            org.webrtc.PeerConnection.PeerConnectionState.FAILED -> {
                if (state.value.channelReady) {
                    handleTransientTransportDisconnect("webrtc: connection failed")
                } else {
                    recoverAfterDirectConnectionFailure("webrtc: direct connection failed")
                }
            }
            org.webrtc.PeerConnection.PeerConnectionState.DISCONNECTED,
            org.webrtc.PeerConnection.PeerConnectionState.CLOSED -> {
                if (state.value.channelReady) handleTransientTransportDisconnect("webrtc: connection $peerState")
            }
            else -> Unit
        }
    }

    private fun dispatchPeerCallback(generation: Long, callback: () -> Unit) {
        viewModelScope.launch {
            if (generation == peerGeneration) callback()
        }
    }

    private fun attachDataChannel(channel: DataChannel, generation: Long) {
        fun syncChannelState() {
            val open = channel.state() == DataChannel.State.OPEN
            log("datachannel: ${channel.state()}")
            if (open) {
                stopDirectConnectionFailureTimer()
                _state.update {
                    applyDataChannelState(resumePairingCountdown(it, System.currentTimeMillis()), true)
                        .copy(transientTransportReconnecting = false)
                }
                if (shouldOpenShareOnConnect) {
                    shouldOpenShareOnConnect = false
                    navigationEventsChannel.trySend(AppNavigationEvent.OpenShare)
                }
                flushPendingSharedUrisIfReady()
            } else if (state.value.channelReady) {
                handleTransientTransportDisconnect("datachannel: ${channel.state()}")
            } else {
                _state.update { it.copy(channelReady = false) }
            }
        }

        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() {
                dispatchPeerCallback(generation, ::syncChannelState)
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                dispatchPeerCallback(generation) {
                    val session = ensureTransfer(channel)
                    if (buffer.binary) {
                        session.handleBinary(bytes)
                    } else {
                        session.handleText(String(bytes, StandardCharsets.UTF_8))
                    }
                }
            }
        })
        transfer = ensureTransfer(channel)
        log("datachannel: attached")
        syncChannelState()
    }

    private fun ensureTransfer(channel: DataChannel): AndroidTransferSession {
        val wrappedChannel = WebRtcTransferDataChannel(channel)
        val existing = transfer
        if (existing != null && existing.isFor(wrappedChannel)) return existing
        if (existing != null) {
            existing.cleanup()
            _state.update { it.copy(localTransfers = emptyList(), peerTransfers = emptyList()) }
        }
        return AndroidTransferSession(
            channel = wrappedChannel,
            limits = state.value.joinedRoom?.limits ?: defaultTransferLimits(),
            incomingDirectory = java.io.File(getApplication<Application>().cacheDir, INCOMING_TRANSFER_DIR),
            // Sends stream file chunks; keep them off the main dispatcher and
            // tied to the viewModel lifetime (same Job, so cleanup cancels).
            sendScope = CoroutineScope(viewModelScope.coroutineContext + Dispatchers.IO),
            onLog = ::log,
            onReceivedFile = { payload ->
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching { downloadStore.save(payload) }
                        .onSuccess { savedUri ->
                            payload.sourceFile?.delete()
                            _state.update { it.copy(downloadCompleteToastId = nextDownloadCompleteToastId++) }
                            maybeShowReviewPrompt()
                            log("transfer: wrote $savedUri")
                        }
                        .onFailure { error ->
                            payload.sourceFile?.delete()
                            log("transfer: save failed ${error.message}")
                        }
                }
            },
            onLocalTransfer = { card -> onLocalTransferCompleted(card) },
            onPeerTransfer = { card -> _state.update { applyChatTransferCardUpdate(it, card, isLocal = false) } },
            onTextMessage = ::onChatText,
            onRecalled = { recall -> applyRecall(recall.target, recall.targetId) },
        ).also { transfer = it }
    }

    fun enqueueSharedUris(uris: List<Uri>) {
        if (state.value.channelReady) {
            navigationEventsChannel.trySend(AppNavigationEvent.OpenShare)
        } else {
            shouldOpenShareOnConnect = true
        }
        val uniqueUris = uris.distinct()
        if (uniqueUris.isEmpty()) return
        val app = getApplication<Application>()
        val generation = synchronized(pendingSharedUrisLock) { sharedQueueGeneration }
        viewModelScope.launch {
            val localUris = withContext(Dispatchers.IO) {
                uniqueUris.mapNotNull { uri ->
                    runCatching { app.materializeSharedUri(uri) }
                        .onFailure { log("shared: materialize failed ${it.message}") }
                        .getOrNull()
                }
            }
            val stale = synchronized(pendingSharedUrisLock) { generation != sharedQueueGeneration }
            if (stale) {
                localUris.forEach { app.deleteMaterializedSharedFile(it) }
                return@launch
            }
            if (localUris.isEmpty()) {
                log("shared: no usable files from ${uniqueUris.size} uri(s)")
                return@launch
            }
            val pendingCount = synchronized(pendingSharedUrisLock) {
                localUris.forEach { uri ->
                    if (uri !in pendingSharedUris) pendingSharedUris += uri
                    else app.deleteMaterializedSharedFile(uri)
                }
                pendingSharedUris.size
            }
            _state.update { it.copy(pendingSharedFileCount = pendingCount) }
            if (!state.value.channelReady) shouldOpenShareOnConnect = true
            log("shared: queued ${localUris.size}")
            flushPendingSharedUrisIfReady()
        }
    }

    fun sendSelectedUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val session = transfer
        if (!canOfferTransferPick(
                channelReady = state.value.channelReady,
                hasSession = session != null,
                channelOpen = session?.isChannelOpen == true,
            )
        ) {
            // 用户刚选完文件但数据通道不可用（连接在系统选择器打开期间断开是常态）。
            // 静默丢弃会让 Share 页面什么都不显示且永远不恢复；入队后由
            // pendingSharedUris 的既有机制在下次连接建立时自动发送。
            log("transfer: channel not ready, queued ${uris.size} file(s)")
            enqueueSharedUris(uris)
            return
        }
        val activeSession = session ?: return
        viewModelScope.launch(Dispatchers.IO) {
            uris.forEach { uri ->
                runCatching {
                    val app = getApplication<Application>()
                    activeSession.offerFile(app.transferContentHandle(uri))
                }.onFailure {
                    log("transfer: selected file failed ${it.message}")
                }
            }
        }
    }

    /**
     * A picked SAF folder: flattened, capped and handed to [sendSelectedUris], so it
     * takes the same path as 相册/文件 picks (offered when the channel is open, queued
     * otherwise). The empty and over-cap cases leave a notice for the panel.
     */
    fun sendFolderTree(treeUri: Uri) {
        _state.update { it.copy(folderPickNotice = null) }
        viewModelScope.launch {
            val app = getApplication<Application>()
            val pick = withContext(Dispatchers.IO) {
                runCatching { collectSafFolderFiles(app.contentResolver, treeUri) }
                    .onFailure { log("folder: unreadable tree ${it.message}") }
                    .getOrDefault(SafFolderPickResult(files = emptyList(), truncated = false))
            }
            // Files the user can no longer read are skipped, not fatal: the rest still go.
            val readable = withContext(Dispatchers.IO) {
                pick.files.filter { app.canReadFolderFile(it.uri) }
            }
            log("folder: ${readable.size} file(s), truncated=${pick.truncated}")
            val notice = folderPickNotice(readable.size, pick.truncated)
            _state.update { it.copy(folderPickNotice = notice.takeUnless { it == FolderPickNotice.NONE }) }
            sendSelectedUris(readable.map { it.uri })
        }
    }

    fun dismissFolderPickNotice() {
        _state.update { it.copy(folderPickNotice = null) }
    }

    private fun flushPendingSharedUrisIfReady() {
        if (!canOfferTransferPick(
                channelReady = state.value.channelReady,
                hasSession = transfer != null,
                channelOpen = transfer?.isChannelOpen == true,
            )
        ) {
            return
        }
        flushPendingSharedUris()
    }

    private fun flushPendingSharedUris() {
        val pending = synchronized(pendingSharedUrisLock) {
            if (pendingSharedUris.isEmpty()) return
            pendingSharedUris.toList().also { pendingSharedUris.clear() }
        }
        _state.update { it.copy(pendingSharedFileCount = 0) }
        sendSelectedUris(pending)
        log("shared: flushed ${pending.size}")
    }

    private fun cleanupPendingSharedUris() {
        val pending = synchronized(pendingSharedUrisLock) {
            sharedQueueGeneration += 1
            pendingSharedUris.toList().also { pendingSharedUris.clear() }
        }
        pending.forEach { uri ->
            if (uri.scheme == "file") uri.path?.let(::File)?.delete()
        }
        _state.update { it.copy(pendingSharedFileCount = 0) }
    }

    fun acceptIncoming(fileId: String? = null) {
        transfer?.acceptIncoming(fileId)
    }

    fun acceptIncoming(fileIds: List<String>) {
        acceptIncomingFileIds(fileIds) { acceptIncoming(it) }
    }

    fun acceptAllIncoming() {
        acceptIncoming(acceptablePeerFileIds(state.value.peerTransfers))
    }

    /** The list overview's ✕: stops the transfer and drops the card from both lists. */
    fun cancelTransfer(fileId: String) {
        transfer?.cancelTransfer(fileId)
        _state.update { removeTransferCards(it, setOf(fileId)) }
    }

    /**
     * The chat bubble's ✕ (iOS `applyKeepingCanceled`; design O3): stops the
     * transfer but keeps the bubble in place showing 已取消, so the user sees
     * what was cancelled. The session's own canceled callback also keeps the
     * bubble when it is still in the conversation.
     */
    fun cancelChatTransfer(fileId: String) {
        transfer?.cancelTransfer(fileId)
        _state.update { applyChatTransferCardUpdate(it, canceledCardFor(it, fileId), isLocal = false) }
    }

    private fun canceledCardFor(state: AppUiState, fileId: String): TransferCardState? =
        (state.localTransfers + state.peerTransfers)
            .firstOrNull { it.fileId == fileId }
            ?.copy(status = TransferCardStatus.Canceled, progressPercent = null, canCancel = false, canRetry = false)

    fun cancelLocalTransfers(fileIds: Set<String>) {
        val targetIds = scopedTransferIds(state.value.localTransfers, fileIds)
        if (targetIds.isEmpty()) return
        targetIds.forEach { transfer?.cancelTransfer(it) }
        _state.update { removeLocalTransferCards(it, targetIds) }
    }

    fun cancelTransfers(fileIds: Set<String>) {
        val targetIds = scopedTransferIds(state.value.localTransfers + state.value.peerTransfers, fileIds)
        if (targetIds.isEmpty()) return
        targetIds.forEach { transfer?.cancelTransfer(it) }
        _state.update { removeTransferCards(it, targetIds) }
    }

    fun cancelAllTransfers() {
        val fileIds = (state.value.localTransfers + state.value.peerTransfers).map { it.fileId }.toSet()
        cancelTransfers(fileIds)
    }

    fun retryTransfer(fileId: String) {
        transfer?.retry(fileId)
    }

    /**
     * Sends a typed chat line, or falls back to the web rule: a draft over the
     * text limit is offered as a `.txt` file instead of being lost.
     */
    fun sendChatText(text: String) {
        if (isTextTooLarge(text)) {
            sendOversizeTextAsFile(text)
            return
        }
        val session = transfer
        val sent = session != null && session.isChannelOpen && session.sendText(text)
        if (!sent) {
            _state.update { it.copy(chatFailure = CHAT_CHANNEL_NOT_READY) }
            log("chat: text not sent, channel not ready")
        }
    }

    /** Retracts a text message or file bubble on both sides (web `TransferSession.recall`). */
    fun recallChatEntry(target: RecallTarget, targetId: String) {
        transfer?.recall(target, targetId)
        applyRecall(target, targetId)
    }

    private fun onChatText(message: ChatMessageState) {
        _state.update { it.copy(chatMessages = it.chatMessages + message) }
    }

    private fun applyRecall(target: RecallTarget, targetId: String) {
        _state.update { markRecalled(it, target, targetId) }
    }

    private fun sendOversizeTextAsFile(text: String) {
        val handle = oversizeDraftHandle(text)
        val bytes = text.encodeToByteArray()
        val session = transfer
        if (session != null && session.isChannelOpen) {
            session.offerFile(handle)
            log("chat: oversize text offered as ${handle.name} (${bytes.size} bytes)")
            return
        }
        // No usable channel: hold the draft as a pending shared file and offer it
        // as soon as a session opens, instead of dropping what the user typed.
        val app = getApplication<Application>()
        viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) {
                runCatching { app.materializeChatDraft(bytes, handle.name) }
                    .onFailure { log("chat: oversize text draft failed ${it.message}") }
                    .getOrNull()
            }
            if (uri == null) {
                _state.update { it.copy(chatFailure = CHAT_CHANNEL_NOT_READY) }
                return@launch
            }
            enqueueSharedUris(listOf(uri))
            log("chat: oversize text queued as ${handle.name} (${bytes.size} bytes)")
        }
    }

    private fun api(): RoomApiClient = RoomApiClient(state.value.origin)

    private fun startPairingCodeAutoRefresh() {
        if (pairingRefreshJob != null) return
        pairingRefreshJob = viewModelScope.launch {
            while (true) {
                refreshExpiredLocalPairingCode(System.currentTimeMillis())
                delay(1_000L)
            }
        }
    }

    private suspend fun refreshExpiredLocalPairingCode(nowMs: Long) {
        if (!shouldAutoRefreshPairingCode(state.value, nowMs)) return
        createRoom().join()
    }

    private fun restartHeartbeat() {
        stopHeartbeat()
        heartbeatJob = viewModelScope.launch {
            while (true) {
                delay(25_000L)
                val room = state.value.joinedRoom ?: continue
                runCatching { api().heartbeat(room) }
                    .onSuccess { heartbeat ->
                        if (!isCurrentRoomSessionRequest(room.roomId, room.sessionToken, state.value.joinedRoom)) return@onSuccess
                        _state.update { current ->
                            val activeRoom = current.joinedRoom
                            if (activeRoom?.roomId == room.roomId) {
                                current.copy(joinedRoom = activeRoom.copy(sessionExpiresAt = heartbeat.sessionExpiresAt))
                            } else {
                                current
                            }
                        }
                    }
                    .onFailure { error ->
                        if (error is CancellationException) return@onFailure
                        if (!isCurrentRoomSessionRequest(room.roomId, room.sessionToken, state.value.joinedRoom)) return@onFailure
                        log("room: heartbeat failed ${error.message}")
                        handleHeartbeatFailure(error)
                    }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun startDirectConnectionFailureTimer() {
        if (directConnectionFailureJob != null) return
        if (!shouldMonitorDirectConnectionAttempt(state.value)) return
        val startedAtMs = System.currentTimeMillis()
        directConnectionFailureJob = viewModelScope.launch {
            delay(DIRECT_CONNECTION_FAILURE_TIMEOUT_MS)
            directConnectionFailureJob = null
            val nowMs = System.currentTimeMillis()
            if (!shouldFailDirectConnectionAttempt(state.value, startedAtMs, nowMs)) return@launch
            recoverAfterDirectConnectionFailure("webrtc: direct connection timed out")
        }
    }

    private fun stopDirectConnectionFailureTimer() {
        directConnectionFailureJob?.cancel()
        directConnectionFailureJob = null
    }

    private fun recoverAfterDirectConnectionFailure(message: String) {
        shouldOpenShareOnConnect = false
        stopDirectConnectionFailureTimer()
        val room = state.value.joinedRoom
        closeSignaling()
        resetPeerConnection()
        lastPeerId = ""
        offeredPeerId = ""
        stopHeartbeat()
        _state.update { resetAfterDirectConnectionFailure(it).copy(transientTransportReconnecting = false) }
        log(message)
        viewModelScope.launch {
            if (room != null) runCatching { api().close(room) }
            createRoom()
        }
    }

    private fun recoverClosedRoom() {
        shouldOpenShareOnConnect = false
        val shouldShowDirectFailureNotice = shouldShowDirectFailureAfterRemoteRoomClosed(state.value)
        closeSignaling()
        resetPeerConnection()
        stopHeartbeat()
        _state.update {
            val reset = if (shouldShowDirectFailureNotice) {
                resetAfterRemoteRoomClosed(it)
            } else {
                resetConnectionUiState(it)
            }
            reset.copy(transientTransportReconnecting = false)
        }
        createRoom()
    }

    private fun handleHeartbeatFailure(error: Throwable) {
        if (error is RoomApiException && error.code == "invalid_session") {
            recoverClosedRoom()
            return
        }
        scheduleSignalingReconnect("heartbeat failed ${error.message ?: "unknown"}")
    }

    private fun handleSignalingDisconnect(client: SignalingClient, reason: String) {
        viewModelScope.launch {
            if (signaling !== client) return@launch
            signaling = null
            _state.update { it.copy(signalingReady = false) }
            scheduleSignalingReconnect("signaling disconnected $reason")
        }
    }

    private fun handleTransientTransportDisconnect(reason: String) {
        if (state.value.joinedRoom == null) return
        lastPeerId = ""
        resetPeerConnection(clearTransfers = false)
        scheduleSignalingReconnect(reason)
    }

    private fun scheduleSignalingReconnect(reason: String) {
        val room = state.value.joinedRoom ?: return
        val nowMs = System.currentTimeMillis()
        if (signalingReconnectDeadlineMs == 0L) {
            signalingReconnectDeadlineMs = nowMs + SIGNALING_RECONNECT_WINDOW_MS
            signalingReconnectAttempt = 0
        }
        if (!shouldAttemptSignalingReconnect(nowMs, signalingReconnectDeadlineMs)) {
            log("signaling: reconnect expired")
            recoverClosedRoom()
            return
        }
        if (signalingReconnectJob != null) return
        val delayMs = signalingReconnectDelayMs(signalingReconnectAttempt++)
        log("signaling: reconnect scheduled reason=$reason delay_ms=$delayMs")
        signalingReconnectJob = viewModelScope.launch {
            delay(delayMs)
            signalingReconnectJob = null
            val activeRoom = state.value.joinedRoom
            if (activeRoom?.roomId != room.roomId) return@launch
            if (!shouldAttemptSignalingReconnect(System.currentTimeMillis(), signalingReconnectDeadlineMs)) {
                log("signaling: reconnect expired")
                recoverClosedRoom()
                return@launch
            }
            connectSignaling()
        }
    }

    private fun clearSignalingReconnect() {
        signalingReconnectJob?.cancel()
        signalingReconnectJob = null
        signalingReconnectDeadlineMs = 0L
        signalingReconnectAttempt = 0
    }

    private fun closeSignaling(clearReconnect: Boolean = true) {
        if (clearReconnect) clearSignalingReconnect()
        signaling?.close()
        signaling = null
        lastPeerId = ""
        offeredPeerId = ""
    }

    private suspend fun closeActiveRoom(room: JoinedRoom?, reason: String) {
        if (room == null) return
        runCatching { api().close(room) }
            .onSuccess { log("room: close sent reason=$reason room=${room.roomId}") }
            .onFailure { log("room: close failed reason=$reason room=${room.roomId} error=${it.message}") }
    }

    override fun onCleared() {
        closeSignaling()
        cleanupPendingSharedUris()
        resetPeerConnection()
        stopDirectConnectionFailureTimer()
        stopHeartbeat()
        clearSignalingReconnect()
        cancelCreateRetry()
        pairingRefreshJob?.cancel()
        pairingRefreshJob = null
    }

    private fun log(message: String) {
        Log.d(LOG_TAG, message)
        _state.update { it.copy(logs = (it.logs + message).takeLast(MAX_LOG_ENTRIES)) }
    }

    /**
     * Releases the shared-file queue on demand (the conversation's "send all").
     * Same guard as the automatic flush: with no usable channel the queue is
     * kept and goes out on reconnect instead of being lost.
     */
    fun sendQueuedSharedFiles() {
        flushPendingSharedUrisIfReady()
    }
}

private const val LOG_TAG = "LanShare"

/**
 * Delays between automatic room-create retries, mirroring the iOS client. The
 * first one is quick because the usual cause is a network path that is not up
 * yet; the ladder covers roughly the first minute after launch.
 */
internal val CREATE_RETRY_DELAYS_MS = longArrayOf(
    1_000, 2_000, 3_000,
    5_000, 5_000, 5_000, 5_000, 5_000, 5_000, 5_000, 5_000, 5_000,
)

/**
 * Minimum gap between create attempts triggered by regaining the foreground, so
 * quickly switching apps cannot hammer the server.
 */
internal const val CREATE_RETRY_FOREGROUND_MIN_GAP_MS = 2_000L

/** Delay before the next automatic create retry, or null once they are used up. */
internal fun createRetryDelayMs(attempt: Int): Long? = CREATE_RETRY_DELAYS_MS.getOrNull(attempt)

/** Regaining the foreground only retries when a code is still missing. */
internal fun shouldRetryCreateOnForeground(
    createInFlight: Boolean,
    createdCode: String?,
    joinedRoom: JoinedRoom?,
    lastCreateAttemptMs: Long,
    nowMs: Long,
): Boolean =
    !createInFlight &&
        createdCode == null &&
        joinedRoom == null &&
        nowMs - lastCreateAttemptMs >= CREATE_RETRY_FOREGROUND_MIN_GAP_MS

fun shouldAutoCreateOfferForPeer(
    peerId: String,
    offeredPeerId: String,
    role: String,
    transientTransportReconnecting: Boolean = false,
): Boolean =
    role == "initiator" &&
        peerId.isNotBlank() &&
        peerId != offeredPeerId &&
        !transientTransportReconnecting

fun peerLabelFromSignalPayload(payload: JsonElement?): String =
    runCatching {
        payload
            ?.jsonObject
            ?.get("deviceLabel")
            ?.jsonPrimitive
            ?.content
            .orEmpty()
    }.getOrDefault("")

private val supportedAndroidLocales = setOf(
    "en", "zh-CN", "zh-TW", "es", "fr", "de", "ja", "ko", "pt-BR", "hi", "ar", "id", "vi", "th",
)

internal fun initialAppUiState(
    persistedLocale: String?,
    persistedDarkTheme: Boolean = false,
): AppUiState {
    val manualLocale = persistedLocale?.takeIf { it.isNotBlank() }
    return AppUiState(
        locale = manualLocale ?: defaultAndroidLocale(),
        localeManuallySelected = manualLocale != null,
        darkTheme = persistedDarkTheme,
    )
}

private fun defaultAndroidLocale(): String =
    androidLocaleFromLanguageTag(Locale.getDefault().toLanguageTag())

internal fun androidLocaleFromLanguageTag(localeTag: String): String {
    val locale = Locale.forLanguageTag(localeTag)
    return when (localeTag) {
        "zh-Hans", "zh-CN", "zh-SG" -> "zh-CN"
        "zh-Hant", "zh-TW", "zh-HK", "zh-MO" -> "zh-TW"
        "pt-BR" -> "pt-BR"
        else -> locale.language.takeIf { it in supportedAndroidLocales } ?: ""
    }
}

private fun localeWithFallback(defaultLocale: String): String {
    val systemLocale = defaultAndroidLocale()
    return when {
        systemLocale.isNotBlank() -> systemLocale
        defaultLocale in supportedAndroidLocales -> defaultLocale
        else -> "en"
    }
}

internal fun android.content.ContentResolver.displayName(uri: Uri): String {
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) {
            val name = cursor.getString(index)
            if (!name.isNullOrBlank()) return name
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "android-file"
}

internal fun resolveSharedContentSize(
    openableSize: Long?,
    assetFileDescriptorLength: Long?,
    streamByteCount: (() -> Long)?,
): Long {
    if (openableSize != null && openableSize >= 0) return openableSize
    if (assetFileDescriptorLength != null && assetFileDescriptorLength >= 0) return assetFileDescriptorLength
    val counted = streamByteCount?.invoke()
    if (counted != null && counted >= 0) return counted
    error("Could not determine file size.")
}

internal fun countInputStreamBytes(input: java.io.InputStream): Long {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read <= 0) break
        total += read
    }
    return total
}

private fun android.content.ContentResolver.queryOpenableSize(uri: Uri): Long? {
    query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
            return cursor.getLong(index).takeIf { it >= 0 }
        }
    }
    return null
}

private fun android.content.ContentResolver.contentSize(uri: Uri): Long {
    val fileLength = uri.localFileLength()
    return resolveSharedContentSize(
        openableSize = queryOpenableSize(uri) ?: fileLength,
        assetFileDescriptorLength = openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.length.takeIf { it >= 0 }
        },
        streamByteCount = {
            openInputStream(uri)?.use(::countInputStreamBytes)
                ?: error("Could not open file.")
        },
    )
}

private fun Uri.localFileLength(): Long? {
    if (scheme != "file") return null
    val path = path ?: return null
    val file = java.io.File(path)
    if (!file.isFile) return null
    return file.length().takeIf { it >= 0 }
}

private fun Application.materializeSharedUri(uri: Uri): Uri {
    val resolver = contentResolver
    val displayName = sanitizeFileName(resolver.displayName(uri))
    val dir = java.io.File(cacheDir, SHARED_INCOMING_DIR).apply { mkdirs() }
    val target = java.io.File(dir, "${UUID.randomUUID()}_$displayName")
    resolver.openInputStream(uri)?.use { input ->
        target.outputStream().use { output -> input.copyTo(output) }
    } ?: error("Could not open shared file.")
    return Uri.fromFile(target)
}

/** Writes an oversize chat draft into the pending-shared queue so it survives until the channel opens. */
internal fun Application.materializeChatDraft(bytes: ByteArray, name: String): Uri {
    val dir = java.io.File(cacheDir, SHARED_INCOMING_DIR).apply { mkdirs() }
    val target = java.io.File(dir, "${UUID.randomUUID()}_${sanitizeFileName(name)}")
    target.outputStream().use { it.write(bytes) }
    return Uri.fromFile(target)
}

/** Whether the picked document still opens; a revoked or broken entry is dropped from the selection. */
private fun Application.canReadFolderFile(uri: Uri): Boolean =
    runCatching { contentResolver.openInputStream(uri)?.use { true } == true }.getOrDefault(false)

private fun Application.transferContentHandle(uri: Uri): TransferContentHandle {
    val app = this
    val resolver = contentResolver
    val name = resolver.displayName(uri)
    val mime = resolver.getType(uri) ?: "application/octet-stream"

    val size = resolver.contentSize(uri)
    return object : TransferContentHandle {
        override val name: String = name
        override val size: Long = size
        override val mime: String = mime

        override fun openStream() =
            resolver.openInputStream(uri) ?: error("Could not open file.")

        override fun cleanup() {
            app.deleteMaterializedSharedFile(uri)
        }
    }
}

private fun Application.deleteMaterializedSharedFile(uri: Uri) {
    if (uri.scheme != "file") return
    val file = uri.path?.let(::File) ?: return
    val sharedDirectory = cacheDir.resolve(SHARED_INCOMING_DIR).canonicalFile
    val candidate = runCatching { file.canonicalFile }.getOrNull() ?: return
    if (candidate.toPath().startsWith(sharedDirectory.toPath())) {
        candidate.delete()
    }
}

private const val SHARED_INCOMING_DIR = "shared-incoming"
private const val INCOMING_TRANSFER_DIR = "incoming-transfers"
private const val DEFAULT_BUFFER_SIZE = 64 * 1024
private const val MAX_LOG_ENTRIES = 200

/** Failure code for a chat line that could not be sent; the UI owns the localized wording. */
internal const val CHAT_CHANNEL_NOT_READY = "channel_not_ready"

/** Same shape as the web stamp: `new Date().toISOString().slice(0, 19).replace(/[T:]/g, '-')`. */
internal val CHAT_DRAFT_TIMESTAMP_PATTERN = Regex("""\d{4}-\d{2}-\d{2}-\d{2}-\d{2}-\d{2}""")

/** Always UTC: the web stamps with `toISOString()`, so both sides name the same draft file alike. */
internal fun formatChatDraftTimestamp(atMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date(atMs))

/** The oversize draft as the file the web offers: `message-<stamp>.txt`, `text/plain`. */
internal fun oversizeDraftHandle(
    text: String,
    stamp: String = formatChatDraftTimestamp(System.currentTimeMillis()),
): TransferContentHandle = ByteArrayTransferContentHandle(
    name = "message-$stamp.txt",
    bytes = text.encodeToByteArray(),
    mime = "text/plain",
)

private fun cleanupTemporaryFiles(application: Application) {
    listOf(SHARED_INCOMING_DIR, INCOMING_TRANSFER_DIR).forEach { name ->
        application.cacheDir.resolve(name).deleteRecursively()
    }
}

private fun defaultServerOrigin(): String = preferredServerOriginForDevice(
    defaultOrigin = BuildConfig.DEFAULT_SERVER_ORIGIN,
)

fun preferredServerOriginForDevice(
    defaultOrigin: String,
): String = defaultOrigin
