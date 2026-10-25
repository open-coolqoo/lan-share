package com.lanshare

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.text.TextUtils
import android.view.View
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val PageBackground = Color(0xFFF5F7F4)
private val Surface = Color.White
private val SurfaceAlt = Color(0xFFEEF5F1)
private val Line = Color(0xFFDCE5DF)
private val TextPrimary = Color(0xFF17211D)
private val Muted = Color(0xFF637269)
private val Green = Color(0xFF0F6B55)
private val GreenStrong = Color(0xFF0B513F)
private val Red = Color(0xFFB42318)
private val Amber = Color(0xFF9A6700)
private val PageBackgroundArgb = 0xFFF5F7F4.toInt()
private val DarkPageBackgroundArgb = 0xFF111512.toInt()
private val BackToastBackgroundArgb = 0xFF102E26.toInt()
private val BackToastBackground = Color(BackToastBackgroundArgb)
private val BackToastBorder = Color(0xFF2E7D67)
private val BackToastText = Color.White
private val DarkBackToastBackgroundArgb = 0xFFBFEFD9.toInt()
private val DarkBackToastBackground = Color(DarkBackToastBackgroundArgb)
private val DarkBackToastBorderArgb = 0xFF4DB78E.toInt()
private val DarkBackToastBorder = Color(DarkBackToastBorderArgb)
private val DarkBackToastTextArgb = 0xFF102E26.toInt()
private val DarkBackToastText = Color(DarkBackToastTextArgb)
private val DarkPageBackground = Color(DarkPageBackgroundArgb)
private val DarkSurface = Color(0xFF1B211D)
private val DarkSurfaceAlt = Color(0xFF24302A)
private val DarkLine = Color(0xFF34463D)
private val DarkTextPrimary = Color(0xFFEAF2ED)
private val DarkMuted = Color(0xFFA7B6AD)
private val DarkErrorSurface = Color(0xFF3B1E1D)
private val DarkErrorLine = Color(0xFF7A3834)
internal val PanelShape = RoundedCornerShape(8.dp)
internal val PillShape = RoundedCornerShape(999.dp)
internal val LocalPrimaryColor = staticCompositionLocalOf { Green }
private val LocalAccentColor = staticCompositionLocalOf { GreenStrong }
internal val LocalDarkTheme = staticCompositionLocalOf { false }

private fun Modifier.progressSemantics(progress: Float): Modifier = semantics {
    progressBarRangeInfo = ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f)
}
private val SupportedAppLocales = listOf(
    AppLocale("en", "English", "English"),
    AppLocale("es", "Español", "Spanish"),
    AppLocale("fr", "Français", "French"),
    AppLocale("de", "Deutsch", "German"),
    AppLocale("ja", "日本語", "Japanese"),
    AppLocale("ko", "한국어", "Korean"),
    AppLocale("pt-BR", "Português", "Portuguese"),
    AppLocale("hi", "हिन्दी", "Hindi"),
    AppLocale("ar", "العربية", "Arabic"),
    AppLocale("id", "Bahasa Indonesia", "Indonesian"),
    AppLocale("vi", "Tiếng Việt", "Vietnamese"),
    AppLocale("th", "ไทย", "Thai"),
    AppLocale("zh-CN", "简体中文", "Simplified Chinese"),
    AppLocale("zh-TW", "繁體中文", "Traditional Chinese"),
)

private data class AppLocale(val code: String, val label: String, val englishLabel: String)

private const val PUBLIC_PAGE_SLUG_EXTRA = "com.lanshare.PUBLIC_PAGE_SLUG"
private const val PUBLIC_PAGE_LOCALE_EXTRA = "com.lanshare.PUBLIC_PAGE_LOCALE"
private const val PUBLIC_PAGE_DARK_THEME_EXTRA = "com.lanshare.PUBLIC_PAGE_DARK_THEME"

private enum class BottomTab {
    CONNECT,
    TRANSFER,
    SETTINGS,
}

