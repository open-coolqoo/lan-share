package com.lanshare

import java.util.Locale

private const val PAIRING_TTL_SECONDS = 120L

/**
 * Consecutive room-create failures before the failure dialog pops. One failed
 * attempt happens on ordinary network blips, so the notice waits for the first
 * automatic retry to fail too.
 */
internal const val ROOM_CREATE_FAILURE_NOTICE_AFTER_FAILURES = 2

data class WebParitySection(
    val key: String,
    val title: String,
)

data class PairingState(
    val localCode: String?,
    val localCodeStatus: String,
    val canRefreshCode: Boolean,
    val peerCode: String,
    val canJoinPeerCode: Boolean,
    val ttlLabel: String,
    val copyLabel: String,
    val validation: PeerCodeValidation,
)

data class ConnectionState(
    val summary: String,
    val signalingLabel: String,
    val channelLabel: String,
    val diagnostics: List<String>,
)

data class TransferCardState(
    val fileId: String,
    val title: String,
    val detail: String,
    val status: String,
    val progressPercent: Int?,
    val actionLabel: String?,
    val canCancel: Boolean,
    val canRetry: Boolean,
    val sizeBytes: Long? = null,
    val totalChunks: Int? = null,
    /** When the bubble joined the conversation, so text and files share one timeline. */
    val atMs: Long = 0L,
    /** Set when the sender retracted the file; the bubble becomes a system note in place. */
    val recalled: Boolean = false,
)

/** One chat entry, mirroring the web `PeerMessageItem`. */
data class ChatMessageState(
    val id: String,
    val outgoing: Boolean,
    val text: String,
    val atMs: Long,
    val recalled: Boolean = false,
)

data class TransferListState(
    val emptyText: String,
    val cards: List<TransferCardState>,
    val canAcceptIncoming: Boolean = false,
)

data class PublicPageLinkState(
    val label: String,
    val slug: String,
)

data class PublicPagesState(
    val locale: String,
    val links: List<PublicPageLinkState>,
    val selectedTitle: String?,
    val selectedBodyPreview: String?,
    val error: String,
)

data class PairedSummaryState(
    val code: String,
    val localDevice: String,
    val peerDevice: String,
    val canDisconnect: Boolean,
)

/**
 * Everything the 连接详情 sheet shows (design §2 frame 13): this session's
 * code, the two device labels, the connection mode and the diagnostics. Kept
 * pure so the sheet's contents are testable without a device; the composable
 * only renders it and adds the copy and the two actions.
 */
data class ConnectionDetailsState(
    val code: String?,
    val localDevice: String,
    val peerDevice: String,
    val connectionPath: ConnectionPath,
    val diagnostics: List<String>,
    val canRefresh: Boolean,
    val canDisconnect: Boolean,
)

enum class ConnectionPath(val label: String) {
    DIRECT("Direct"),
    UNKNOWN("Unknown"),
}

data class DirectFailureState(
    val visible: Boolean,
    val guidance: List<String>,
)

data class FooterState(
    val legalName: String,
    val copyright: String,
    val developer: String?,
    val contact: String?,
    val links: List<PublicPageLinkState>,
)

enum class PeerCodeValidation {
    OK,
    INVALID,
    SELF,
    REFRESHED,
}

object TransferCardStatus {
    const val WaitingForWebAcceptance = "waiting_for_web_acceptance"
    const val ReceivingFromWeb = "receiving_from_web"
    const val Canceled = "canceled"
    const val ReadyToAccept = "ready_to_accept"
    const val SentToWeb = "sent_to_web"
    const val SavedOnAndroid = "saved_on_android"
    const val Transferring = "transferring"
    const val RejectedPrefix = "rejected:"
    const val ErrorPrefix = "error:"
}

object TransferCardAction {
    const val Retry = "retry"
    const val Accept = "accept"
    const val SendAgain = "send_again"
}

fun webParitySections(state: AppUiState): List<WebParitySection> = listOf(
    WebParitySection("brand", state.siteConfig?.siteName ?: "Lan-Share"),
    WebParitySection("pairing", "Pair devices"),
    WebParitySection("connection", "Connection"),
    WebParitySection("local_files", "Files on this device"),
    WebParitySection("peer_files", "Files from peer"),
    WebParitySection("public_pages", "Public pages"),
    WebParitySection("diagnostics", "Diagnostics"),
)

