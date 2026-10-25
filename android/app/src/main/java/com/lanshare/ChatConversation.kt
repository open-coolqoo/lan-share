package com.lanshare

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

// Warm-green palette island (design §3 / D2 / D5 / D6).
//
// Declared here on purpose instead of leaning on the app-wide surface / text /
// line / muted tokens: an own bubble is a fixed island, so its fill, text,
// meta, action and progress colours switch as one group and a dark-theme token
// can never leak a light value (or the reverse) onto it.

private val ChatCanvasLight = Color(0xFFF5F7F4)
private val ChatCanvasDark = Color(0xFF111512)
private val ChatBarLight = Color(0xFFFFFFFF)
private val ChatBarDark = Color(0xFF1B211D)
private val ChatBarLineLight = Color(0xFFDCE5DF)
private val ChatBarLineDark = Color(0xFF34463D)

private val ChatPeerBubbleLight = Color(0xFFFFFFFF)
private val ChatPeerBubbleDark = Color(0xFF1B211D)
private val ChatPeerTextLight = Color(0xFF17211D)
private val ChatPeerTextDark = Color(0xFFEAF2ED)
private val ChatPeerMetaLight = Color(0xFF637269)
private val ChatPeerMetaDark = Color(0xFFA7B6AD)
private val ChatPeerActionLight = Color(0xFF0B513F)
private val ChatPeerActionDark = Color(0xFF4DB78E)
private val ChatPeerTrackLight = Color(0xFFDCE5DF)
private val ChatPeerTrackDark = Color(0xFF34463D)

private val ChatOwnTextLight = Color(0xFF95EC69)
private val ChatOwnTextInkLight = Color(0xFF17211D)
private val ChatOwnTextDark = Color(0xFF0F6B55)
private val ChatOwnTextInkDark = Color(0xFFFFFFFF)

private val ChatOwnFileLight = Color(0xFFDDF5CD)
private val ChatOwnFileDark = Color(0xFF173D32)
private val ChatOwnFileNameLight = Color(0xFF17211D)
private val ChatOwnFileNameDark = Color(0xFFEAF2ED)
private val ChatOwnFileMetaLight = Color(0xFF5C6B62)
private val ChatOwnFileMetaDark = Color(0xFFA7B6AD)
private val ChatOwnFileBorderLight = Color(0xFFC3DEA2)
private val ChatOwnFileBorderDark = Color(0xFF2A5A4A)
private val ChatOwnFileActionLight = Color(0xFF4F5F56)
private val ChatOwnFileActionDark = Color(0xFFC9D6CE)
private val ChatOwnTrackLight = Color(0x2917211D)
private val ChatOwnTrackDark = Color(0x2EFFFFFF)
private val ChatOwnProgressLight = Color(0xFF0B513F)
private val ChatOwnProgressDark = Color(0xFF6FD0A6)

private val ChatOwnAvatarLight = Color(0xFF0F6B55)
private val ChatOwnAvatarInkLight = Color(0xFFFFFFFF)
private val ChatOwnAvatarDark = Color(0xFF173D32)
private val ChatOwnAvatarInkDark = Color(0xFFEAF2ED)
private val ChatPeerAvatar = Color(0xFF4F7A91)
private val ChatPeerAvatarInk = Color(0xFFFFFFFF)

private val ChatSystemNoteLight = Color(0xFFE3EAE5)
private val ChatSystemNoteDark = Color(0xFF2A342E)
private val ChatSystemNoteTextLight = Color(0xFF637269)
private val ChatSystemNoteTextDark = Color(0xFFA7B6AD)
private val ChatReconnectingLight = Color(0xFFF3E6C4)
private val ChatReconnectingDark = Color(0xFF3A3220)
private val ChatReconnectingTextLight = Color(0xFF7A5200)
private val ChatReconnectingTextDark = Color(0xFFE8C46A)

/** Design §3 `.send.off`: the enabled button at 40% instead of a grey swap that reads as another control. */
private const val CHAT_SEND_DISABLED_ALPHA = 0.4f
private val ChatInputLight = Color(0xFFF5F7F4)
private val ChatInputDark = Color(0xFF111512)
private val ChatIconLight = Color(0xFF4A554E)
private val ChatIconDark = Color(0xFFC9D6CE)
private val ChatTileLight = Color(0xFFEEF5F1)
private val ChatTileDark = Color(0xFF24302A)

private val ChatOwnMetaWarningLight = Color(0xFF7A5200)
private val ChatOwnMetaWarningDark = Color(0xFFE8C46A)
private val ChatScrim = Color(0x66000000)
private val ChatDestructive = Color(0xFFB42318)
private val ChatDestructiveDark = Color(0xFFFFB4AB)

// 连接详情 sheet tokens (design §3): the code block, the 48dp rows and the disconnect button.
private val ChatDetailsCodeLight = Color(0xFFF0FAF5)
private val ChatDetailsCodeDark = Color(0xFF132A23)
private val ChatDetailsRowLight = Color(0xFFEEF5F1)
private val ChatDetailsRowDark = Color(0xFF24302A)
private val ChatDetailsDangerFillLight = Color(0xFFFDF7F6)
private val ChatDetailsDangerLineLight = Color(0xFFEECFCD)
private val ChatDetailsDangerFillDark = Color(0xFF3B1E1D)
private val ChatDetailsDangerLineDark = Color(0xFF7A3834)

/** The sheet's QR is drawn at 80dp plus its 5dp padding, at triple density for a crisp scan. */
private const val CHAT_DETAILS_QR_PX = 270

/** Dismissal for the sheet: Back and the scrim both close it. */
private val ChatDialogProperties = DialogProperties(usePlatformDefaultWidth = false)

/** A bubble never takes more than this share of the stream; the avatar keeps the rest. */
private const val CHAT_BUBBLE_MAX_FRACTION = 0.78f

private val ChatBubbleShape = RoundedCornerShape(8.dp)
private val ChatBadgeShape = RoundedCornerShape(6.dp)
private val ChatAvatarShape = RoundedCornerShape(8.dp)
private val ChatSheetShape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)

// File badges follow the web kinds and hues (`App.tsx:2289-2313`, `styles.css:763-773`).
private val ChatBadgeKinds: Map<String, String> = buildMap {
    listOf("PNG", "JPG", "JPEG", "GIF", "WEBP", "HEIC", "BMP", "SVG", "AVIF").forEach { put(it, "image") }
    listOf("MP4", "MOV", "AVI", "MKV", "WEBM", "M4V").forEach { put(it, "video") }
    listOf("MP3", "WAV", "FLAC", "M4A", "AAC", "OGG").forEach { put(it, "audio") }
    put("PDF", "pdf")
    listOf("DOC", "DOCX", "PAGES").forEach { put(it, "doc") }
    listOf("XLS", "XLSX", "CSV", "NUMBERS").forEach { put(it, "sheet") }
    listOf("PPT", "PPTX", "KEY").forEach { put(it, "slide") }
    listOf("ZIP", "RAR", "7Z", "TAR", "GZ", "BZ2", "XZ").forEach { put(it, "archive") }
    put("APK", "apk")
    listOf("TXT", "MD", "JSON", "LOG").forEach { put(it, "text") }
}