private enum class StepStatus {
    WAIT,
    CHECKING,
    PASSED,
    PAIRING,
    DONE,
    FAILED,
    SUCCESS,
}

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleSharedIntent(intent)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = appPageBackground()) {
                    AppScreen(viewModel = viewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        viewModel.onAppForegrounded()
    }

    override fun onStop() {
        super.onStop()
        viewModel.onAppBackgrounded()
    }

    private fun handleSharedIntent(intent: Intent) {
        val sharedUris = sharedUrisFromIntent(intent)
        if (sharedUris.isEmpty()) return
        if (shouldTakePersistableUriPermission(intent.flags)) {
            sharedUris.forEach { uri ->
                runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }
        viewModel.enqueueSharedUris(sharedUris)
        clearHandledShareIntent(intent)
    }
}

internal fun shouldTakePersistableUriPermission(intentFlags: Int): Boolean =
    intentFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0 &&
        intentFlags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0

/**
 * Keeps read access to a picked tree for the rest of the app's life, so the
 * files can still be offered after the system picker has taken its grant back.
 * A provider that refuses persistable grants simply keeps the one-shot grant.
 */
internal fun retainFolderReadAccess(resolver: android.content.ContentResolver, treeUri: Uri) {
    runCatching {
        resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

internal fun clearHandledShareIntent(intent: Intent) {
    intent.action = null
    intent.removeExtra(Intent.EXTRA_STREAM)
    intent.clipData = null
}

internal fun sharedUrisFromIntent(intent: Intent?): List<Uri> {
    if (intent == null) return emptyList()
    val action = intent.action
    if (action != null && action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) {
        return emptyList()
    }
    val fromExtras = when (action) {
        Intent.ACTION_SEND -> listOfNotNull(intent.streamExtra())
        Intent.ACTION_SEND_MULTIPLE -> intent.streamExtraList()
        else -> emptyList()
    }
    val fromClipData = buildList {
        val clipData = intent.clipData ?: return@buildList
        for (index in 0 until clipData.itemCount) {
            clipData.getItemAt(index).uri?.let(::add)
        }
    }
    return (fromExtras + fromClipData).distinct()
}

private fun Intent.streamExtra(): Uri? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
    }

private fun Intent.streamExtraList(): List<Uri> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
    } else {
        @Suppress("DEPRECATION")
        getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
    }

internal enum class BackNavigationResult {
    Consumed,
    Exit,
}

internal class BackNavigationController(private val exitWindowMs: Long = 2_000L) {
    var drawerOpen by mutableStateOf(false)
    var exitToastVisible by mutableStateOf(false)
        private set
    var exitToastPulse by mutableStateOf(0)
        private set
    var closeDrawer: (() -> Unit)? = null

    private var lastHomeBackAtMs = 0L

    fun handleBackPressed(nowMs: Long): BackNavigationResult {
        if (drawerOpen) {
            hideExitToast()
            drawerOpen = false
            closeDrawer?.invoke()
            return BackNavigationResult.Consumed
        }

        if (exitToastVisible && nowMs - lastHomeBackAtMs <= exitWindowMs) {
            return BackNavigationResult.Exit
        }

        lastHomeBackAtMs = nowMs
        exitToastVisible = true
        exitToastPulse += 1
        return BackNavigationResult.Consumed
    }

    fun hideExitToast() {
        exitToastVisible = false
    }
}

@SuppressLint("LocalContextGetResourceValueCall")
@Composable
internal fun AppScreen(
    viewModel: AppViewModel = viewModel(),
    backNavigationController: BackNavigationController = remember { BackNavigationController() },
) {
    val state by viewModel.state.collectAsState()
    val primaryColor = state.siteConfig?.primaryColor?.let { parseHexColor(it) }?.let { Color(it) } ?: Green
    val accentColor = state.siteConfig?.accentColor?.let { parseHexColor(it) }?.let { Color(it) } ?: GreenStrong
    val darkTheme = state.darkTheme
    var selectedTab by rememberSaveable { mutableStateOf(BottomTab.CONNECT) }
    // One-shot per room: the first channel-ready moves the shell to 传输. The
    // flag survives a transient reconnect (the room does), so its recovery
    // does not; it resets once the room is gone.
    var pairedInSession by rememberSaveable { mutableStateOf(state.channelReady) }
    LaunchedEffect(state.joinedRoom == null) {
        if (state.joinedRoom == null) pairedInSession = false
    }
    LaunchedEffect(state.channelReady) {
        if (shouldAutoSelectTransferTab(
                wasPaired = pairedInSession,
                channelReady = state.channelReady,
                onTransferTab = selectedTab == BottomTab.TRANSFER,
            )
        ) {
            selectedTab = BottomTab.TRANSFER
        }
        if (state.channelReady) pairedInSession = true
    }
    var contactDialogVisible by remember { mutableStateOf(false) }
    var disconnectDialogVisible by remember { mutableStateOf(false) }
    var hotspotGuideVisible by remember { mutableStateOf(false) }
    val baseContext = LocalContext.current
    val localizedContext = remember(baseContext, state.locale) { localizedAndroidContext(baseContext, state.locale) }
    val layoutDirection = remember(state.locale) { localizedLayoutDirection(state.locale) }
    var qrScannerNoPairingCodeMessage by remember { mutableStateOf<String?>(null) }
    var pendingIncomingFileIds by remember { mutableStateOf<List<String>>(emptyList()) }
    val legacyStoragePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            viewModel.acceptIncoming(pendingIncomingFileIds)
        }
        pendingIncomingFileIds = emptyList()
    }
    val acceptIncoming: (List<String>) -> Unit = { fileIds ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(baseContext, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.acceptIncoming(fileIds)
        } else {
            pendingIncomingFileIds = fileIds.distinct()
            legacyStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
    val acceptSingleIncoming: (String) -> Unit = { fileId -> acceptIncoming(listOf(fileId)) }
    val requestDisconnect: () -> Unit = {
        if (state.localTransfers.any { it.status == TransferCardStatus.Transferring } ||
            state.peerTransfers.any { it.status == TransferCardStatus.ReceivingFromWeb }) {
            disconnectDialogVisible = true
        } else {
            viewModel.disconnect()
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.sendSelectedUris(uris)
    }
    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        viewModel.sendSelectedUris(uris)
    }
    // A cancelled pick answers null: nothing to walk and no notice, exactly like cancelling the file picker.
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        treeUri?.let {
            retainFolderReadAccess(baseContext.contentResolver, it)
            viewModel.sendFolderTree(it)
        }
    }
    val qrScannerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val noPairingCodeMessage = qrScannerNoPairingCodeMessage ?: baseContext.getString(R.string.qr_scanner_no_code)
            viewModel.joinScannedCode(QrScannerActivity.resultRawValue(result.data), noPairingCodeMessage)
        }
    }

    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalResources provides localizedContext.resources,
        LocalConfiguration provides localizedContext.resources.configuration,
        LocalLayoutDirection provides layoutDirection,
        LocalPrimaryColor provides primaryColor,
        LocalAccentColor provides accentColor,
        LocalDarkTheme provides darkTheme,
    ) {
        ApplySystemBars(darkTheme)
        val exitToastText = stringResource(R.string.press_back_again_to_exit)
        val noPairingCodeMessage = stringResource(R.string.qr_scanner_no_code)
        val downloadsFolderName = stringResource(R.string.downloads_folder_name)
        val downloadCompleteToastText = stringResource(R.string.download_complete_toast, downloadsFolderName)
        LaunchedEffect(viewModel) {
            viewModel.navigationEvents.collect { event ->
                when (event) {
                    AppNavigationEvent.OpenShare -> {
                        // The conversation lives in the 传输 tab now, so a shared file simply
                        // reveals that tab: the queue (待发送 + 发送全部) sits on the same page.
                        when (navigationTargetHomeTab(event, conversationVisible = state.channelReady)) {
                            ConnectionHomeTab.SELECTED -> Unit
                            ConnectionHomeTab.TRANSFER -> selectedTab = BottomTab.TRANSFER
                        }
                    }
                }
            }
        }
        LaunchedEffect(backNavigationController.exitToastPulse) {
            if (backNavigationController.exitToastPulse > 0) {
                delay(2_000L)
                backNavigationController.hideExitToast()
            }
        }
        LaunchedEffect(state.downloadCompleteToastId, downloadCompleteToastText) {
            val toastId = state.downloadCompleteToastId ?: return@LaunchedEffect
            delay(4_000L)
            viewModel.clearDownloadCompleteToast(toastId)
        }
        BackHandler(enabled = true) {
            // ‹ no longer exists on the chat: 传输 and 设置 both fall back to 连接, and only 连接
            // runs the existing double-back exit.
            if (selectedTab != BottomTab.CONNECT) {
                selectedTab = BottomTab.CONNECT
                backNavigationController.hideExitToast()
            } else {
                when (backNavigationController.handleBackPressed(System.currentTimeMillis())) {
                    BackNavigationResult.Consumed -> Unit
                    BackNavigationResult.Exit -> (baseContext as? ComponentActivity)?.finish()
                }
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(appPageBackground())
                    // The IME moves the whole column up, bottom bar included, so the composer and
                    // the tab bar share the space above the keyboard instead of overlapping it.
                    .imePadding()
                    .statusBarsPadding(),
            ) {
                TopBar(state = state)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    when (selectedTab) {
                        BottomTab.CONNECT -> ConnectionTabScreen(
                            state = state,
                            viewModel = viewModel,
                            onScanConnect = {
                                qrScannerNoPairingCodeMessage = noPairingCodeMessage
                                qrScannerLauncher.launch(QrScannerActivity.createIntent(baseContext, state.locale))
                            },
                            onOpenShare = { selectedTab = BottomTab.TRANSFER },
                            onDisconnect = requestDisconnect,
                            onOpenHotspotGuide = { hotspotGuideVisible = true },
                        )
                        // 传输 is the conversation: the same screen before and after pairing.
                        BottomTab.TRANSFER -> ChatConversationScreen(
                            state = state,
                            viewModel = viewModel,
                            onGoToConnect = { selectedTab = BottomTab.CONNECT },
                            onOpenSettings = { selectedTab = BottomTab.SETTINGS },
                            onPickFiles = { filePicker.launch(arrayOf("*/*")) },
                            onPickGallery = { galleryPicker.launch("image/*") },
                            onPickFolder = { folderPicker.launch(null) },
                            onAcceptIncoming = acceptSingleIncoming,
                            onDisconnect = requestDisconnect,
                        )
                        BottomTab.SETTINGS -> SettingsTabScreen(
                            state = state,
                            darkTheme = darkTheme,
                            onToggleTheme = viewModel::setDarkTheme,
                            onLocale = viewModel::setLocale,
                            onShowContact = { contactDialogVisible = true },
                            onOpenDownloads = { openDownloadFolder(baseContext) },
                            onOpenPage = { slug ->
                                baseContext.startActivity(
                                    publicPageIntent(
                                        context = baseContext,
                                        locale = state.locale,
                                        slug = slug,
                                        darkTheme = darkTheme,
                                    ),
                                )
                            },
                        )
                    }
                }
                AppBottomNavigation(
                    selectedTab = selectedTab,
                    pendingIncomingCount = if (state.channelReady) {
                        state.peerTransfers.count { it.actionLabel == TransferCardAction.Accept }
                    } else {
                        0
                    },
                    sendCount = if (state.channelReady) state.localTransfers.size else 0,
                    onTabSelect = { selectedTab = it },
                )
            }
            if (backNavigationController.exitToastVisible) {
                BackExitToast(
                    text = exitToastText,
                    darkTheme = darkTheme,
                    modifier = Modifier
                        .align(backToastOverlayAlignment())
                        .padding(horizontal = 24.dp),
                )
            }
            state.downloadCompleteToastId?.let {
                DownloadCompleteToast(
                    text = downloadCompleteToastText,
                    darkTheme = darkTheme,
                    onClick = {
                        openDownloadFolder(baseContext)
                        viewModel.clearDownloadCompleteToast(it)
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 96.dp),
                )
            }
            if (contactDialogVisible) {
                ContactDialog(
                    email = contactDialogEmail(state),
                    onDismiss = { contactDialogVisible = false },
                )
            }
            if (directFailureState(state).visible) {
                DirectConnectionFailureDialog(
                    onDismiss = viewModel::clearDirectConnectionFailure,
                    onUseHotspot = {
                        viewModel.clearDirectConnectionFailure()
                        hotspotGuideVisible = true
                    },
                )
            }
            if (state.roomCreateFailureNotice) {
                RoomCreateFailureDialog(
                    webOrigin = state.origin,
                    onRetry = viewModel::createRoom,
                    onDismiss = viewModel::dismissCreateFailureNotice,
                )
            }
            if (hotspotGuideVisible) {
                HotspotConnectionGuideDialog(
                    onDismiss = { hotspotGuideVisible = false },
                    onOpenHotspotSettings = { openHotspotSettings(baseContext) },
                    onOpenNetworkSettings = { openNetworkSettings(baseContext) },
                    onConfirm = {
                        hotspotGuideVisible = false
                        viewModel.retryAfterNetworkChange()
                    },
                )
            }
            if (disconnectDialogVisible) {
                AlertDialog(
                    onDismissRequest = { disconnectDialogVisible = false },
                    title = { Text(stringResource(R.string.disconnect_confirm_title), color = appTextPrimary()) },
                    text = { Text(stringResource(R.string.disconnect_confirm_message), color = appTextPrimary()) },
                    dismissButton = {
                        TextButton(onClick = { disconnectDialogVisible = false }) {
                            Text(stringResource(R.string.remove_file_cancel), color = LocalPrimaryColor.current)
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            disconnectDialogVisible = false
                            viewModel.disconnect()
                        }) {
                            Text(stringResource(R.string.disconnect), color = Red)
                        }
                    },
                    containerColor = appPanelSurface(),
                )
            }
            if (state.reviewPromptVisible) {
                ReviewPromptDialog(
                    onDismiss = viewModel::dismissReviewPrompt,
                    onRate = {
                        viewModel.dismissReviewPrompt()
                        openPlayStoreReview(baseContext)
                    },
                )
            }
        }
    }
}

    @Composable
    private fun ApplySystemBars(darkTheme: Boolean) {
        val view = LocalView.current
        SideEffect {
            val window = (view.context as? ComponentActivity)?.window ?: return@SideEffect
            @Suppress("DEPRECATION")
            run {
                window.statusBarColor = (if (darkTheme) DarkPageBackground else PageBackground).toArgb()
                window.navigationBarColor = (if (darkTheme) DarkPageBackground else PageBackground).toArgb()
                window.decorView.systemUiVisibility = if (darkTheme) {
                    0
                } else {
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                }
            }
        }
    }