fun pairingState(state: AppUiState, nowMs: Long = System.currentTimeMillis()): PairingState {
    val code = state.createdCode
    val validation = validatePeerCode(state.roomCode, state)
    val effectiveNowMs = state.pairingCountdownPausedAtMs ?: nowMs
    return PairingState(
        localCode = code,
        localCodeStatus = if (code == null) "Create a code for the Web peer." else "Ready to share with a Web peer.",
        canRefreshCode = code != null,
        peerCode = state.roomCode,
        canJoinPeerCode = validation == PeerCodeValidation.OK,
        ttlLabel = ttlLabel(state.pairingExpiresAtMs, effectiveNowMs),
        copyLabel = if (state.codeCopied) "Copied" else "Copy",
        validation = validation,
    )
}

fun peerConnectionInProgress(state: AppUiState): Boolean =
    state.joiningRoom || (state.joinedRoom?.role == "peer" && !state.channelReady && !state.directConnectionFailed)

fun localConnectionWaitingForPeer(state: AppUiState): Boolean =
    state.joinedRoom?.role == "initiator" && !state.channelReady

fun connectionActionsLocked(state: AppUiState): Boolean {
    if (state.channelReady) return false
    if (state.directConnectionFailed) return false
    if (state.transientTransportReconnecting) return false
    if (state.joiningRoom) return true
    if (state.joinedRoom?.role == "peer") return true
    return state.joinedRoom?.role == "initiator" && state.peerDeviceLabel.isNotBlank()
}

fun directFailureState(state: AppUiState): DirectFailureState = DirectFailureState(
    visible = state.directConnectionFailureNotice,
    guidance = listOf(
        "Keep both devices on the same Wi-Fi or LAN.",
        "Turn off VPN or proxy on both devices.",
        "Disable guest network, AP isolation, or firewall rules that block peer connections.",
        "If the network still blocks direct pairing, connect both devices to the same phone hotspot and create a new code.",
    ),
)

const val DIRECT_CONNECTION_FAILURE_TIMEOUT_MS = 30_000L
const val SIGNALING_RECONNECT_WINDOW_MS = 5 * 60_000L
private const val SIGNALING_RECONNECT_BASE_DELAY_MS = 1_000L
private const val SIGNALING_RECONNECT_MAX_DELAY_MS = 10_000L

fun signalingReconnectDelayMs(attempt: Int): Long {
    val boundedAttempt = attempt.coerceIn(0, 30)
    return (SIGNALING_RECONNECT_BASE_DELAY_MS * (1L shl boundedAttempt))
        .coerceAtMost(SIGNALING_RECONNECT_MAX_DELAY_MS)
}

fun shouldAttemptSignalingReconnect(nowMs: Long, deadlineMs: Long): Boolean = nowMs <= deadlineMs

fun shouldMonitorDirectConnectionAttempt(state: AppUiState): Boolean {
    val role = state.joinedRoom?.role ?: return false
    if (state.channelReady || state.directConnectionFailed) return false
    return role == "peer" || state.peerDeviceLabel.isNotBlank()
}

fun shouldFailDirectConnectionAttempt(
    state: AppUiState,
    startedAtMs: Long,
    nowMs: Long,
    timeoutMs: Long = DIRECT_CONNECTION_FAILURE_TIMEOUT_MS,
): Boolean {
    if (!shouldMonitorDirectConnectionAttempt(state)) return false
    return nowMs - startedAtMs >= timeoutMs
}

fun shouldShowDirectFailureAfterRemoteRoomClosed(state: AppUiState): Boolean =
    shouldMonitorDirectConnectionAttempt(state)

fun applyDataChannelState(state: AppUiState, isOpen: Boolean): AppUiState =
    if (isOpen) {
        state.copy(
            // The channel is usable again, so a stale "not connected" banner must go.
            chatFailure = "",
            channelReady = true,
            roomCode = "",
            roomError = "",
            directConnectionFailed = false,
            directConnectionFailureNotice = false,
            connectionPath = if (state.connectionPath == ConnectionPath.UNKNOWN) {
                ConnectionPath.DIRECT
            } else {
                state.connectionPath
            },
        )
    } else {
        resetConnectionUiState(state)
    }