private val ChatBadgeColors: Map<String, Color> = mapOf(
    "image" to Color(0xFF7C6FF0),
    "video" to Color(0xFFEF7D33),
    "audio" to Color(0xFF14A08A),
    "pdf" to Color(0xFFE5484D),
    "doc" to Color(0xFF2F7DE1),
    "sheet" to Color(0xFF2AA05A),
    "slide" to Color(0xFFE8842C),
    "archive" to Color(0xFFA08430),
    "text" to Color(0xFF78909C),
)
private val ChatBadgeFallback = Color(0xFF64748B)
private val ChatApkInk = Color(0xFF14381F)

/**
 * The type badge for one file. The label is the extension (at most 4 chars,
 * `FILE` when unknown); the kind comes from the extension first and falls back
 * to `file`, exactly like the web. APK keeps a dark glyph on its green, every
 * other kind is white — an icon block carries the kind, not a coloured word.
 */
internal fun chatFileBadge(fileName: String): Triple<String, Color, Color> {
    val extension = if (fileName.contains('.')) {
        fileName.substringAfterLast('.').trim().uppercase()
    } else {
        ""
    }
    val kind = ChatBadgeKinds[extension] ?: "file"
    val background = ChatBadgeColors[kind] ?: ChatBadgeFallback
    val ink = if (kind == "apk") ChatApkInk else Color.White
    return Triple(extension.take(4).ifEmpty { "FILE" }, background, ink)
}

/**
 * The conversation (design §2/§3/§4, decisions D1–D6), rendered inside the 传输 tab.
 *
 * Holds no navigation state: 前往连接 and the sheet's 设置 row report through their
 * callbacks, while the system back button is the shell's concern. The screen owns no
 * bottom bar — the shell draws it, and the shell's `imePadding` lifts bar and composer
 * together. Tapping the top bar (there is no ‹ anymore) opens the 连接详情 sheet.
 *
 * Unpaired the same page is the chat-style empty state: a neutral disconnected title, one
 * notice with a 前往连接 action, and the composer visible but unable to send text. The
 * attachment tiles stay enabled because the pending-share queue really accepts them:
 * `AppViewModel.sendSelectedUris` falls back to `enqueueSharedUris` when the channel is not
 * ready, and the files wait for the next session instead of being dropped.
 */