@Composable
private fun ConnectionTabScreen(
    state: AppUiState,
    viewModel: AppViewModel,
    onScanConnect: () -> Unit,
    onOpenShare: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenHotspotGuide: () -> Unit,
) {
    // Wide canvases (tablets, unfolded phones) read as the two-column pairing
    // board; phones keep the single column. The cap follows the page grid.
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            GridColumn(maxWidth = if (state.channelReady || !wide) AdaptiveLayout.readableWidth else AdaptiveLayout.wideWidth) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (state.channelReady) {
                        pairedSummaryState(state)?.let { summary ->
                            PairedSummaryPanel(summary, onDisconnect = onDisconnect)
                        }
                        ConnectionStatusPanel(state, viewModel)
                        NetworkConnectionHelpCard(onClick = onOpenHotspotGuide)
                        DiagnosticsPanel(state)
                    } else if (wide) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                PeerCard(state, viewModel, onScanConnect)
                                ConnectionStatusPanel(state, viewModel)
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                SelfCard(state, viewModel)
                                PendingSharedFilesPanel(state, onOpenShare)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column(Modifier.weight(1f)) { NetworkConnectionHelpCard(onClick = onOpenHotspotGuide) }
                            Column(Modifier.weight(1f)) { DiagnosticsPanel(state) }
                        }
                    } else {
                        ConnectionArea(state, viewModel, onScanConnect)
                        PendingSharedFilesPanel(state, onOpenShare)
                        NetworkConnectionHelpCard(onClick = onOpenHotspotGuide)
                        DiagnosticsPanel(state)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }
    }
}

@Composable
private fun SettingsTabScreen(
    state: AppUiState,
    darkTheme: Boolean,
    onToggleTheme: (Boolean) -> Unit,
    onLocale: (String) -> Unit,
    onShowContact: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenPage: (String) -> Unit,
) {
    var languageDialogVisible by remember { mutableStateOf(false) }
    val locale = state.locale
    val pageLinks = drawerPageLinks(state)

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            // One reading column on the page grid: the cards' edges line up
            // with the other tabs and the top bar on wide canvases.
            GridColumn(maxWidth = AdaptiveLayout.wideWidth) {
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text(
                        stringResource(R.string.tab_settings),
                        color = appTextPrimary(),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 20.dp),
                    )
                    SettingsSectionCard(stringResource(R.string.settings_section_appearance)) {
                        SettingsSwitchRow(
                            iconRes = R.drawable.ic_settings_theme,
                            title = stringResource(R.string.dark_theme),
                            checked = darkTheme,
                            onCheckedChange = onToggleTheme,
                        )
                        SettingsRowDivider()
                        SettingsRow(
                            iconRes = R.drawable.ic_settings_language,
                            title = stringResource(R.string.language),
                            subtitle = localeLabel(locale),
                            onClick = { languageDialogVisible = true },
                        )
                    }
                    SettingsSectionCard(stringResource(R.string.tab_transfer)) {
                        SettingsRow(
                            iconRes = R.drawable.ic_folder,
                            title = stringResource(R.string.open_downloads_folder),
                            onClick = onOpenDownloads,
                        )
                    }
                    SettingsSectionCard(stringResource(R.string.settings_section_help)) {
                        SettingsRow(stringResource(R.string.security)) { onOpenPage(pageLinks["security"] ?: "security") }
                        SettingsRowDivider()
                        SettingsRow(stringResource(R.string.compatibility)) { onOpenPage(pageLinks["compatibility"] ?: "compatibility") }
                        SettingsRowDivider()
                        SettingsRow(iconRes = R.drawable.ic_settings_contact, title = stringResource(R.string.contact), onClick = onShowContact)
                    }
                    SettingsSectionCard(stringResource(R.string.settings_section_legal)) {
                        SettingsRow(stringResource(R.string.about_us)) { onOpenPage(pageLinks["about"] ?: "about") }
                        SettingsRowDivider()
                        SettingsRow(stringResource(R.string.privacy_policy)) { onOpenPage(pageLinks["privacy"] ?: "privacy") }
                        SettingsRowDivider()
                        SettingsRow(stringResource(R.string.terms)) { onOpenPage(pageLinks["terms"] ?: "terms") }
                    }
                    Text(
                        stringResource(R.string.settings_version_format, BuildConfig.VERSION_NAME),
                        color = appMuted(),
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }
    }

    if (languageDialogVisible) {
        AlertDialog(
            onDismissRequest = { languageDialogVisible = false },
            title = { Text(stringResource(R.string.settings_language_dialog_title), color = appTextPrimary(), fontWeight = FontWeight.Bold) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(SupportedAppLocales.size) { index ->
                        val option = SupportedAppLocales[index]
                        LanguageOptionRow(
                            option = option,
                            selected = option.code == locale,
                            darkTheme = darkTheme,
                            enabled = true,
                            onClick = {
                                onLocale(option.code)
                                languageDialogVisible = false
                            },
                        )
                    }
                }
            },
            confirmButton = {},
            containerColor = appPanelSurface(),
            titleContentColor = appTextPrimary(),
            textContentColor = appTextPrimary(),
        )
    }
}

@Composable
private fun SettingsSectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = appMuted(), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(PanelShape)
                .background(appPanelSurface())
                .border(1.dp, appLine(), PanelShape),
            content = content,
        )
    }
}