fun resetConnectionUiState(state: AppUiState): AppUiState =
    state.copy(
        // A failed send is about the connection that just ended; the next session starts clean.
        chatFailure = "",
        roomCode = "",
        connectedPeerCode = null,
        joiningRoom = false,
        roomError = "",
        joinedRoom = null,
        peerDeviceLabel = "",
        localTransfers = emptyList(),
        peerTransfers = emptyList(),
        chatMessages = emptyList(),
        connectionPath = ConnectionPath.UNKNOWN,
        directConnectionFailed = false,
        directConnectionFailureNotice = false,
        signalingReady = false,
        channelReady = false,
        pairingCountdownPausedAtMs = null,
    )

fun resetAfterDirectConnectionFailure(state: AppUiState): AppUiState =
    resetConnectionUiState(state).copy(directConnectionFailureNotice = true)

fun resetAfterNetworkChange(state: AppUiState): AppUiState =
    resetConnectionUiState(state).copy(
        directConnectionFailed = false,
        directConnectionFailureNotice = false,
        transientTransportReconnecting = false,
    )

fun resetAfterRemoteRoomClosed(state: AppUiState): AppUiState =
    if (shouldShowDirectFailureAfterRemoteRoomClosed(state)) {
        resetAfterDirectConnectionFailure(state)
    } else {
        resetConnectionUiState(state)
    }

fun resetFailedPeerJoinState(state: AppUiState, message: String): AppUiState =
    resetConnectionUiState(state).copy(roomError = message)

fun connectionState(state: AppUiState): ConnectionState {
    val summary = when {
        state.channelReady -> "Direct DataChannel ready."
        state.signalingReady -> "Signaling authenticated. Waiting for DataChannel."
        state.joinedRoom != null -> "Room joined. Connect signaling."
        else -> "Room not connected."
    }
    return ConnectionState(
        summary = summary,
        signalingLabel = if (state.signalingReady) "Authenticated" else "Not connected",
        channelLabel = if (state.channelReady) "Open" else "Not ready",
        diagnostics = listOf(
            "Origin: ${state.origin}",
            "Room: ${state.joinedRoom?.roomId ?: "-"}",
            "Role: ${state.joinedRoom?.role ?: "-"}",
            "Peer count: ${state.joinedRoom?.peerCount ?: 0}",
            "Path: ${state.connectionPath.label}",
            "Failure: ${if (state.directConnectionFailed) "Direct connection failed" else "-"}",
        ),
    )
}

fun localFilesState(state: AppUiState): TransferListState = TransferListState(
    emptyText = "No local file offered yet.",
    cards = state.localTransfers,
)

fun peerFilesState(state: AppUiState): TransferListState = TransferListState(
    emptyText = "No peer file offer yet.",
    cards = state.peerTransfers,
    canAcceptIncoming = state.peerTransfers.any { it.actionLabel == TransferCardAction.Accept },
)

fun acceptablePeerFileIds(cards: List<TransferCardState>): List<String> =
    cards.filter { it.actionLabel == TransferCardAction.Accept }.map { it.fileId }

/**
 * Whether a freshly picked file can be offered on the current DataChannel right
 * now. Anything else (no session yet, pairing lost, or a channel that only
 * looks ready) must be queued via the pending shared files flow instead of
 * being dropped — the system pickers keep the app in the background, so the
 * connection routinely dies between tapping 文件/图库 and the pick returning.
 */
fun canOfferTransferPick(channelReady: Boolean, hasSession: Boolean, channelOpen: Boolean): Boolean =
    channelReady && hasSession && channelOpen

fun acceptIncomingFileIds(fileIds: List<String>, accept: (String) -> Unit) {
    fileIds.distinct().forEach(accept)
}

fun applyTransferCardUpdate(
    state: AppUiState,
    card: TransferCardState?,
    isLocal: Boolean,
): AppUiState {
    card ?: return state
    if (card.status == TransferCardStatus.Canceled) return removeTransferCards(state, setOf(card.fileId))
    return if (isLocal) {
        state.copy(localTransfers = state.localTransfers.upsertChatCard(card))
    } else {
        state.copy(peerTransfers = state.peerTransfers.upsertChatCard(card))
    }
}

/**
 * The chat variant of [applyTransferCardUpdate] (iOS `applyKeepingCanceled`):
 * a canceled status marks the existing bubble 已取消 in place instead of
 * deleting it, so the user can see what was stopped. A card the list does not
 * hold yet is not added — cancelling never conjures an entry.
 */