@Composable
internal fun ChatConversationScreen(
    state: AppUiState,
    viewModel: AppViewModel,
    onGoToConnect: () -> Unit,
    onOpenSettings: () -> Unit,
    onPickFiles: () -> Unit,
    onPickGallery: () -> Unit,
    onPickFolder: () -> Unit,
    onAcceptIncoming: (String) -> Unit,
    onDisconnect: () -> Unit,
) {
    val dark = LocalDarkTheme.current
    val primary = LocalPrimaryColor.current
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val timeline = chatTimeline(state.chatMessages, state.localTransfers, state.peerTransfers)
    val peerName = state.peerDeviceLabel.ifBlank { stringResource(R.string.web_browser) }
    val peerBadge = deviceBadge(peerName)
    val ownBadge = deviceBadge(state.localDeviceLabel)
    // Paired once a channel is live, or while a transient reconnect keeps the session alive.
    val paired = chatConversationComposerEnabled(state)

    var draft by remember { mutableStateOf("") }
    var emojiOpen by remember { mutableStateOf(false) }
    var attachmentOpen by remember { mutableStateOf(false) }
    var actionSheetEntry by remember { mutableStateOf<ChatTimelineEntry?>(null) }
    var detailsOpen by remember { mutableStateOf(false) }
    val detailsAvailable = paired && chatConnectionDetailsAvailable(state, conversationVisible = true)

    // Bottom-anchored. Keyed off the whole list, not just its last entry: a status
    // change on an older bubble, and the appended notes (pending files, failure,
    // reconnect), also have to bring the end of the stream into view.
    val streamVersion = (
        timeline.map { it.key } +
            chatStreamTailNoteKeys(state.pendingSharedFileCount, state.chatFailure, state.channelReady) +
            listOfNotNull(state.folderPickNotice?.let { "chat-folder-notice" })
        ).joinToString("|")
    var focusScrollPulse by remember { mutableIntStateOf(0) }
    // The last row is a lazy item, so it may not exist yet when this runs: position on a
    // large index and let Compose clamp it to the end.
    LaunchedEffect(streamVersion, focusScrollPulse) {
        listState.scrollToItem(Int.MAX_VALUE)
    }

    fun hidePickers() {
        emojiOpen = false
        attachmentOpen = false
    }

    fun submitDraft() {
        if (!chatSendEnabled(draft)) return
        // The composer is disabled while unpaired, but the rule holds here too: no text leaves
        // this screen without an open channel.
        if (!chatConversationComposerEnabled(state)) return
        viewModel.sendChatText(draft)
        draft = ""
    }

    fun openActionSheet(entry: ChatTimelineEntry) {
        if (chatCopyableText(entry) == null && chatRecallTarget(entry) == null) return
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        focusManager.clearFocus(force = true)
        hidePickers()
        actionSheetEntry = entry
    }

    fun openDetails() {
        if (!detailsAvailable) return
        // The sheet lists what to do next; no picker or keyboard should sit under it.
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        hidePickers()
        detailsOpen = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (dark) ChatCanvasDark else ChatCanvasLight),
    ) {
        ChatTopBar(
            dark = dark,
            peerName = if (paired) peerName else stringResource(R.string.chat_disconnected_title),
            modeLabel = when {
                state.channelReady -> connectionPathLabel(state.connectionPath)
                state.transientTransportReconnecting -> stringResource(R.string.chat_reconnecting)
                else -> stringResource(R.string.chat_not_connected)
            },
            modeIsWarning = !state.channelReady,
            onOpenDetails = if (detailsAvailable) ::openDetails else null,
        )
        if (!state.channelReady) {
            // Unpaired (or between sessions): one empty state, no stream and nothing stale.
            ChatUnpairedStream(
                dark = dark,
                message = stringResource(R.string.chat_unpaired_stream),
                actionLabel = stringResource(R.string.go_to_connect),
                onGoToConnect = onGoToConnect,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
        } else if (timeline.isEmpty() && state.chatFailure.isBlank() && state.folderPickNotice == null) {

            ChatEmptyStream(
                dark = dark,
                systemNote = stringResource(R.string.chat_session_established),
                hint = stringResource(R.string.chat_empty_hint),
                emptyLabel = stringResource(R.string.chat_messages_empty),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = AdaptiveLayout.gridPadding)
                    .widthIn(max = AdaptiveLayout.conversationWidth)
                    .align(Alignment.CenterHorizontally),
                contentPadding = PaddingValues(vertical = 14.dp),
                // Anchor a short conversation to the bottom the way the web stream does
                // (`.chat-timeline { margin-top: auto }`): the newest entry sits next to
                // the composer and the stream grows upward from there. Without the
                // alignment the first message pinned itself to the top of an otherwise
                // empty screen.
                verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.Bottom),
            ) {
                itemsIndexed(timeline, key = { _, entry -> entry.key }) { index, entry ->
                    // Name only at the start of a peer run (design frame 02); PEER_NAME_LABEL
                    // is swapped for the device label here so the rule itself stays pure.
                    val senderName = chatSenderLabel(entry, timeline.getOrNull(index - 1))?.let { peerName }
                    when (entry) {
                        // A recall never removes the row: it turns it into a note in place.
                        is ChatTimelineEntry.Recalled -> ChatSystemNoteRow(
                            dark = dark,
                            text = stringResource(if (entry.byMe) R.string.chat_recall_self else R.string.chat_recall_peer),
                            warning = false,
                        )
                        is ChatTimelineEntry.Text -> {
                            val incoming = !entry.message.outgoing
                            ChatBubbleRow(
                                dark = dark,
                                incoming = incoming,
                                avatar = if (incoming) peerBadge else ownBadge,
                                senderName = senderName,
                                onLongPress = { openActionSheet(entry) },
                            ) {
                                ChatTextBubble(dark = dark, own = !incoming, text = entry.message.text)
                            }
                        }
                        is ChatTimelineEntry.File -> ChatBubbleRow(
                            dark = dark,
                            incoming = entry.incoming,
                            avatar = if (entry.incoming) peerBadge else ownBadge,
                            senderName = senderName,
                            onLongPress = { openActionSheet(entry) },
                        ) {
                            ChatFileBubble(
                                dark = dark,
                                card = entry.card,
                                incoming = entry.incoming,
                                onAccept = { onAcceptIncoming(entry.card.fileId) },
                                onOpenDownloads = { openDownloadFolder(context) },
                                onRecall = {
                                    chatRecallTargetId(entry)?.let { id ->
                                        viewModel.recallChatEntry(RecallTarget.FILE, id)
                                    }
                                },
                                onCancel = { viewModel.cancelChatTransfer(entry.card.fileId) },
                            )
                        }
                    }
                }

                if (state.pendingSharedFileCount > 0) {
                    item(key = "chat-pending") {
                        ChatPendingPanel(
                            label = stringResource(R.string.chat_pending_files, state.pendingSharedFileCount),
                            actionLabel = "${stringResource(R.string.chat_pending_send_all)} ›",
                            // The queue already releases itself when the channel is usable; while it is
                            // not (reconnect in flight) there is nothing to press, so the row is a notice.
                            onSendAll = viewModel::sendQueuedSharedFiles,
                            canSend = state.channelReady,
                        )
                    }
                }
                if (state.chatFailure.isNotBlank()) {
                    item(key = "chat-failure") { ErrorBanner(chatFailureText(state.chatFailure)) }
                }
                state.folderPickNotice?.let { notice ->
                    item(key = "chat-folder-notice") {
                        ChatFolderNotice(dark = dark, notice = notice, onDismiss = viewModel::dismissFolderPickNotice)
                    }
                }
                if (!state.channelReady) {
                    item(key = "chat-session-note") {
                        val reconnecting = state.transientTransportReconnecting && state.joinedRoom != null
                        ChatSystemNoteRow(
                            dark = dark,
                            text = stringResource(if (reconnecting) R.string.chat_reconnecting else R.string.chat_peer_disconnected),
                            warning = reconnecting,
                        )
                    }
                }
            }
        }

        when {
            attachmentOpen -> ChatAttachmentPanel(
                dark = dark,
                primary = primary,
                galleryLabel = stringResource(R.string.gallery),
                filesLabel = stringResource(R.string.files),
                folderLabel = stringResource(R.string.chat_attach_folder),
                downloadsLabel = stringResource(R.string.open_downloads_folder),
                onGallery = {
                    hidePickers()
                    onPickGallery()
                },
                onFiles = {
                    hidePickers()
                    onPickFiles()
                },
                onFolder = {
                    hidePickers()
                    onPickFolder()
                },
                onOpenDownloads = {
                    hidePickers()
                    openDownloadFolder(context)
                },
            )
            emojiOpen -> ChatEmojiPalette(dark = dark) { emoji -> draft = appendEmoji(draft, emoji) }
        }

        ChatComposer(
            dark = dark,
            primary = primary,
            draft = draft,
            onDraftChange = { draft = it },
            onFieldFocus = { focusScrollPulse += 1 },
            enabled = paired,
            paired = paired,
            placeholder = if (paired) {
                stringResource(R.string.chat_message_placeholder)
            } else {
                stringResource(R.string.chat_composer_unpaired_hint)
            },
            emojiOpen = emojiOpen,
            attachmentOpen = attachmentOpen,
            onToggleEmoji = {
                // Unpaired there is nothing to write into, so the palette never opens.
                if (!paired) return@ChatComposer
                emojiOpen = !emojiOpen
                attachmentOpen = false
                if (!emojiOpen) scope.launch { keyboardController?.show() }
            },
            onToggleAttachment = {
                val opening = !attachmentOpen
                attachmentOpen = opening
                emojiOpen = false
                if (opening) {
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                }
            },
            onSend = ::submitDraft,
        )
    }

    actionSheetEntry?.let { entry ->
        ChatActionSheet(
            dark = dark,
            copyText = chatCopyableText(entry),
            onRecall = chatRecallTarget(entry)?.let { target ->
                { chatRecallTargetId(entry)?.let { id -> viewModel.recallChatEntry(target, id) } }
            },
            onDismiss = { actionSheetEntry = null },
        )
    }

    if (detailsOpen) {
        // A real disconnect (no live channel, no reconnect in flight) empties the room, so the
        // sheet has nothing left to show; it closes itself rather than lingering over the home page.
        LaunchedEffect(detailsAvailable) {
            if (!detailsAvailable) detailsOpen = false
        }
        ChatConnectionDetailsSheet(
            dark = dark,
            state = state,
            connectionPathLabel = connectionPathLabel(state.connectionPath),
            webBrowserLabel = stringResource(R.string.web_browser),
            onRefreshCode = viewModel::createRoom,
            onOpenSettings = {
                detailsOpen = false
                onOpenSettings()
            },
            onDisconnect = {
                detailsOpen = false
                onDisconnect()
            },
            onDismiss = { detailsOpen = false },
        )
    }
}

/**
 * The 连接详情 sheet (design §2 frame 13, §3 sheet tokens). Modal, 14dp top
 * corners, opened by tapping the conversation's top bar (which now carries no ‹).
 * It never renders a bottom bar: the shell owns that, and the sheet lifts itself
 * above the keyboard on its own.
 */