@Composable
private fun SettingsRow(
    iconRes: Int,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    val primary = LocalPrimaryColor.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = primary,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = appTextPrimary(), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            if (subtitle != null) {
                Text(subtitle, color = appMuted(), fontSize = 13.sp)
            }
        }
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = appMuted(),
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun SettingsRow(title: String, onClick: () -> Unit) {
    SettingsRow(iconRes = R.drawable.ic_settings_help, title = title, subtitle = null, onClick = onClick)
}

@Composable
private fun SettingsSwitchRow(iconRes: Int, title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val primary = LocalPrimaryColor.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = primary,
            modifier = Modifier.size(22.dp),
        )
        Text(title, color = appTextPrimary(), fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        CompactToggle(checked = checked)
    }
}

@Composable
private fun SettingsRowDivider() {
    HorizontalDivider(color = appLine(), modifier = Modifier.padding(start = 48.dp))
}

@Composable
private fun AppBottomNavigation(
    selectedTab: BottomTab,
    pendingIncomingCount: Int,
    sendCount: Int,
    onTabSelect: (BottomTab) -> Unit,
) {
    NavigationBar(
        containerColor = appPanelSurface(),
        contentColor = appTextPrimary(),
        tonalElevation = 0.dp,
    ) {
        BottomTabItem(
            label = stringResource(R.string.tab_connect),
            iconRes = R.drawable.ic_tab_connect,
            selected = selectedTab == BottomTab.CONNECT,
            onClick = { onTabSelect(BottomTab.CONNECT) },
        )
        BottomTabItem(
            label = stringResource(R.string.tab_transfer),
            iconRes = R.drawable.ic_tab_transfer,
            selected = selectedTab == BottomTab.TRANSFER,
            badgeCount = if (selectedTab != BottomTab.TRANSFER) sendCount + pendingIncomingCount else 0,
            onClick = { onTabSelect(BottomTab.TRANSFER) },
        )
        BottomTabItem(
            label = stringResource(R.string.tab_settings),
            iconRes = R.drawable.ic_tab_settings,
            selected = selectedTab == BottomTab.SETTINGS,
            onClick = { onTabSelect(BottomTab.SETTINGS) },
        )
    }
}

@Composable
private fun RowScope.BottomTabItem(
    label: String,
    iconRes: Int,
    selected: Boolean,
    badgeCount: Int = 0,
    onClick: () -> Unit,
) {
    val primary = LocalPrimaryColor.current
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = {
            if (badgeCount > 0) {
                BadgedBox(badge = { Badge { Text(badgeCount.coerceAtMost(99).toString()) } }) {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = label,
                    )
                }
            } else {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = label,
                )
            }
        },
        label = { Text(label) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = Color.White,
            selectedTextColor = primary,
            indicatorColor = primary,
            unselectedIconColor = appMuted(),
            unselectedTextColor = appMuted(),
        ),
    )
}

class PublicPageActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val slug = intent.getStringExtra(PUBLIC_PAGE_SLUG_EXTRA).orEmpty()
        val locale = intent.getStringExtra(PUBLIC_PAGE_LOCALE_EXTRA).orEmpty().ifBlank { "en" }
        val darkTheme = intent.getBooleanExtra(PUBLIC_PAGE_DARK_THEME_EXTRA, false)
        val publicContent = PublicContentRepository.fromAssets(this)
        val page = publicContent.page(slug, locale) ?: run {
            finish()
            return
        }
        val siteName = publicContent.siteConfig.siteName
        val siteDescription = publicContent.siteConfig.description
        val localizedContext = localizedAndroidContext(this, locale)
        val layoutDirection = localizedLayoutDirection(locale)
        setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalContext provides localizedContext,
                    LocalResources provides localizedContext.resources,
                    LocalConfiguration provides localizedContext.resources.configuration,
                    LocalLayoutDirection provides layoutDirection,
                    LocalPrimaryColor provides Green,
                    LocalAccentColor provides GreenStrong,
                    LocalDarkTheme provides darkTheme,
                ) {
                    ApplySystemBars(darkTheme = darkTheme)
                    PublicPageActivityContent(
                        page = page,
                        siteName = siteName,
                        siteDescription = siteDescription,
                        onBack = { finish() },
                    )
                }
            }
        }
    }
}

@Composable
private fun PublicPageActivityContent(
    page: PublicPage,
    siteName: String,
    siteDescription: String,
    onBack: () -> Unit,
) {
    PublicPageContent(
        page = page,
        siteName = siteName,
        siteDescription = siteDescription,
        onBack = onBack,
    )
}

@Composable
private fun PublicPageContent(
    page: PublicPage,
    siteName: String,
    siteDescription: String,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(appPageBackground())
            .statusBarsPadding(),
    ) {
        PublicPageTopBar(
            siteName = siteName,
            siteDescription = siteDescription,
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Panel {
                        Text(page.title, color = appTextPrimary(), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        if (page.updatedAt.isNotBlank()) {
                            Text(page.updatedAt, color = appMuted(), fontSize = 12.sp)
                        }
                        Text(page.body, color = appTextPrimary(), fontSize = 15.sp, lineHeight = 22.sp)
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }
        }
    }
}

@Composable
private fun PublicPageTopBar(siteName: String, siteDescription: String, onBack: () -> Unit) {
    val appName = stringResource(R.string.app_name)
    val title = siteName.ifBlank { appName }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (LocalDarkTheme.current) appPanelSurface() else Color.White.copy(alpha = 0.94f))
            .border(1.dp, appLine())
            .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Text("‹", color = appTextPrimary(), fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
            BrandIcon(title)
            Column {
                Text(title, color = appTextPrimary(), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(publicPageHeaderDescription(siteDescription), color = appMuted(), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

internal fun publicPageHeaderDescription(siteDescription: String?): String = homeHeaderDescription(siteDescription)

private fun publicPageIntent(
    context: Context,
    locale: String,
    slug: String,
    darkTheme: Boolean,
): Intent =
    Intent(context, PublicPageActivity::class.java)
        .putExtra(PUBLIC_PAGE_LOCALE_EXTRA, locale)
        .putExtra(PUBLIC_PAGE_SLUG_EXTRA, slug)
        .putExtra(PUBLIC_PAGE_DARK_THEME_EXTRA, darkTheme)

@Composable
private fun ContactDialog(email: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contact), color = appTextPrimary(), fontWeight = FontWeight.Bold) },
        text = { Text(email, color = appTextPrimary(), fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.contact_dialog_confirm), color = LocalPrimaryColor.current, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = appPanelSurface(),
        titleContentColor = appTextPrimary(),
        textContentColor = appTextPrimary(),
    )
}

@Composable
private fun DirectConnectionFailureDialog(
    onDismiss: () -> Unit,
    onUseHotspot: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.direct_connection_failed_title),
                    color = appTextPrimary(),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(34.dp)) {
                    Text("×", color = appTextPrimary(), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.direct_connection_failed_message), color = appTextPrimary(), fontSize = 15.sp)
                Text(stringResource(R.string.direct_connection_hotspot_hint), color = appMuted(), fontSize = 14.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.hotspot_later), color = LocalPrimaryColor.current)
            }
        },
        confirmButton = {
            TextButton(onClick = onUseHotspot) {
                Text(stringResource(R.string.use_same_hotspot), color = LocalPrimaryColor.current, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = appPanelSurface(),
        titleContentColor = appTextPrimary(),
        textContentColor = appTextPrimary(),
    )
}

@Composable
private fun RoomCreateFailureDialog(
    webOrigin: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.room_create_failed_title),
                    color = appTextPrimary(),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(34.dp)) {
                    Text("×", color = appTextPrimary(), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.room_create_failed_message), color = appTextPrimary(), fontSize = 15.sp)
                Text(stringResource(R.string.room_create_failed_web_hint), color = appMuted(), fontSize = 14.sp)
                Text(
                    text = webOrigin,
                    color = LocalPrimaryColor.current,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    modifier = Modifier.clickable {
                        runCatching { uriHandler.openUri(webOrigin) }
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.room_create_failed_dismiss), color = LocalPrimaryColor.current)
            }
        },
        confirmButton = {
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.room_create_failed_retry), color = LocalPrimaryColor.current, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = appPanelSurface(),
        titleContentColor = appTextPrimary(),
        textContentColor = appTextPrimary(),
    )
}