fun applyChatTransferCardUpdate(
    state: AppUiState,
    card: TransferCardState?,
    isLocal: Boolean,
): AppUiState {
    card ?: return state
    if (card.status != TransferCardStatus.Canceled) return applyTransferCardUpdate(state, card, isLocal)
    val cards = if (isLocal) state.localTransfers else state.peerTransfers
    if (cards.none { it.fileId == card.fileId }) return state
    // Drop any retry affordance the canceled card carries: the bubble reads
    // 已取消 with no action, so a retry cannot resurrect it from the chat.
    val kept = card.copy(canCancel = false, canRetry = false)
    return if (isLocal) {
        state.copy(localTransfers = state.localTransfers.upsertChatCard(kept))
    } else {
        state.copy(peerTransfers = state.peerTransfers.upsertChatCard(kept))
    }
}

/**
 * Upserts a card without letting a status update move its position in the
 * conversation: the entry keeps the `atMs` and `recalled` it was created with,
 * mirroring the web, where status changes only mutate the existing item.
 */
private fun List<TransferCardState>.upsertChatCard(card: TransferCardState): List<TransferCardState> {
    val index = indexOfFirst { it.fileId == card.fileId }
    if (index < 0) return this + card
    val existing = this[index]
    val renewed = card.copy(atMs = existing.atMs, recalled = existing.recalled)
    return mapIndexed { i, current -> if (i == index) renewed else current }
}

/** Marks the matching text message or file bubble as recalled; every other entry is untouched. */
fun markRecalled(state: AppUiState, target: RecallTarget, targetId: String): AppUiState = when (target) {
    RecallTarget.TEXT -> state.copy(
        chatMessages = state.chatMessages.map { if (it.id == targetId) it.copy(recalled = true) else it },
    )
    RecallTarget.FILE -> state.copy(
        localTransfers = state.localTransfers.map { if (it.fileId == targetId) it.copy(recalled = true) else it },
        peerTransfers = state.peerTransfers.map { if (it.fileId == targetId) it.copy(recalled = true) else it },
    )
}

enum class FileBubbleAction {
    ACCEPT,
    OPEN_DOWNLOADS,
    RECALL,
    /** Aborts an incoming transfer in flight and keeps the bubble in place as 已取消. */
    CANCEL,
}

/**
 * At most one action per bubble (design §4): an incoming offer is accepted, a
 * received file opens Downloads, an in-flight incoming file can be canceled,
 * an in-flight outgoing file can be recalled, and every settled state leaves
 * the bubble read-only. Mirrors the web (chat bubbles pass `onCancel` only for
 * incoming files) and the iOS `chatFileAction`.
 */
fun fileBubbleAction(card: TransferCardState, incoming: Boolean): FileBubbleAction? = when {
    card.recalled -> null
    card.status == TransferCardStatus.ReadyToAccept -> if (incoming) FileBubbleAction.ACCEPT else null
    card.status == TransferCardStatus.SavedOnAndroid -> if (incoming) FileBubbleAction.OPEN_DOWNLOADS else null
    card.status == TransferCardStatus.Transferring -> if (incoming) FileBubbleAction.CANCEL else FileBubbleAction.RECALL
    card.status == TransferCardStatus.ReceivingFromWeb -> if (incoming) FileBubbleAction.CANCEL else null
    else -> null
}

/**
 * A short avatar label, an exact port of the web `deviceBadge`: "PC Chrome" ->
 * "PC", "Chrome" -> "CH", "手机" -> "手", blank -> "?".
 */