@Composable
private fun ChatConnectionDetailsSheet(
    dark: Boolean,
    state: AppUiState,
    connectionPathLabel: String,
    webBrowserLabel: String,
    onRefreshCode: () -> Unit,
    onOpenSettings: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    // A dialog window is not dimmed by the platform, so the scrim is drawn here (same as the
    // long-press sheet) and both a scrim tap and Back close the sheet.
    Dialog(onDismissRequest = onDismiss, properties = ChatDialogProperties) {
        val details = connectionDetailsState(state)
        val ink = if (dark) ChatPeerTextDark else ChatPeerTextLight
        val primary = LocalPrimaryColor.current
        val barFill = if (dark) ChatBarDark else ChatBarLight
        val divider = if (dark) ChatBarLineDark else ChatBarLineLight
        val qr = details.code?.let { code ->
            remember(state.origin, code, state.locale) {
                runCatching { pairingQrImage(state.origin, code, state.locale, CHAT_DETAILS_QR_PX) }.getOrNull()
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ChatScrim)
                .clickable(onClick = onDismiss),
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    // A dialog is its own window: the shell's imePadding cannot reach it, so the
                    // sheet lifts itself above the keyboard and owns the navigation-bar inset.
                    .imePadding()
                    .navigationBarsPadding()
                    .clip(ChatSheetShape)
                    .background(barFill)
                    // Keep taps on the card from reaching the dismiss scrim underneath.
                    .clickable(enabled = false) {},
            ) {
                ChatSheetGrabber(divider)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.chat_details_title),
                        color = ink,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    if (state.channelReady) {
                        StatusPill(stringResource(R.string.connected))
                    } else {
                        ChatWarningPill(dark = dark, label = stringResource(R.string.chat_reconnecting))
                    }
                }
                HorizontalDivider(color = divider)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Short screens scroll the body; frame 13's card fits 393×852 without it.
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ChatDetailsCodeCard(dark = dark, code = details.code.orEmpty(), qr = qr)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ChatDetailsDeviceBox(
                            label = stringResource(R.string.local),
                            value = details.localDevice.ifBlank { webBrowserLabel },
                            dark = dark,
                            modifier = Modifier.weight(1f),
                        )
                        ChatDetailsDeviceBox(
                            label = stringResource(R.string.peer),
                            value = details.peerDevice.ifBlank { webBrowserLabel },
                            dark = dark,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    ChatDetailsPathRow(dark = dark, path = state.connectionPath, pathLabel = connectionPathLabel)
                    // Before the diagnostics block, in the order frame 13 lists them.
                    ChatDetailsReconnectRow(dark = dark)
                    ChatDetailsDiagnostics(
                        dark = dark,
                        diagnostics = details.diagnostics,
                        expandLabel = stringResource(R.string.chat_details_expand),
                        collapseLabel = stringResource(R.string.chat_details_collapse),
                    )
                    ChatDetailsActionRow(
                        dark = dark,
                        label = stringResource(R.string.chat_details_settings),
                        value = "${stringResource(R.string.chat_details_open)} ›",
                        ink = primary,
                        onClick = onOpenSettings,
                    )
                    ChatDetailsButton(
                        label = stringResource(R.string.refresh_code),
                        enabled = details.canRefresh,
                        disabledLabel = stringResource(R.string.chat_details_disabled),
                        ink = ink,
                        fill = barFill,
                        border = divider,
                        onClick = onRefreshCode,
                    )
                    ChatDetailsButton(
                        label = stringResource(R.string.disconnect),
                        enabled = details.canDisconnect,
                        disabledLabel = stringResource(R.string.chat_details_disabled),
                        ink = if (dark) ChatDestructiveDark else ChatDestructive,
                        fill = if (dark) ChatDetailsDangerFillDark else ChatDetailsDangerFillLight,
                        border = if (dark) ChatDetailsDangerLineDark else ChatDetailsDangerLineLight,
                        onClick = onDisconnect,
                    )
                }
            }
        }
    }
}

/** The 36×4dp grabber the design draws at the top of a sheet. */
@Composable
private fun ChatSheetGrabber(line: Color) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Box(
            modifier = Modifier
                .padding(top = 8.dp, bottom = 6.dp)
                .size(width = 36.dp, height = 4.dp)
                .clip(PillShape)
                .background(line),
        )
    }
}

/** This session's code, left, and the pairing QR from `pairingQrImage`, right (frame 13). */
@Composable
private fun ChatDetailsCodeCard(dark: Boolean, code: String, qr: ImageBitmap?) {
    val primary = LocalPrimaryColor.current
    val codeLine = if (dark) Color(0xFF2D6A58) else Color(0xFFBAD9CB)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ChatBubbleShape)
            .background(if (dark) ChatDetailsCodeDark else ChatDetailsCodeLight)
            .border(1.dp, codeLine, ChatBubbleShape)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FieldLabel(stringResource(R.string.chat_details_code_label))
            Text(
                text = code,
                color = if (dark) ChatPeerTextDark else Color(0xFF0B513F),
                fontSize = 30.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 3.sp,
                maxLines = 1,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(ChatBubbleShape)
                    // The QR keeps a white quiet zone in both themes so it stays scannable.
                    .background(Color.White)
                    .border(1.dp, codeLine, ChatBubbleShape)
                    .padding(5.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (qr != null) {
                    Image(
                        bitmap = qr,
                        contentDescription = stringResource(R.string.qr_code_pairing_content_description),
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text("--", color = primary, fontWeight = FontWeight.Black)
                }
            }
            Text(stringResource(R.string.chat_details_qr_label), color = if (dark) ChatPeerMetaDark else ChatPeerMetaLight, fontSize = 10.sp)
        }
    }
}

@Composable
private fun ChatDetailsDeviceBox(
    label: String,
    value: String,
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(ChatBubbleShape)
            .background(if (dark) ChatDetailsRowDark else ChatDetailsRowLight)
            .border(1.dp, if (dark) ChatBarLineDark else ChatBarLineLight, ChatBubbleShape)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(label, color = if (dark) ChatPeerMetaDark else ChatPeerMetaLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(
            text = value,
            color = if (dark) ChatPeerTextDark else ChatPeerTextLight,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The 连接方式 row: the localized name on the left and the badge on the right.
 *
 * [path] is `connectionPathLabel(state.connectionPath)` — the same real state
 * the top bar shows — while the badge carries the path's own short tag
 * (board `--path-badge`: "Direct" / "Unknown"), exactly as frame 13 draws
 * 「直连 [Direct]」. Only DIRECT and UNKNOWN exist, so there is no relay badge.
 */
@Composable
private fun ChatDetailsPathRow(dark: Boolean, path: ConnectionPath, pathLabel: String) {
    val divider = if (dark) ChatBarLineDark else ChatBarLineLight
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(ChatBubbleShape)
            .background(if (dark) ChatDetailsRowDark else ChatDetailsRowLight)
            .border(1.dp, divider, ChatBubbleShape)
            .padding(start = 11.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.chat_details_connection_mode),
            color = if (dark) ChatPeerMetaDark else ChatPeerMetaLight,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = pathLabel,
            color = if (dark) ChatPeerTextDark else ChatPeerTextLight,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Box(
            modifier = Modifier
                .clip(PillShape)
                .background(divider)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = path.label,
                color = if (dark) ChatPeerTextDark else ChatPeerTextLight,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

/** The 「网络中断时 · 5 分钟内自动重连」 row: a statement, not a control (frame 13). */
@Composable
private fun ChatDetailsReconnectRow(dark: Boolean) {
    val divider = if (dark) ChatBarLineDark else ChatBarLineLight
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(ChatBubbleShape)
            .background(if (dark) ChatDetailsRowDark else ChatDetailsRowLight)
            .border(1.dp, divider, ChatBubbleShape)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.chat_details_reconnect),
            color = if (dark) ChatPeerMetaDark else ChatPeerMetaLight,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.chat_details_reconnect_value),
            color = if (dark) ChatPeerTextDark else ChatPeerTextLight,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.2f),
        )
    }
}