@Composable
private fun NetworkConnectionHelpCard(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PanelShape)
            .background(appSurfaceAlt())
            .border(1.dp, appLine(), PanelShape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.network_help_title), color = appTextPrimary(), fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.network_help_message), color = appMuted(), fontSize = 13.sp)
        WebButton(
            label = stringResource(R.string.use_same_hotspot),
            primary = false,
            modifier = Modifier.fillMaxWidth(),
            onClick = onClick,
        )
    }
}

@Composable
private fun HotspotConnectionGuideDialog(
    onDismiss: () -> Unit,
    onOpenHotspotSettings: () -> Unit,
    onOpenNetworkSettings: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hotspot_guide_title), color = appTextPrimary(), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.hotspot_guide_message), color = appTextPrimary(), fontSize = 15.sp)
                Text(stringResource(R.string.hotspot_guide_steps), color = appMuted(), fontSize = 14.sp)
                Text(stringResource(R.string.hotspot_guide_existing_network), color = appMuted(), fontSize = 14.sp)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.hotspot_later), color = LocalPrimaryColor.current)
                }
                TextButton(onClick = onOpenNetworkSettings) {
                    Text(stringResource(R.string.open_network_settings), color = LocalPrimaryColor.current)
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpenHotspotSettings) {
                    Text(stringResource(R.string.open_hotspot_settings), color = LocalPrimaryColor.current)
                }
                TextButton(onClick = onConfirm) {
                    Text(stringResource(R.string.hotspot_connected_confirm), color = LocalPrimaryColor.current, fontWeight = FontWeight.Bold)
                }
            }
        },
        containerColor = appPanelSurface(),
        titleContentColor = appTextPrimary(),
        textContentColor = appTextPrimary(),
    )
}

internal fun hotspotSettingsIntent(): Intent = Intent("android.settings.TETHER_SETTINGS")

internal fun networkSettingsIntent(): Intent = Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)

internal fun openHotspotSettings(context: Context) {
    val intents = listOf(
        hotspotSettingsIntent(),
        Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS),
        Intent(android.provider.Settings.ACTION_SETTINGS),
    )
    intents.firstOrNull { intent ->
        runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}

internal fun openNetworkSettings(context: Context) {
    val intents = listOf(
        networkSettingsIntent(),
        Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS),
        Intent(android.provider.Settings.ACTION_SETTINGS),
    )
    intents.firstOrNull { intent ->
        runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}

internal fun contactDialogEmail(state: AppUiState): String =
    state.siteConfig?.footer?.contactEmail.orEmpty()

@Composable
private fun ReviewPromptDialog(onDismiss: () -> Unit, onRate: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.review_prompt_title), color = appTextPrimary(), fontWeight = FontWeight.Bold) },
        text = { Text(stringResource(R.string.review_prompt_message), color = appTextPrimary(), fontSize = 15.sp) },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.review_prompt_later), color = LocalPrimaryColor.current)
            }
        },
        confirmButton = {
            TextButton(onClick = onRate) {
                Text(stringResource(R.string.review_prompt_rate), color = LocalPrimaryColor.current, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = appPanelSurface(),
        titleContentColor = appTextPrimary(),
        textContentColor = appTextPrimary(),
    )
}

internal fun playStoreMarketIntent(packageName: String): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

internal fun playStoreWebIntent(packageName: String): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

internal fun openPlayStoreReview(context: Context) {
    val packageName = context.packageName
    runCatching { context.startActivity(playStoreMarketIntent(packageName)) }
        .recoverCatching { context.startActivity(playStoreWebIntent(packageName)) }
}

@Composable
private fun BackExitToast(text: String, darkTheme: Boolean, modifier: Modifier = Modifier) {
    val background = if (darkTheme) DarkBackToastBackground else BackToastBackground
    val border = if (darkTheme) DarkBackToastBorder else BackToastBorder
    val textColor = if (darkTheme) DarkBackToastText else BackToastText
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(PillShape)
            .background(background)
            .border(1.dp, border, PillShape)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DownloadCompleteToast(
    text: String,
    darkTheme: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = if (darkTheme) DarkBackToastBackground else BackToastBackground
    val border = if (darkTheme) DarkBackToastBorder else BackToastBorder
    val textColor = if (darkTheme) DarkBackToastText else BackToastText
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(PillShape)
            .background(background)
            .border(1.dp, border, PillShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

internal fun downloadFolderIntent(): Intent =
    Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

internal fun openDownloadFolder(context: Context) {
    runCatching { context.startActivity(downloadFolderIntent()) }
}

@Composable
private fun TopBar(state: AppUiState) {
    val config = state.siteConfig
    val appName = stringResource(R.string.app_name)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (LocalDarkTheme.current) appPanelSurface() else Color.White.copy(alpha = 0.94f))
            .border(1.dp, appLine())
            .padding(vertical = 12.dp),
    ) {
        // The brand and status pill share the page grid (frame ≤ wideWidth,
        // 16dp inset, centred) so their edges line up with the cards below.
        GridColumn(maxWidth = AdaptiveLayout.wideWidth) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BrandIcon(config?.siteName ?: appName)
                    Column {
                        Text(config?.siteName ?: appName, color = appTextPrimary(), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(homeHeaderDescription(config?.description), color = appMuted(), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(phaseLabelText(state))
                }
            }
        }
    }
}

internal fun homeHeaderDescription(siteDescription: String?): String = "https://lanshare.example"

internal fun drawerHeaderDescription(siteDescription: String?): String = homeHeaderDescription(siteDescription)

@Composable
private fun CompactToggle(checked: Boolean) {
    val primary = LocalPrimaryColor.current
    Box(
        modifier = Modifier
            .size(width = 42.dp, height = 24.dp)
            .clip(PillShape)
            .background(if (checked) primary else appSurfaceAlt())
            .border(1.dp, if (checked) primary else appLine(), PillShape)
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

@Composable
private fun LanguageOptionRow(option: AppLocale, selected: Boolean, darkTheme: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val primary = LocalPrimaryColor.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PanelShape)
            .background(if (selected) primary.copy(alpha = 0.12f) else Color.Transparent)
            .alpha(if (enabled) 1f else 0.55f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(option.label, color = if (!enabled) appMuted() else if (selected) primary else appTextPrimary(), fontWeight = FontWeight.Bold)
            Text("${option.englishLabel} · ${option.code}", color = appMuted(), fontSize = 12.sp)
        }
        if (selected) Text("✓", color = primary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

private fun drawerPageLinks(state: AppUiState): Map<String, String> {
    val links = state.siteConfig?.footer?.links.orEmpty()
    return links.mapNotNull { link ->
        val slug = publicPageSlugFromUrl(link.url) ?: return@mapNotNull null
        if (isContactSlug(slug)) return@mapNotNull null
        slug.lowercase() to slug
    }.toMap()
}

private fun localeLabel(locale: String): String = SupportedAppLocales.firstOrNull { it.code == locale }?.label ?: locale

@Composable
private fun BrandIcon(label: String) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Transparent)
            .border(1.dp, appLine(), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(id = R.drawable.lanshare_icon),
            contentDescription = stringResource(R.string.brand_icon_content_description, label),
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
    }
}

@Composable
private fun ConnectionArea(state: AppUiState, viewModel: AppViewModel, onScanConnect: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PeerCard(state, viewModel, onScanConnect)
        SelfCard(state, viewModel)
        ConnectionStatusPanel(state, viewModel)
    }
}

@Composable
private fun PendingSharedFilesPanel(state: AppUiState, onOpenShare: () -> Unit) {
    if (state.pendingSharedFileCount <= 0) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PanelShape)
            .background(appSurfaceAlt())
            .border(1.dp, appLine(), PanelShape)
            .clickable(onClick = onOpenShare)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(R.string.pending_shared_files_title), color = appTextPrimary(), fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.pending_shared_files_body, state.pendingSharedFileCount),
                color = appMuted(),
                fontSize = 13.sp,
            )
        }
        Text(
            "›",
            color = LocalPrimaryColor.current,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SelfCard(state: AppUiState, viewModel: AppViewModel) {
    val actionsLocked = connectionActionsLocked(state)
    var nowMs by remember(state.createdCode, state.pairingExpiresAtMs) {
        mutableStateOf(System.currentTimeMillis())
    }
    LaunchedEffect(state.createdCode, state.pairingExpiresAtMs) {
        if (state.createdCode == null || state.pairingExpiresAtMs == null) return@LaunchedEffect
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    LaunchedEffect(state.codeCopied) {
        if (!state.codeCopied) return@LaunchedEffect
        delay(2_500L)
        viewModel.clearCodeCopied()
    }
    val pairing = pairingState(state, nowMs = nowMs)
    Panel {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Text(stringResource(R.string.this_device), color = appTextPrimary(), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            TtlBadge(pairing.ttlLabel)
        }
        FieldLabel(stringResource(R.string.local_code))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(82.dp)
                .clip(PanelShape)
                .background(if (LocalDarkTheme.current) Color(0xFF132A23) else Color(0xFFF0FAF5))
                .border(1.dp, if (LocalDarkTheme.current) Color(0xFF2D6A58) else Color(0xFFBAD9CB), PanelShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(pairing.localCode ?: "", color = appTextPrimary(), fontSize = 42.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
        }
        Text(
            stringResource(if (roomCreateFailing(state)) R.string.room_create_failed_hint else R.string.self_code_hint),
            color = appMuted(),
            fontSize = 13.sp,
        )
        PairingQrBlock(state = state, code = pairing.localCode)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            WebButton(label = if (pairing.canRefreshCode) stringResource(R.string.refresh_code) else stringResource(R.string.create_code), primary = true, enabled = !actionsLocked, onClick = viewModel::createRoom)
            WebButton(label = if (state.codeCopied) stringResource(R.string.copied) else stringResource(R.string.copy), primary = false, enabled = pairing.localCode != null && !actionsLocked, onClick = viewModel::copyCode)
        }
    }
}

@Composable
private fun PairingQrBlock(state: AppUiState, code: String?) {
    val primary = LocalPrimaryColor.current
    val qr = code?.let { runCatching { pairingQrImage(state.origin, it, state.locale, 176) }.getOrNull() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PanelShape)
            .background(appSurfaceAlt())
            .border(1.dp, appLine(), PanelShape)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(92.dp)
                .clip(PanelShape)
                .background(appPanelSurface())
                .border(1.dp, if (LocalDarkTheme.current) DarkLine else Color(0xFFBAD9CB), PanelShape),
            contentAlignment = Alignment.Center,
        ) {
            if (qr != null) {
                Image(bitmap = qr, contentDescription = stringResource(R.string.qr_code_pairing_content_description), modifier = Modifier.size(86.dp))
            } else {
                Text("...", color = primary, fontWeight = FontWeight.Black)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.scan_to_pair), color = appTextPrimary(), fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.pairing_qr_hint), color = appMuted(), fontSize = 13.sp)
        }
    }
}