fun deviceBadge(label: String): String {
    val words = label.trim().split(Regex("[\\s_-]+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return "?"
    val ascii = words.filter { word -> word.all { it.code in 0x20..0x7e } }
    if (ascii.size >= 2) return "${ascii[0][0]}${ascii[1][0]}".uppercase()
    if (ascii.size == 1) return takeGlyphs(ascii[0], 2).uppercase()
    return takeGlyphs(words[0], 1)
}

/** First [count] glyphs (code points, not UTF-16 units), so an emoji or CJK head survives intact. */
private fun takeGlyphs(value: String, count: Int): String =
    value.codePoints().limit(count.toLong()).collect({ StringBuilder() }, { sb, cp -> sb.appendCodePoint(cp) }, StringBuilder::append).toString()

/**
 * One chronological conversation: text messages and file bubbles share a single
 * ordered list, so a message sent after a file never lands above that file.
 * Recalled entries stay in place as a system note. Equal timestamps keep the
 * production order: text, then local files, then peer files, each in list order.
 */
sealed interface ChatTimelineEntry {
    val key: String
    val atMs: Long

    data class Text(val message: ChatMessageState) : ChatTimelineEntry {
        override val key: String get() = "text-${message.id}"
        override val atMs: Long get() = message.atMs
    }

    data class File(
        val card: TransferCardState,
        val incoming: Boolean,
    ) : ChatTimelineEntry {
        override val key: String get() = "${if (incoming) "remote" else "local"}-${card.fileId}"
        override val atMs: Long get() = card.atMs
    }

    data class Recalled(
        val sourceKey: String,
        override val atMs: Long,
        val byMe: Boolean,
    ) : ChatTimelineEntry {
        override val key: String get() = "recalled-$sourceKey"
    }
}

fun chatTimeline(
    messages: List<ChatMessageState>,
    localTransfers: List<TransferCardState>,
    peerTransfers: List<TransferCardState>,
): List<ChatTimelineEntry> {
    val entries = mutableListOf<ChatTimelineEntry>()
    fun pushText(message: ChatMessageState) {
        entries += if (message.recalled) {
            ChatTimelineEntry.Recalled("text-${message.id}", message.atMs, byMe = message.outgoing)
        } else {
            ChatTimelineEntry.Text(message)
        }
    }
    fun pushFile(card: TransferCardState, incoming: Boolean) {
        entries += if (card.recalled) {
            ChatTimelineEntry.Recalled("file-${card.fileId}", card.atMs, byMe = !incoming)
        } else {
            ChatTimelineEntry.File(card, incoming)
        }
    }
    messages.forEach(::pushText)
    localTransfers.forEach { pushFile(it, incoming = false) }
    peerTransfers.forEach { pushFile(it, incoming = true) }
    // Stable sort: the web's Array.sort is stable too, so equal timestamps keep this order.
    return entries.sortedWith(compareBy { it.atMs })
}

/**
 * The peer name sits above a peer bubble only at the start of a run (design frame
 * 02, web `App.tsx:1724-1734`): shown when there is no previous entry, when the
 * previous entry is one of ours, and when the previous entry is a recall note —
 * a note is a break, so the name comes back after it.
 */
fun chatSenderLabel(entry: ChatTimelineEntry, previous: ChatTimelineEntry?): String? {
    val incoming = when (entry) {
        is ChatTimelineEntry.Text -> !entry.message.outgoing
        is ChatTimelineEntry.File -> entry.incoming
        is ChatTimelineEntry.Recalled -> return null
    }
    if (!incoming) return null
    val previousIncoming = when (previous) {
        is ChatTimelineEntry.Text -> !previous.message.outgoing
        is ChatTimelineEntry.File -> previous.incoming
        is ChatTimelineEntry.Recalled, null -> null
    }
    return if (previousIncoming == true) null else PEER_NAME_LABEL
}

/** The stream substitutes this placeholder with the peer's device label. */
const val PEER_NAME_LABEL = "\u0000peer"

/** Everything below the conversation stream that changes its scroll height. */
fun chatStreamTailNoteKeys(pendingSharedFileCount: Int, chatFailure: String, channelReady: Boolean): List<String> =
    buildList {
        if (pendingSharedFileCount > 0) add("chat-pending")
        if (chatFailure.isNotBlank()) add("chat-failure")
        if (!channelReady) add("chat-session-note")
    }

fun shouldShowCompactTransferProgress(card: TransferCardState, requested: Boolean): Boolean =
    card.status == TransferCardStatus.Transferring ||
        card.status == TransferCardStatus.ReceivingFromWeb ||
        (requested && !card.hasSettledTransferStatus())

fun syncRequestedDownloadIds(
    requestedIds: Set<String>,
    cards: List<TransferCardState>,
): Set<String> {
    val activePresentIds = cards.filterNot { it.hasSettledTransferStatus() }.mapTo(mutableSetOf()) { it.fileId }
    return requestedIds.filterTo(mutableSetOf()) { it in activePresentIds }
}

fun scopedTransferIds(cards: List<TransferCardState>, fileIds: Set<String>): Set<String> =
    cards.mapTo(mutableSetOf()) { it.fileId }.intersect(fileIds)

fun removeLocalTransferCards(state: AppUiState, fileIds: Set<String>): AppUiState {
    if (fileIds.isEmpty()) return state
    return state.copy(localTransfers = state.localTransfers.filterNot { it.fileId in fileIds })
}

fun removeTransferCards(state: AppUiState, fileIds: Set<String>): AppUiState {
    if (fileIds.isEmpty()) return state
    return state.copy(
        localTransfers = state.localTransfers.filterNot { it.fileId in fileIds },
        peerTransfers = state.peerTransfers.filterNot { it.fileId in fileIds },
    )
}

private fun TransferCardState.hasSettledTransferStatus(): Boolean =
    status == TransferCardStatus.SentToWeb ||
        status == TransferCardStatus.SavedOnAndroid ||
        status == TransferCardStatus.Canceled ||
        status.startsWith(TransferCardStatus.RejectedPrefix) ||
        status.startsWith(TransferCardStatus.ErrorPrefix)

fun publicPagesState(state: AppUiState): PublicPagesState {
    val config = state.siteConfig
    val links = config?.footer?.links.orEmpty().mapNotNull { link ->
        publicPageSlugFromUrl(link.url)
            ?.takeUnless { isContactSlug(it) }
            ?.let { PublicPageLinkState(label = link.label, slug = it) }
    }
    return PublicPagesState(
        locale = config?.defaultLocale ?: "en",
        links = links,
        selectedTitle = state.selectedPage?.title,
        selectedBodyPreview = state.selectedPage?.body?.take(220),
        error = state.pageError,
    )
}

fun pairedSummaryState(state: AppUiState): PairedSummaryState? {
    val code = connectedPairingCode(state) ?: return null
    if (!state.channelReady && !state.transientTransportReconnecting) return null
    return PairedSummaryState(
        code = code,
        localDevice = state.localDeviceLabel,
        peerDevice = state.peerDeviceLabel.ifBlank { "Peer browser" },
        canDisconnect = state.localTransfers.none { it.status == TransferCardStatus.Transferring } && state.peerTransfers.none { it.status == TransferCardStatus.Transferring },
    )
}

/**
 * The sheet's contents. This session's code is the existing paired-code state,
 * so a session without one has nothing to show and the sheet is not offered.
 * The code is refreshed exactly as the home page does it (stand down the
 * pairing of the current room, show a fresh one); during a transient reconnect
 * there is no live channel to swap it on, so both actions wait for it.
 * [ConnectionPath] only knows DIRECT/UNKNOWN today, so the sheet shows those
 * two real states and never invents a relay.
 */
fun connectionDetailsState(state: AppUiState): ConnectionDetailsState {
    val summary = pairedSummaryState(state)
    return ConnectionDetailsState(
        code = summary?.code,
        localDevice = state.localDeviceLabel,
        peerDevice = summary?.peerDevice ?: state.peerDeviceLabel,
        connectionPath = state.connectionPath,
        diagnostics = connectionState(state).diagnostics,
        canRefresh = state.channelReady && summary != null,
        canDisconnect = summary?.canDisconnect == true || !state.channelReady,
    )
}

/**
 * The top bar opens the sheet only while the conversation is actually on
 * screen and there is a session code to show; the ‹ affordance beside it stays
 * a separate 48dp target (design §4).
 */
fun chatConnectionDetailsAvailable(state: AppUiState, conversationVisible: Boolean): Boolean =
    conversationVisible && connectionDetailsState(state).code != null

fun connectedPairingCode(state: AppUiState): String? {
    val peerCode = state.roomCode.takeIf { it.length == 8 }
    return if (state.joinedRoom?.role == "peer") {
        state.connectedPeerCode ?: peerCode ?: state.createdCode
    } else {
        state.createdCode ?: state.connectedPeerCode ?: peerCode
    }
}

fun footerState(state: AppUiState): FooterState {
    val config = state.siteConfig
    val footer = config?.footer
    return FooterState(
        legalName = footer?.legalName?.takeIf { it.isNotBlank() } ?: config?.siteName ?: "Lan-Share",
        copyright = footer?.copyright?.takeIf { it.isNotBlank() } ?: "Copyright",
        developer = footer?.developerName?.takeIf { it.isNotBlank() }?.let { "Developer: $it" },
        contact = footer?.contactEmail?.takeIf { it.isNotBlank() }?.let { "Contact: $it" },
        links = footer?.links.orEmpty().mapNotNull { link ->
            publicPageSlugFromUrl(link.url)
                ?.takeUnless { isContactSlug(it) }
                ?.let { PublicPageLinkState(link.label, it) }
        },
    )
}

fun validatePeerCode(peerCode: String, state: AppUiState): PeerCodeValidation = when {
    !Regex("""^\d{8}$""").matches(peerCode) -> PeerCodeValidation.INVALID
    peerCode == state.createdCode -> PeerCodeValidation.SELF
    state.invalidatedCodes.contains(peerCode) -> PeerCodeValidation.REFRESHED
    else -> PeerCodeValidation.OK
}

fun shouldAutoRefreshPairingCode(state: AppUiState, nowMs: Long): Boolean =
    state.createdCode != null &&
        state.pairingExpiresAtMs != null &&
        !state.channelReady &&
        state.joinedRoom?.role != "peer" &&
        !connectionActionsLocked(state) &&
        nowMs >= state.pairingExpiresAtMs

fun pairingDisplayExpiresAtMs(nowMs: Long): Long =
    nowMs + PAIRING_TTL_SECONDS * 1000

fun pausePairingCountdown(state: AppUiState, nowMs: Long): AppUiState {
    if (!connectionActionsLocked(state) || state.pairingCountdownPausedAtMs != null) return state
    return state.copy(pairingCountdownPausedAtMs = nowMs)
}

fun resumePairingCountdown(state: AppUiState, nowMs: Long): AppUiState {
    val pausedAtMs = state.pairingCountdownPausedAtMs ?: return state
    val pausedDurationMs = (nowMs - pausedAtMs).coerceAtLeast(0)
    return state.copy(
        pairingExpiresAtMs = state.pairingExpiresAtMs?.plus(pausedDurationMs),
        pairingCountdownPausedAtMs = null,
    )
}

/**
 * Records a failed room create. Only an episode that leaves the home page with
 * no local code may alert the user: once, two failures in, and never again
 * after they dismiss it. A failed refresh keeps the old code on screen and
 * stays silent.
 */
internal fun markRoomCreateFailure(state: AppUiState): AppUiState {
    if (state.createdCode != null) return state
    val failures = state.roomCreateFailureCount + 1
    val mayShowNotice = !state.roomCreateFailureSeen &&
        failures >= ROOM_CREATE_FAILURE_NOTICE_AFTER_FAILURES
    return state.copy(
        roomCreateFailureCount = failures,
        roomCreateFailureNotice = state.roomCreateFailureNotice || mayShowNotice,
    )
}

internal fun dismissRoomCreateFailure(state: AppUiState): AppUiState =
    state.copy(roomCreateFailureNotice = false, roomCreateFailureSeen = true)

internal fun clearRoomCreateFailure(state: AppUiState): AppUiState =
    state.copy(
        roomCreateFailureCount = 0,
        roomCreateFailureNotice = false,
        roomCreateFailureSeen = false,
    )

/** Whether the blank local-code card should explain itself inline. */
fun roomCreateFailing(state: AppUiState): Boolean =
    state.createdCode == null && state.roomCreateFailureCount > 0

/**
 * Whether the conversation owns the whole screen (design D1: no bottom bar).
 *
 * A connected session keeps the conversation even while a transient reconnect is
 * in flight: `channelReady` drops during auto-reconnect, but the session, its
 * transfers and its history survive, so keying off `channelReady` alone would
 * throw the user back to the 4-tab home mid-conversation. [showingHome] is the
 * ‹ / system-back escape hatch to the home page, which keeps the session alive.
 */
fun chatConversationVisible(state: AppUiState, showingHome: Boolean): Boolean {
    val connected = state.channelReady || (state.transientTransportReconnecting && state.joinedRoom != null)
    return connected && !showingHome
}

/**
 * Where a navigation event sends the home-page tab selection.
 *
 * While the conversation owns the screen there is no send tab to land on (design D1): the
 * conversation already renders the queued-files bar with its 全部发送 action, so the event just
 * reveals that screen and [SELECTED] leaves the tab the user last chose untouched, for the next ‹.
 * Before connecting the event keeps its old meaning and opens the page where the queue lives —
 * [TRANSFER], the merge of the old Share and Receive tabs.
 */
enum class ConnectionHomeTab {
    SELECTED,
    TRANSFER,
}

fun navigationTargetHomeTab(
    event: AppNavigationEvent,
    conversationVisible: Boolean,
): ConnectionHomeTab = when (event) {
    AppNavigationEvent.OpenShare -> if (conversationVisible) ConnectionHomeTab.SELECTED else ConnectionHomeTab.TRANSFER
}

/**
 * Whether a fresh pairing should move the shell to 传输.
 *
 * Only the first channel-ready of a room counts: [wasPaired] stays true for
 * the lifetime of the room, so recovering from a transient reconnect (which
 * drops `channelReady` but keeps the room) never yanks the user back, and a
 * user already on 传输 is left where they are.
 */
fun shouldAutoSelectTransferTab(
    wasPaired: Boolean,
    channelReady: Boolean,
    onTransferTab: Boolean,
): Boolean = channelReady && !wasPaired && !onTransferTab

/** Only own text can be copied; recalled entries are plain system notes. */
fun chatCopyableText(entry: ChatTimelineEntry): String? = when (entry) {
    is ChatTimelineEntry.Text -> entry.message.text.takeIf { it.isNotBlank() }
    is ChatTimelineEntry.File, is ChatTimelineEntry.Recalled -> null
}

/** Recall is offered on own entries only, in place, never for another peer's row. */
fun chatRecallTarget(entry: ChatTimelineEntry): RecallTarget? = when (entry) {
    is ChatTimelineEntry.Text -> if (entry.message.outgoing) RecallTarget.TEXT else null
    is ChatTimelineEntry.File -> if (entry.incoming) null else RecallTarget.FILE
    is ChatTimelineEntry.Recalled -> null
}

/** The entry a recall would act on (its id), or null when there is nothing to recall. */
fun chatRecallTargetId(entry: ChatTimelineEntry): String? = when (entry) {
    is ChatTimelineEntry.Text -> entry.message.id
    is ChatTimelineEntry.File -> entry.card.fileId
    is ChatTimelineEntry.Recalled -> null
}

/** Send is enabled only with a non-blank draft, so an empty tap neither sends nor shifts the bar. */
fun chatSendEnabled(draft: String): Boolean = draft.isNotBlank()

/**
 * Bytes as the file bubble meta shows them: B / KB / MB / GB with one decimal
 * from MB up, plus a separate byte count while bytes are known.
 */
fun formatFileSize(bytes: Long?): String {
    if (bytes == null || bytes < 0) return ""
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${oneDecimal(kb)} KB"
    val mb = kb / 1024.0
    if (mb < 1024) return "${oneDecimal(mb)} MB"
    return "${oneDecimal(mb / 1024.0)} GB"
}

private fun oneDecimal(value: Double): String = String.format(Locale.US, "%.1f", value)

/** "1.0 / 2.4 MB" while transferring, empty once bytes are unknown or the transfer settled. */
fun formatTransferBytes(receivedBytes: Long?, totalBytes: Long?): String {
    if (receivedBytes == null || totalBytes == null || totalBytes <= 0) return ""
    return "${formatFileSize(receivedBytes)} / ${formatFileSize(totalBytes)}"
}

/** The emoji palette keeps the draft short; pasted text is not truncated by it. */
val CHAT_EMOJI_PALETTE: List<String> = listOf(
    "😀", "😄", "😊", "😉", "😍", "🤔", "😅", "😂", "😢", "😮",
    "👍", "👎", "👏", "🙏", "💪", "👌", "✌️", "🤝", "🎉", "🔥",
    "❤️", "💚", "💯", "⭐", "✅", "❌", "📎", "📁", "⏰", "☕",
)

/** Appends a palette glyph at the end of the draft, keeping what the user already typed. */
fun appendEmoji(draft: String, emoji: String): String = draft + emoji

/** A stable, unique-on-first-use suggestion for an oversized draft offered as `.txt`. */
fun oversizeDraftName(text: String, atMs: Long): String =
    "message-${java.time.Instant.ofEpochMilli(atMs).toString().replace(':', '-')}-${sha256Hex(text.toByteArray()).take(8)}.txt"

private fun ttlLabel(expiresAtMs: Long?, nowMs: Long): String {
    if (expiresAtMs == null) return "--:--"
    val seconds = ((expiresAtMs - nowMs + 999) / 1000).coerceIn(0, PAIRING_TTL_SECONDS)
    val minutesPart = seconds / 60
    val secondsPart = seconds % 60
    return "${minutesPart.toString().padStart(2, '0')}:${secondsPart.toString().padStart(2, '0')}"
}