/**
 * The diagnostics from `connectionState(...).diagnostics`, collapsed by default
 * and expanded exactly like the home page's `DiagnosticsPanel`: a summary row
 * that rotates the same chevron, then the same `key: value` rows.
 */
@Composable
private fun ChatDetailsDiagnostics(
    dark: Boolean,
    diagnostics: List<String>,
    expandLabel: String,
    collapseLabel: String,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val label = stringResource(R.string.diagnostics)
    val divider = if (dark) ChatBarLineDark else ChatBarLineLight
    val ink = if (dark) ChatPeerTextDark else ChatPeerTextLight
    val meta = if (dark) ChatPeerMetaDark else ChatPeerMetaLight
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "detailsDiagnosticsChevron",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ChatBubbleShape)
            .background(if (dark) ChatDetailsRowDark else ChatDetailsRowLight)
            .border(1.dp, divider, ChatBubbleShape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable { expanded = !expanded }
                .semantics { contentDescription = label }
                .padding(horizontal = 11.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, color = meta, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(
                text = "${if (expanded) collapseLabel else expandLabel} ›",
                color = ink,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Icon(
                painter = painterResource(R.drawable.ic_expand_more),
                contentDescription = null,
                tint = LocalPrimaryColor.current,
                modifier = Modifier
                    .size(24.dp)
                    .rotate(chevronRotation),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(200)),
            exit = shrinkVertically(tween(200)),
        ) {
            Column(
                modifier = Modifier.padding(start = 11.dp, end = 11.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HorizontalDivider(color = divider)
                diagnostics.forEach { row ->
                    val key = row.substringBefore(": ")
                    val value = row.substringAfter(": ", missingDelimiterValue = "")
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(key, color = meta, fontSize = 12.sp)
                        Text(
                            text = value,
                            color = ink,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** The 设置 row: the existing `tab_settings` wording, 48dp tall, opening the existing settings screen. */
@Composable
private fun ChatDetailsActionRow(dark: Boolean, label: String, value: String, ink: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(ChatBubbleShape)
            .background(if (dark) ChatDetailsRowDark else ChatDetailsRowLight)
            .border(1.dp, if (dark) ChatBarLineDark else ChatBarLineLight, ChatBubbleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, color = if (dark) ChatPeerMetaDark else ChatPeerMetaLight, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(text = value, color = ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** A full-width 48dp action button; a disabled one stays visible and says so instead of reacting. */
@Composable
private fun ChatDetailsButton(
    label: String,
    enabled: Boolean,
    disabledLabel: String,
    ink: Color,
    fill: Color,
    border: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(ChatBubbleShape)
            .background(fill)
            .border(1.dp, border, ChatBubbleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = if (enabled) label else "$label, $disabledLabel" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (enabled) ink else (if (LocalDarkTheme.current) ChatPeerMetaDark else ChatPeerMetaLight),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/**
 * The composer keeps accepting text while a transient reconnect is in flight
 * (the session and its transfers survive; a send that cannot go out now is
 * reported in the stream); a real disconnect disables it until reconnecting.
 */
internal fun chatConversationComposerEnabled(state: AppUiState): Boolean =
    state.channelReady || state.transientTransportReconnecting

/** [AppUiState.chatFailure] carries a code, never UI copy; the screen maps it here. */
@Composable
internal fun chatFailureText(code: String): String = when (code) {
    CHAT_CHANNEL_NOT_READY -> stringResource(R.string.transfer_empty_not_connected)
    else -> code
}

/** Plain-text copy for the long-press sheet, matching the rest of the app (no clip label). */
private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(null, text))
}

@Composable
private fun ChatTopBar(
    dark: Boolean,
    peerName: String,
    modeLabel: String,
    modeIsWarning: Boolean,
    onOpenDetails: (() -> Unit)?,
) {
    val detailsLabel = stringResource(R.string.chat_details_title)
    val ink = if (dark) ChatPeerTextDark else ChatPeerTextLight
    val meta = if (dark) ChatPeerMetaDark else ChatPeerMetaLight
    val barFill = if (dark) ChatBarDark else ChatBarLight
    val barLine = if (dark) ChatBarLineDark else ChatBarLineLight
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(barFill)
            // A single hairline under the bar (board `chatbar`), not a box around it.
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawLine(barLine, Offset(0f, size.height - stroke / 2f), Offset(size.width, size.height - stroke / 2f), stroke)
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The bar surface spans the canvas; the peer name and status pill sit
        // on the page grid so their edges line up with the stream and composer.
        GridColumn(maxWidth = AdaptiveLayout.conversationWidth) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clip(ChatBubbleShape)
                        .then(
                            if (onOpenDetails != null) {
                                Modifier.clickable(onClick = onOpenDetails).semantics { contentDescription = detailsLabel }
                            } else {
                                Modifier
                            },
                        )
                        .padding(start = 4.dp, end = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Left-aligned like the board, with the ▾ hint that this bar opens the sheet.
                    Text(
                        text = peerName,
                        color = ink,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (onOpenDetails != null) {
                        Text(text = "▾", color = meta, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Box {
                        if (modeIsWarning) {
                            ChatWarningPill(dark = dark, label = modeLabel)
                        } else {
                            StatusPill(modeLabel)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatWarningPill(dark: Boolean, label: String) {
    Box(
        modifier = Modifier
            .clip(PillShape)
            .background(if (dark) ChatReconnectingDark else ChatReconnectingLight)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = label.uppercase(),
            color = if (dark) ChatReconnectingTextDark else ChatReconnectingTextLight,
            fontSize = 11.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ChatEmptyStream(
    dark: Boolean,
    systemNote: String,
    hint: String,
    emptyLabel: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            ChatSystemNoteRow(dark = dark, text = systemNote, warning = false)
            Text(emptyLabel, color = if (dark) ChatPeerTextDark else ChatPeerTextLight, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                text = hint,
                color = if (dark) ChatPeerMetaDark else ChatPeerMetaLight,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The unpaired page's one empty state: the notice plus a 前往连接 action that selects the
 * 连接 tab. It stands where the stream would be, so a session's leftovers never show here.
 */
@Composable
private fun ChatUnpairedStream(
    dark: Boolean,
    message: String,
    actionLabel: String,
    onGoToConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            ChatSystemNoteRow(dark = dark, text = message, warning = false)
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(ChatBubbleShape)
                    .background(LocalPrimaryColor.current)
                    .clickable(onClick = onGoToConnect)
                    .semantics { contentDescription = actionLabel }
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = actionLabel,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ChatSystemNoteRow(dark: Boolean, text: String, warning: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Box(
            modifier = Modifier
                .clip(PillShape)
                .background(
                    when {
                        warning && dark -> ChatReconnectingDark
                        warning -> ChatReconnectingLight
                        dark -> ChatSystemNoteDark
                        else -> ChatSystemNoteLight
                    },
                )
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(
                text = text,
                color = when {
                    warning && dark -> ChatReconnectingTextDark
                    warning -> ChatReconnectingTextLight
                    dark -> ChatSystemNoteTextDark
                    else -> ChatSystemNoteTextLight
                },
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * One stream row. The peer avatar sits left of peer bubbles; own rows are
 * right-aligned with the own avatar trailing. The peer name appears only on
 * the first peer bubble of a run ([senderName] is null for later ones), and
 * the bubble itself is capped at [CHAT_BUBBLE_MAX_FRACTION] of the row.
 */
@Composable
private fun ChatBubbleRow(
    dark: Boolean,
    incoming: Boolean,
    avatar: String,
    senderName: String?,
    onLongPress: () -> Unit,
    bubble: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maxBubbleWidth = (maxWidth * CHAT_BUBBLE_MAX_FRACTION).coerceAtLeast(120.dp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (incoming) Arrangement.Start else Arrangement.End,
            verticalAlignment = Alignment.Top,
        ) {
            val avatarSlot: @Composable () -> Unit = { ChatAvatar(dark = dark, incoming = incoming, label = avatar) }
            val bubbleSlot: @Composable () -> Unit = {
                Column(
                    modifier = Modifier
                        .widthIn(max = maxBubbleWidth)
                        .padding(vertical = 2.dp),
                    horizontalAlignment = if (incoming) Alignment.Start else Alignment.End,
                ) {
                    if (senderName != null) {
                        Text(
                            text = senderName,
                            color = if (dark) ChatPeerMetaDark else ChatPeerMetaLight,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 2.dp, bottom = 3.dp),
                        )
                    }
                    // Press and long-press answer on the bubble alone; the sender
                    // name above stays inert (design §4: long-press the bubble).
                    Box(
                        modifier = Modifier
                            .clip(ChatBubbleShape)
                            .combinedClickable(
                                onClick = {},
                                // Long-press opens the action sheet (design §4: ~500ms; the platform default sits there).
                                onLongClick = onLongPress,
                            ),
                    ) {
                        bubble()
                    }
                }
            }
            if (incoming) {
                avatarSlot()
                Spacer(Modifier.width(8.dp))
                bubbleSlot()
            } else {
                bubbleSlot()
                Spacer(Modifier.width(8.dp))
                avatarSlot()
            }
        }
    }
}

@Composable
private fun ChatAvatar(dark: Boolean, incoming: Boolean, label: String) {
    val fill = when {
        incoming -> ChatPeerAvatar
        dark -> ChatOwnAvatarDark
        else -> ChatOwnAvatarLight
    }
    val ink = when {
        incoming -> ChatPeerAvatarInk
        dark -> ChatOwnAvatarInkDark
        else -> ChatOwnAvatarInkLight
    }
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(ChatAvatarShape)
            .background(fill),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = ink, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
}

@Composable
private fun ChatTextBubble(dark: Boolean, own: Boolean, text: String) {
    val fill = when {
        own && dark -> ChatOwnTextDark
        own -> ChatOwnTextLight
        dark -> ChatPeerBubbleDark
        else -> ChatPeerBubbleLight
    }
    val ink = when {
        own && dark -> ChatOwnTextInkDark
        own -> ChatOwnTextInkLight
        dark -> ChatPeerTextDark
        else -> ChatPeerTextLight
    }
    Box(
        modifier = Modifier
            .clip(ChatBubbleShape)
            .background(fill)
            .then(
                if (own) {
                    Modifier
                } else {
                    Modifier.border(1.dp, if (dark) ChatBarLineDark else ChatBarLineLight, ChatBubbleShape)
                },
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(text = text, color = ink, fontSize = 15.sp, lineHeight = 22.sp)
    }
}

/**
 * Keeps an LTR value (size, byte counter) intact inside an RTL paragraph:
 * without the isolate "128.0 KB" renders as "KB 128.0".
 */
private fun isolateLtr(value: String): String = if (value.isBlank()) value else "\u2066$value\u2069"

/**
 * A file bubble: type badge + name + `size · status` (plus the byte counter
 * while in flight) and a 4dp progress bar that only exists during a transfer.
 * The single action comes from [fileBubbleAction] — accept, cancel a receive,
 * open downloads or recall — and a bubble never renders two.
 */
@Composable
private fun ChatFileBubble(    dark: Boolean,
    card: TransferCardState,
    incoming: Boolean,
    onAccept: () -> Unit,
    onOpenDownloads: () -> Unit,
    onRecall: () -> Unit,
    onCancel: () -> Unit,
) {
    val own = !incoming
    val fill = when {
        own && dark -> ChatOwnFileDark
        own -> ChatOwnFileLight
        dark -> ChatPeerBubbleDark
        else -> ChatPeerBubbleLight
    }
    val nameInk = when {
        own && dark -> ChatOwnFileNameDark
        own -> ChatOwnFileNameLight
        dark -> ChatPeerTextDark
        else -> ChatPeerTextLight
    }
    val metaInk = when {
        own && dark -> ChatOwnFileMetaDark
        own -> ChatOwnFileMetaLight
        dark -> ChatPeerMetaDark
        else -> ChatPeerMetaLight
    }
    val actionInk = when {
        own && dark -> ChatOwnFileActionDark
        own -> ChatOwnFileActionLight
        dark -> ChatPeerActionDark
        else -> ChatPeerActionLight
    }
    val track = when {
        own && dark -> ChatOwnTrackDark
        own -> ChatOwnTrackLight
        dark -> ChatPeerTrackDark
        else -> ChatPeerTrackLight
    }
    val progressInk = when {
        own && dark -> ChatOwnProgressDark
        own -> ChatOwnProgressLight
        dark -> ChatPeerActionDark
        else -> ChatPeerActionLight
    }
    val badge = chatFileBadge(card.title)
    val action = fileBubbleAction(card, incoming)
    val transferring = shouldShowCompactTransferProgress(card, requested = false)
    val percent = card.progressPercent
    val sizeText = formatFileSize(card.sizeBytes)
    val statusText = chatFileStatusText(card, percent)
    val bytesText = if (transferring && sizeText.isNotBlank() && percent != null) {
        val done = (card.sizeBytes ?: 0L) * percent / 100
        formatTransferBytes(done, card.sizeBytes)
    } else {
        ""
    }
    val metaText = listOf(isolateLtr(sizeText), statusText)
        .filter { it.isNotBlank() }
        .joinToString(" · ") +
        if (bytesText.isBlank()) "" else " (${isolateLtr(bytesText)})"

    Column(
        modifier = Modifier
            .clip(ChatBubbleShape)
            .background(fill)
            .border(
                width = 1.dp,
                color = when {
                    own -> if (dark) ChatOwnFileBorderDark else ChatOwnFileBorderLight
                    dark -> ChatBarLineDark
                    else -> ChatBarLineLight
                },
                shape = ChatBubbleShape,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(ChatBadgeShape)
                    .background(badge.second),
                contentAlignment = Alignment.Center,
            ) {
                // Icon block: the extension drives colour and two glyphs, so no white-on-hue word is needed.
                Text(
                    text = badge.first.take(2),
                    color = badge.third,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = card.title,
                    color = nameInk,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = metaText,
                    color = if (chatFileMetaIsWarning(card)) {
                        if (dark && own) ChatOwnMetaWarningDark else ChatOwnMetaWarningLight
                    } else {
                        metaInk
                    },
                    fontSize = 12.sp,
                    fontWeight = if (chatFileMetaIsWarning(card)) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
        if (transferring) {
            ChatProgressBar(
                progress = ((percent ?: 0) / 100f).coerceIn(0f, 1f),
                track = track,
                ink = progressInk,
            )
        }
        when (action) {
            FileBubbleAction.ACCEPT -> ChatBubbleAction(
                label = stringResource(R.string.chat_file_accept),
                ink = actionInk,
                fill = if (dark) ChatPeerBubbleDark else ChatPeerBubbleLight,
                onClick = onAccept,
            )
            FileBubbleAction.OPEN_DOWNLOADS -> ChatInlineAction(
                label = "${stringResource(R.string.open_downloads_folder)} ›",
                color = actionInk,
                onClick = onOpenDownloads,
            )
            FileBubbleAction.RECALL -> ChatInlineAction(
                label = stringResource(R.string.chat_recall),
                color = actionInk,
                onClick = onRecall,
            )
            FileBubbleAction.CANCEL -> ChatCancelAction(
                label = stringResource(R.string.chat_file_cancel),
                color = if (dark) ChatDestructiveDark else ChatDestructive,
                onClick = onCancel,
            )
            null -> Unit
        }
    }
}

@Composable
internal fun chatFileStatusText(card: TransferCardState, percent: Int?): String {
    val value = percent ?: 0
    return when {
        card.status == TransferCardStatus.Transferring -> stringResource(R.string.chat_file_sending, value)
        card.status == TransferCardStatus.ReceivingFromWeb -> stringResource(R.string.chat_file_receiving, value)
        card.status == TransferCardStatus.WaitingForWebAcceptance -> stringResource(R.string.transfer_status_waiting_for_web_acceptance)
        card.status == TransferCardStatus.ReadyToAccept -> stringResource(R.string.chat_file_accept)
        card.status == TransferCardStatus.SentToWeb -> stringResource(R.string.chat_file_sent)
        card.status == TransferCardStatus.SavedOnAndroid -> stringResource(R.string.chat_file_saved)
        card.status == TransferCardStatus.Canceled -> stringResource(R.string.transfer_status_canceled)
        card.status.startsWith(TransferCardStatus.RejectedPrefix) -> stringResource(R.string.chat_file_rejected)
        card.status.startsWith(TransferCardStatus.ErrorPrefix) ->
            stringResource(R.string.chat_file_failed, card.status.removePrefix(TransferCardStatus.ErrorPrefix).trim())
        else -> ""
    }
}

internal fun chatFileMetaIsWarning(card: TransferCardState): Boolean =
    card.status.startsWith(TransferCardStatus.RejectedPrefix) || card.status.startsWith(TransferCardStatus.ErrorPrefix)

@Composable
private fun ChatProgressBar(progress: Float, track: Color, ink: Color) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(PillShape)
            .background(track),
    ) {
        val barWidth = size.width * progress
        drawRoundRect(
            color = ink,
            topLeft = Offset(
                x = if (layoutDirection == LayoutDirection.Rtl) size.width - barWidth else 0f,
                y = 0f,
            ),
            size = Size(width = barWidth, height = size.height),
            cornerRadius = CornerRadius(size.height / 2f),
        )
    }
}

/** Button-shaped single action: ≥48dp tall, outlined, one per bubble. */
@Composable
private fun ChatBubbleAction(label: String, ink: Color, fill: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 56.dp)
            .clip(ChatBubbleShape)
            .background(fill)
            .border(1.dp, ink, ChatBubbleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/**
 * The receive-cancel slot (web `App.tsx:2081-2095`, a 44dp ✕ button): an ✕
 * glyph in the danger ink inside a 44dp target. The label is also the spoken
 * name and the accessible tooltip, so the glyph is never the only cue.
 */
@Composable
private fun ChatCancelAction(label: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(ChatBubbleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(15.dp)) {
            val stroke = 1.8.dp.toPx()
            drawLine(color, Offset(0f, 0f), Offset(size.width, size.height), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(color, Offset(size.width, 0f), Offset(0f, size.height), strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}

/** Inline text action: 12sp label with a ≥48dp touch target. */
@Composable
private fun ChatInlineAction(label: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/**
 * The folder pick's own row (web `folderEmpty` / `folderTooMany`): amber, because
 * nothing failed — the folder simply had nothing usable, or more than the cap.
 * The tap target for closing it is the whole row, comfortably above 48dp.
 */
@Composable
private fun ChatFolderNotice(dark: Boolean, notice: FolderPickNotice, onDismiss: () -> Unit) {
    val text = when (notice) {
        FolderPickNotice.EMPTY -> stringResource(R.string.chat_folder_empty)
        FolderPickNotice.TOO_MANY -> stringResource(R.string.chat_folder_too_many, MAX_FOLDER_FILES)
        FolderPickNotice.NONE -> return
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(ChatBubbleShape)
            .background(if (dark) ChatReconnectingDark else ChatReconnectingLight)
            .clickable(onClick = onDismiss)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            color = if (dark) ChatReconnectingTextDark else ChatReconnectingTextLight,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ChatPendingPanel(label: String, actionLabel: String, canSend: Boolean, onSendAll: () -> Unit) {
    Panel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = appTextPrimary(),
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            if (canSend) {
                ChatInlineAction(label = actionLabel, color = LocalPrimaryColor.current, onClick = onSendAll)
            }
        }
    }
}

@Composable
private fun ChatAttachmentPanel(
    dark: Boolean,
    primary: Color,
    galleryLabel: String,
    filesLabel: String,
    folderLabel: String,
    downloadsLabel: String,
    onGallery: () -> Unit,
    onFiles: () -> Unit,
    onFolder: () -> Unit,
    onOpenDownloads: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (dark) ChatBarDark else ChatBarLight)
            .border(1.dp, if (dark) ChatBarLineDark else ChatBarLineLight)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        // The tiles sit on the page grid; the bar surface behind still spans
        // the canvas the way the composer's does.
        GridColumn(maxWidth = AdaptiveLayout.conversationWidth) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ChatTile(galleryLabel, R.drawable.ic_tab_share, dark, primary, onGallery)
                ChatTile(filesLabel, R.drawable.ic_tab_receive, dark, primary, onFiles)
                ChatTile(folderLabel, R.drawable.ic_folder, dark, primary, onFolder)
                ChatTile(downloadsLabel, R.drawable.ic_file_download, dark, primary, onOpenDownloads)
            }
        }
    }
}

@Composable
private fun ChatTile(label: String, iconRes: Int, dark: Boolean, tint: Color, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .widthIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp)
            .semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(ChatBubbleShape)
                .background(if (dark) ChatTileDark else ChatTileLight)
                .border(1.dp, if (dark) ChatBarLineDark else ChatBarLineLight, ChatBubbleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = label,
            color = if (dark) ChatPeerMetaDark else ChatPeerMetaLight,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Ten glyphs per row, each a full 48dp square. The squares are not weighted: a
 * weight would clamp each cell to its share of the row (~36–39dp on a 360–393dp
 * screen) and put the targets under the 48dp rule, so the grid scrolls instead.
 */
@Composable
private fun ChatEmojiPalette(dark: Boolean, onPick: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .background(if (dark) ChatBarDark else ChatBarLight)
            .border(1.dp, if (dark) ChatBarLineDark else ChatBarLineLight)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        CHAT_EMOJI_PALETTE.chunked(CHAT_EMOJI_PER_ROW).forEach { row ->
            Row {
                row.forEach { emoji ->
                    ChatTapTarget(
                        label = emoji,
                        onClick = { onPick(emoji) },
                        modifier = Modifier.requiredSize(48.dp),
                    ) {
                        Text(emoji, fontSize = 24.sp)
                    }
                }
            }
        }
    }
}

/** Ten 48dp cells are one screen width from a 414dp device up; narrower screens scroll. */
private const val CHAT_EMOJI_PER_ROW = 10

@Composable
private fun ChatComposer(
    dark: Boolean,
    primary: Color,
    draft: String,
    onDraftChange: (String) -> Unit,
    onFieldFocus: () -> Unit,
    enabled: Boolean,
    paired: Boolean,
    placeholder: String,
    emojiOpen: Boolean,
    attachmentOpen: Boolean,
    onToggleEmoji: () -> Unit,
    onToggleAttachment: () -> Unit,
    onSend: () -> Unit,
) {
    // The shell already applies the IME and navigation-bar insets to the whole column (bar
    // included), so the composer must not add them again: a second imePadding here would push
    // it a keyboard-height above the bar.
    val canSend = chatSendEnabled(draft) && enabled
    val iconInk = if (dark) ChatIconDark else ChatIconLight
    val sendLabel = stringResource(R.string.chat_send)
    val emojiLabel = stringResource(R.string.chat_emoji_picker)
    val attachmentLabel = stringResource(R.string.drop_files)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (dark) ChatBarDark else ChatBarLight)
            .border(1.dp, if (dark) ChatBarLineDark else ChatBarLineLight)
            .padding(vertical = 8.dp),
    ) {
        // The bar surface spans the canvas; the controls row sits on the page
        // grid so it lines up with the stream above it.
        GridColumn(maxWidth = AdaptiveLayout.conversationWidth) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
            ChatTapTarget(
                label = emojiLabel,
                onClick = onToggleEmoji,
                selected = emojiOpen,
                selectedFill = if (dark) ChatTileDark else ChatTileLight,
            ) {
                Text(
                    text = "☺",
                    color = if (paired) iconInk else iconInk.copy(alpha = CHAT_SEND_DISABLED_ALPHA),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            ChatTapTarget(
                label = attachmentLabel,
                onClick = onToggleAttachment,
                selected = attachmentOpen,
                selectedFill = if (dark) ChatTileDark else ChatTileLight,
            ) {
                Text(text = "+", color = iconInk, fontSize = 26.sp, fontWeight = FontWeight.Medium)
            }
            // A flat, rounded box rather than Material's outlined field: the Material field adds a
            // ~16dp wrapper that would swallow keyboard focus (and with it the Send IME action).
            TextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .onFocusChanged { focusState ->
                        // Design §4: focusing the input brings the end of the stream into view.
                        if (focusState.isFocused) onFieldFocus()
                    },
                enabled = enabled,
                textStyle = TextStyle(fontSize = 15.sp),
                placeholder = {
                    Text(
                        text = placeholder,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                // Design §4: Enter in the input sends. `chatSendEnabled` is the same gate the
                // button uses, so Enter on an empty draft does nothing rather than insert a line.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                maxLines = 4,
                shape = RoundedCornerShape(10.dp),
                colors = TextFieldDefaults.colors(
                    focusedTextColor = if (dark) ChatPeerTextDark else ChatPeerTextLight,
                    unfocusedTextColor = if (dark) ChatPeerTextDark else ChatPeerTextLight,
                    disabledTextColor = if (dark) ChatPeerMetaDark else ChatPeerMetaLight,
                    focusedPlaceholderColor = if (dark) ChatPeerMetaDark else ChatPeerMetaLight,
                    unfocusedPlaceholderColor = if (dark) ChatPeerMetaDark else ChatPeerMetaLight,
                    disabledPlaceholderColor = if (dark) ChatPeerMetaDark else ChatPeerMetaLight,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedContainerColor = if (dark) ChatInputDark else ChatInputLight,
                    unfocusedContainerColor = if (dark) ChatInputDark else ChatInputLight,
                    disabledContainerColor = if (dark) ChatInputDark else ChatInputLight,
                    cursorColor = primary,
                ),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .widthIn(min = 56.dp)
                    .clip(ChatBubbleShape)
                    // Design §3 `.send.off`: the same button at ~40% opacity, so nothing shifts.
                    .background(primary.copy(alpha = if (canSend) 1f else CHAT_SEND_DISABLED_ALPHA))
                    .clickable(enabled = canSend, onClick = onSend)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = sendLabel,
                    color = Color.White.copy(alpha = if (canSend) 1f else CHAT_SEND_DISABLED_ALPHA),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
                // Unpaired the placeholder already says why the box is inert, so the note and the
                // spacing that carried it exist only for the paired 对方可见 sentence.
                if (paired) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.peer_visible),
                        color = if (dark) ChatPeerMetaDark else ChatPeerMetaLight,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
            }
        }
    }
}

/** A 48×48dp target whose label is also its spoken name — the chat controls never shrink below it. */
@Composable
private fun ChatTapTarget(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    selectedFill: Color = Color.Transparent,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(ChatBubbleShape)
            .then(if (selected) Modifier.background(selectedFill) else Modifier)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun ChatActionSheet(
    dark: Boolean,
    copyText: String?,
    onRecall: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val copyLabel = stringResource(R.string.copy)
    val recallLabel = stringResource(R.string.chat_recall)
    val cancelLabel = stringResource(R.string.remove_file_cancel)
    val ink = if (dark) ChatPeerTextDark else ChatPeerTextLight
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var copied by remember { mutableStateOf(false) }
        val divider = if (dark) ChatBarLineDark else ChatBarLineLight
        Box(
            modifier = Modifier
                .fillMaxSize()
                // The scrim is the dismiss affordance; it sits under the card so taps on rows still land.
                .background(ChatScrim)
                .clickable(onClick = onDismiss),
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .clip(ChatSheetShape)
                    .background(if (dark) ChatBarDark else ChatBarLight),
            ) {
                if (copyText != null) {
                    ChatSheetRow(
                        label = if (copied) "$copyLabel ✓" else copyLabel,
                        ink = ink,
                        onClick = {
                            copyToClipboard(context, copyText)
                            copied = true
                        },
                    )
                    HorizontalDivider(color = divider)
                }
                if (onRecall != null) {
                    ChatSheetRow(
                        label = recallLabel,
                        ink = if (dark) ChatDestructiveDark else ChatDestructive,
                        onClick = {
                            onRecall()
                            onDismiss()
                        },
                    )
                    HorizontalDivider(color = divider)
                }
                ChatSheetRow(label = cancelLabel, ink = ink, onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun ChatSheetRow(label: String, ink: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(label, color = ink, fontSize = 15.sp)
    }
}