@Composable
private fun PeerCard(state: AppUiState, viewModel: AppViewModel, onScanConnect: () -> Unit) {
    val pairing = pairingState(state)
    val connectionInProgress = peerConnectionInProgress(state)
    val actionsLocked = connectionActionsLocked(state)
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    Panel {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.connect), color = appTextPrimary(), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            StatusPill(stringResource(R.string.eight_digits))
        }
        FieldLabel(stringResource(R.string.peer_code))
        OutlinedTextField(
            value = pairing.peerCode,
            onValueChange = viewModel::setRoomCode,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.enter_peer_code)) },
            singleLine = true,
            enabled = !actionsLocked,
            colors = textFieldColors(),
        )
        WebButton(
            label = if (connectionInProgress) stringResource(R.string.phase_connecting) else stringResource(R.string.connect),
            primary = true,
            enabled = pairing.canJoinPeerCode && !actionsLocked,
            loading = connectionInProgress,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
                viewModel.joinRoom()
            },
        )
        WebButton(
            label = stringResource(R.string.scan_to_connect),
            primary = false,
            enabled = !actionsLocked,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
                onScanConnect()
            },
        )
        Text(stringResource(R.string.peer_code_hint), color = appMuted(), fontSize = 13.sp)
        if ((pairing.peerCode.length == 8 || pairing.peerCode.isBlank()) && state.roomError.isNotBlank()) {
            ErrorBanner(state.roomError)
        }
        if (pairing.peerCode.length == 8 && pairing.validation != PeerCodeValidation.OK) {
            ErrorBanner(stringResource(R.string.peer_code_error_format, validationLabel(pairing.validation)))
        }
    }
}

@Composable
private fun ConnectionStatusPanel(state: AppUiState, viewModel: AppViewModel) {
    val showSteps = isPeerConnectionMode(state)
    val actionsLocked = connectionActionsLocked(state)
    Panel {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.connection_status), color = appTextPrimary(), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            StatusPill(connectionDirectionText(state))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            DeviceBox(stringResource(R.string.local), state.localDeviceLabel)
            DeviceBox(stringResource(R.string.peer), state.peerDeviceLabel.ifBlank { stringResource(R.string.wait) })
        }
        if (showSteps) {
            StepRow(stringResource(R.string.check_network), if (state.joinedRoom != null) StepStatus.PASSED else StepStatus.CHECKING)
            StepRow(stringResource(R.string.check_code), if (state.joinedRoom != null) StepStatus.PASSED else StepStatus.WAIT)
            StepRow(stringResource(R.string.pair), when {
                state.channelReady -> StepStatus.DONE
                state.signalingReady -> StepStatus.PAIRING
                else -> StepStatus.WAIT
            })
            StepRow(stringResource(R.string.result), if (state.channelReady) StepStatus.SUCCESS else StepStatus.WAIT)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                WebButton(stringResource(R.string.signal), primary = true, enabled = state.joinedRoom != null && !actionsLocked, onClick = viewModel::connectSignaling)
                WebButton(stringResource(R.string.start_direct_path), primary = false, enabled = state.signalingReady && !actionsLocked, onClick = viewModel::createOffer)
            }
        } else {
            Text(stringResource(R.string.hidden_steps_hint), color = appMuted(), fontSize = 13.sp)
        }
    }
}

@Composable
private fun PairedSummaryPanel(summary: PairedSummaryState, onDisconnect: () -> Unit) {
    Panel {
        FieldLabel(stringResource(R.string.paired_code))
        Text(summary.code, color = appTextPrimary(), fontSize = 32.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
        Text(stringResource(R.string.paired_hint), color = appMuted(), fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            DeviceBox(stringResource(R.string.local), summary.localDevice)
            DeviceBox(stringResource(R.string.peer), summary.peerDevice)
        }
        WebButton(stringResource(R.string.disconnect), primary = false, enabled = summary.canDisconnect, modifier = Modifier.fillMaxWidth(), onClick = onDisconnect)
    }
}

@Composable
private fun TransferPanel(
    state: AppUiState,
    viewModel: AppViewModel,
    onPickFiles: () -> Unit,
    onPickGallery: () -> Unit,
    onAcceptIncoming: (List<String>) -> Unit,
    onAcceptSingleIncoming: (String) -> Unit,
) {
    Panel {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.file_transfer), color = appTextPrimary(), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            StatusPill(connectionPathLabel(state.connectionPath))
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(PanelShape)
                .border(1.dp, appLine(), PanelShape)
                .background(appSurfaceAlt())
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.drop_files), color = appTextPrimary(), fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    WebButton(stringResource(R.string.files), primary = true, enabled = state.channelReady, onClick = onPickFiles)
                    WebButton(stringResource(R.string.gallery), primary = false, enabled = state.channelReady, onClick = onPickGallery)
                }
            }
        }
        FileList(
            title = stringResource(R.string.local_files),
            emptyText = stringResource(R.string.no_file_selected),
            listState = localFilesState(state),
            batchLabel = stringResource(R.string.cancel_all),
            batchEnabled = state.localTransfers.isNotEmpty(),
            onBatch = { viewModel.cancelLocalTransfers(state.localTransfers.map { it.fileId }.toSet()) },
            onCancel = viewModel::cancelTransfer,
        )
        val acceptablePeerIds = acceptablePeerFileIds(state.peerTransfers)
        FileList(
            title = stringResource(R.string.peer_files),
            emptyText = stringResource(R.string.waiting_for_peer_file),
            listState = peerFilesState(state),
            batchLabel = stringResource(R.string.download_all),
            batchEnabled = acceptablePeerIds.isNotEmpty(),
            onBatch = onAcceptIncoming,
            onAccept = onAcceptSingleIncoming,
            onCancel = viewModel::cancelTransfer,
        )
        Text(stringResource(R.string.peer_visible), color = appMuted(), fontSize = 13.sp)
    }
}

@Composable
private fun FileList(
    title: String,
    emptyText: String,
    listState: TransferListState,
    batchLabel: String,
    batchEnabled: Boolean,
    onBatch: (List<String>) -> Unit,
    onAccept: ((String) -> Unit)? = null,
    onCancel: ((String) -> Unit)? = null,
) {
    var requestedDownloadIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var pendingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDeleteName by rememberSaveable { mutableStateOf("") }
    val currentOnCancel by rememberUpdatedState(onCancel)
    val requestedIds = syncRequestedDownloadIds(requestedDownloadIds.toSet(), listState.cards)
    LaunchedEffect(listState.cards) {
        requestedDownloadIds = syncRequestedDownloadIds(requestedDownloadIds.toSet(), listState.cards).toList()
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = appTextPrimary(), fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${listState.cards.size}", color = appMuted(), fontSize = 13.sp)
                WebButton(batchLabel, primary = false, enabled = batchEnabled, onClick = {
                    val acceptedIds = acceptablePeerFileIds(listState.cards).filterNot { it in requestedDownloadIds }
                    if (onAccept != null) {
                        if (acceptedIds.isNotEmpty()) {
                            requestedDownloadIds = requestedDownloadIds + acceptedIds
                            onBatch(acceptedIds)
                        }
                    } else {
                        onBatch(emptyList())
                    }
                })
            }
        }
        if (listState.cards.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(PanelShape)
                    .background(appSurfaceAlt())
                    .border(1.dp, appLine(), PanelShape)
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(emptyText, color = appTextPrimary(), fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.no_file_content_read), color = appMuted(), fontSize = 13.sp)
            }
        }
        listState.cards.forEachIndexed { index, card ->
            val requested = card.fileId in requestedIds
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(PanelShape)
                    .background(appSurfaceAlt())
                    .border(1.dp, appLine(), PanelShape)
                    .padding(start = 12.dp, top = 6.dp, end = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text("${index + 1}", color = appMuted(), fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp, end = 8.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            card.title,
                            color = appTextPrimary(),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (card.actionLabel == TransferCardAction.Accept && onAccept != null) {
                            CompactFileActionIcon(
                                actionLabel = stringResource(R.string.download_file_action),
                                enabled = !requested,
                                onClick = {
                                    if (card.fileId !in requestedDownloadIds) {
                                        requestedDownloadIds = requestedDownloadIds + card.fileId
                                        onAccept(card.fileId)
                                    }
                                },
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_file_download),
                                    contentDescription = null,
                                    tint = LocalPrimaryColor.current,
                                )
                            }
                        }
                        if (onCancel != null) {
                            CompactFileActionIcon(
                                actionLabel = stringResource(R.string.remove_file_action),
                                enabled = card.canCancel,
                                onClick = {
                                    pendingDeleteId = card.fileId
                                    pendingDeleteName = card.title
                                },
                            ) {
                                Text("×", color = Red, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    // Design frame 14: a failed transfer says why in place instead of resting at a percentage.
                    if (chatFileMetaIsWarning(card)) {
                        Text(
                            text = chatFileStatusText(card, card.progressPercent),
                            color = Red,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (shouldShowCompactTransferProgress(card, requested)) {
                        LinearProgressIndicator(
                            progress = { (card.progressPercent ?: 0) / 100f },
                            modifier = Modifier.fillMaxWidth().height(5.dp).progressSemantics((card.progressPercent ?: 0) / 100f),
                            color = LocalPrimaryColor.current,
                            trackColor = appSurfaceAlt(),
                        )
                    }
                }
            }
        }
    }
    pendingDeleteId?.let { fileId ->
        RemoveFileDialog(
            fileName = pendingDeleteName,
            onKeep = { pendingDeleteId = null },
            onRemove = {
                pendingDeleteId = null
                currentOnCancel?.invoke(fileId)
            },
        )
    }
}

@Composable
private fun CompactFileActionIcon(
    actionLabel: String,
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = actionLabel },
    ) {
        Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun RemoveFileDialog(fileName: String, onKeep: () -> Unit, onRemove: () -> Unit) {
    val keepFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { keepFocusRequester.requestFocus() }
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text(stringResource(R.string.remove_file_title)) },
        text = { Text(stringResource(R.string.remove_file_message, fileName)) },
        dismissButton = {
            TextButton(onClick = onKeep, modifier = Modifier.focusRequester(keepFocusRequester)) {
                Text(stringResource(R.string.remove_file_cancel))
            }
        },
        confirmButton = {
            TextButton(onClick = onRemove) { Text(stringResource(R.string.remove_file_confirm)) }
        },
    )
}

@Composable
private fun DiagnosticsPanel(state: AppUiState) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val diagnosticsLabel = stringResource(R.string.diagnostics)
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "diagnosticsChevron",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PanelShape)
            .background(appPanelSurface())
            .border(1.dp, appLine(), PanelShape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .semantics { contentDescription = diagnosticsLabel }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(diagnosticsLabel, color = appTextPrimary(), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (state.directConnectionFailed) stringResource(R.string.direct_connection_failed) else phaseLabelText(state),
                    color = if (state.directConnectionFailed) Red else appMuted(),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_expand_more),
                contentDescription = null,
                tint = LocalPrimaryColor.current,
                modifier = Modifier
                    .padding(start = 12.dp)
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
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HorizontalDivider(color = appLine())
                DiagRow(stringResource(R.string.signaling), if (state.channelReady) stringResource(R.string.connected) else phaseLabelText(state))
                DiagRow(stringResource(R.string.session), if (state.channelReady) stringResource(R.string.connected) else stringResource(R.string.not_connected))
                DiagRow(stringResource(R.string.data_channel), if (state.channelReady) stringResource(R.string.open) else stringResource(R.string.not_connected))
                DiagRow(stringResource(R.string.path), if (state.channelReady) connectionPathLabel(state.connectionPath) else stringResource(R.string.unknown))
                DiagRow(stringResource(R.string.failure), if (state.directConnectionFailed) stringResource(R.string.direct_connection_failed) else stringResource(R.string.none))
            }
        }
    }
}

@Composable
internal fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PanelShape)
            .background(appPanelSurface())
            .border(1.dp, appLine(), PanelShape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

@Composable
private fun RowScope.DeviceBox(label: String, value: String) {
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(PanelShape)
            .background(appSurfaceAlt())
            .border(1.dp, appLine(), PanelShape)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, color = appMuted(), fontSize = 13.sp)
        Text(value, color = appTextPrimary(), fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StepRow(label: String, status: StepStatus) {
    val primary = LocalPrimaryColor.current
    val color = when (status) {
        StepStatus.PASSED, StepStatus.DONE, StepStatus.SUCCESS -> primary
        StepStatus.CHECKING, StepStatus.PAIRING -> Amber
        StepStatus.FAILED -> Red
        StepStatus.WAIT -> Muted
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PanelShape)
            .border(1.dp, appLine(), PanelShape)
            .padding(10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = appTextPrimary())
        Text(stepStatusLabel(status), color = color, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = appMuted(), fontSize = 13.sp)
        Text(value, color = appTextPrimary(), fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TtlBadge(value: String) {
    val primary = LocalPrimaryColor.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
            .background(if (value.startsWith("00:")) Red else primary)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(R.string.secure_format, value), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun StatusPill(label: String) {
    val primary = LocalPrimaryColor.current
    Box(
        modifier = Modifier
            .clip(PillShape)
            .background(appSurfaceAlt())
            .border(1.dp, appLine(), PillShape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(label, color = primary, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun WebButton(
    label: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    onClick: () -> Unit,
) {
    val primaryColor = LocalPrimaryColor.current
    val colors = if (primary) {
        ButtonDefaults.buttonColors(containerColor = primaryColor, contentColor = Color.White, disabledContainerColor = appLine(), disabledContentColor = appMuted())
    } else {
        ButtonDefaults.outlinedButtonColors(containerColor = appPanelSurface(), contentColor = appTextPrimary(), disabledContentColor = appMuted())
    }
    if (primary) {
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = PanelShape,
            colors = colors,
            modifier = modifier.height(40.dp),
        ) {
            ButtonContent(label = label, primary = primary, loading = loading)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = PanelShape,
            colors = colors,
            border = ButtonDefaults.outlinedButtonBorder(enabled).copy(width = 1.dp),
            modifier = modifier.height(40.dp),
        ) {
            ButtonContent(label = label, primary = primary, loading = loading)
        }
    }
}

@Composable
private fun ButtonContent(label: String, primary: Boolean, loading: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = if (primary) Color.White else LocalPrimaryColor.current,
                strokeWidth = 2.dp,
            )
        }
        Text(label, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun FieldLabel(value: String) {
    Text(value, color = appMuted(), fontSize = 12.sp, fontWeight = FontWeight.Bold)
}

@Composable
internal fun ErrorBanner(value: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PanelShape)
            .background(if (LocalDarkTheme.current) DarkErrorSurface else Color(0xFFFFF5F5))
            .border(1.dp, if (LocalDarkTheme.current) DarkErrorLine else Color(0xFFF2B8B5), PanelShape)
            .padding(10.dp),
    ) {
        Text(value, color = if (LocalDarkTheme.current) Color(0xFFFFB4AB) else Red, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun textFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = appTextPrimary(),
    unfocusedTextColor = appTextPrimary(),
    focusedPlaceholderColor = appMuted(),
    unfocusedPlaceholderColor = appMuted(),
    focusedBorderColor = LocalPrimaryColor.current,
    unfocusedBorderColor = appLine(),
    focusedContainerColor = appPanelSurface(),
    unfocusedContainerColor = appPanelSurface(),
    cursorColor = LocalPrimaryColor.current,
)

@Composable
private fun appPageBackground(): Color = if (LocalDarkTheme.current) DarkPageBackground else PageBackground

@Composable
internal fun appPanelSurface(): Color = if (LocalDarkTheme.current) DarkSurface else Surface

@Composable
internal fun appSurfaceAlt(): Color = if (LocalDarkTheme.current) DarkSurfaceAlt else SurfaceAlt

@Composable
internal fun appLine(): Color = if (LocalDarkTheme.current) DarkLine else Line

@Composable
internal fun appTextPrimary(): Color = if (LocalDarkTheme.current) DarkTextPrimary else TextPrimary

@Composable
internal fun appMuted(): Color = if (LocalDarkTheme.current) DarkMuted else Muted

@Composable
private fun stepStatusLabel(status: StepStatus): String = when (status) {
    StepStatus.WAIT -> stringResource(R.string.wait)
    StepStatus.CHECKING -> stringResource(R.string.checking)
    StepStatus.PASSED -> stringResource(R.string.passed)
    StepStatus.PAIRING -> stringResource(R.string.pairing)
    StepStatus.DONE -> stringResource(R.string.done)
    StepStatus.FAILED -> stringResource(R.string.failed)
    StepStatus.SUCCESS -> stringResource(R.string.success)
}

@Composable
private fun connectionDirectionText(state: AppUiState): String = when {
    isPeerConnectionMode(state) && state.channelReady -> stringResource(R.string.connected_peer)
    isPeerConnectionMode(state) && state.signalingReady -> stringResource(R.string.connecting_peer)
    isPeerConnectionMode(state) -> stringResource(R.string.checking_peer_code)
    state.channelReady -> stringResource(R.string.peer_connected_this)
    else -> stringResource(R.string.waiting_direction)
}

@Composable
internal fun phaseLabelText(state: AppUiState): String = when {
    state.channelReady -> stringResource(R.string.connected)
    peerConnectionInProgress(state) -> stringResource(R.string.phase_connecting)
    state.joinedRoom != null -> stringResource(R.string.wait)
    else -> stringResource(R.string.phase_checking_network)
}

@Composable
internal fun connectionPathLabel(path: ConnectionPath): String = when (path) {
    ConnectionPath.DIRECT -> stringResource(R.string.direct)
    ConnectionPath.UNKNOWN -> stringResource(R.string.unknown)
}

@Composable
private fun validationLabel(validation: PeerCodeValidation): String = when (validation) {
    PeerCodeValidation.OK -> stringResource(R.string.ok)
    PeerCodeValidation.INVALID -> stringResource(R.string.invalid)
    PeerCodeValidation.SELF -> stringResource(R.string.self_code)
    PeerCodeValidation.REFRESHED -> stringResource(R.string.refreshed_code)
}

internal fun isPeerConnectionMode(state: AppUiState): Boolean =
    state.roomCode.isNotBlank() || state.joinedRoom?.role == "peer"

internal fun localizedAndroidContext(context: Context, localeTag: String): Context {
    val locale = Locale.forLanguageTag(localeTag.ifBlank { "en" })
    Locale.setDefault(locale)
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocales(LocaleList(locale))
    return context.createConfigurationContext(configuration)
}

/**
 * Compose keeps `LocalLayoutDirection` at the value of the hosting view, so an
 * in-app language switch has to provide it too — overriding only the context
 * would translate the strings but leave everything laid out left-to-right.
 */
internal fun localizedLayoutDirection(localeTag: String): LayoutDirection {
    val locale = Locale.forLanguageTag(localeTag.ifBlank { "en" })
    return if (TextUtils.getLayoutDirectionFromLocale(locale) == View.LAYOUT_DIRECTION_RTL) {
        LayoutDirection.Rtl
    } else {
        LayoutDirection.Ltr
    }
}

internal fun backToastBackgroundContrastAgainstPage(): Double =
    contrastRatio(BackToastBackgroundArgb, PageBackgroundArgb)

internal fun backToastOverlayAlignment(): Alignment =
    Alignment.Center

internal fun darkBackToastBackgroundContrastAgainstPage(): Double =
    contrastRatio(DarkBackToastBackgroundArgb, DarkPageBackgroundArgb)

internal fun backToastTextContrast(darkTheme: Boolean): Double =
    if (darkTheme) {
        contrastRatio(DarkBackToastBackgroundArgb, DarkBackToastTextArgb)
    } else {
        contrastRatio(BackToastBackgroundArgb, 0xFFFFFFFF.toInt())
    }

internal fun darkBackToastBackgroundColorArgb(): Int = DarkBackToastBackgroundArgb

internal fun darkBackToastBorderColorArgb(): Int = DarkBackToastBorderArgb

internal fun darkBackToastTextColorArgb(): Int = DarkBackToastTextArgb

private fun contrastRatio(firstArgb: Int, secondArgb: Int): Double {
    val first = relativeLuminance(firstArgb)
    val second = relativeLuminance(secondArgb)
    val lighter = maxOf(first, second)
    val darker = minOf(first, second)
    return (lighter + 0.05) / (darker + 0.05)
}

private fun relativeLuminance(argb: Int): Double {
    fun channel(shift: Int): Double {
        val raw = (argb shr shift) and 0xFF
        val normalized = raw / 255.0
        return if (normalized <= 0.03928) normalized / 12.92 else Math.pow((normalized + 0.055) / 1.055, 2.4)
    }
    val red = channel(16)
    val green = channel(8)
    val blue = channel(0)
    return 0.2126 * red + 0.7152 * green + 0.0722 * blue
}
