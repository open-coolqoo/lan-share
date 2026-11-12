package com.lanshare

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.roundToInt

internal data class QrOverlayPoint(val xFraction: Float, val yFraction: Float)

private data class QrScanCandidate(val rawValue: String, val overlayPoint: QrOverlayPoint?)

private enum class QrScanHandling { Pause, Complete }

private const val QR_LOCK_VISIBLE_MS = 1_000L

private fun cameraPermissionGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

class QrScannerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterFullScreenScannerMode()
        val locale = intent.getStringExtra(EXTRA_LOCALE).orEmpty()
        val activity = this
        setContent {
            MaterialTheme {
                var cameraAllowed by remember {
                    mutableStateOf(
                        cameraPermissionGranted(activity),
                    )
                }
                var cameraDenied by remember { mutableStateOf(false) }
                val lifecycleOwner = LocalLifecycleOwner.current
                val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    cameraAllowed = granted
                    cameraDenied = !granted
                }
                DisposableEffect(lifecycleOwner, activity) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            val granted = cameraPermissionGranted(activity)
                            cameraAllowed = granted
                            if (granted) cameraDenied = false
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }
                val localizedContext = remember(this, locale) { localizedAndroidContext(this, locale) }
                CompositionLocalProvider(
                    LocalContext provides localizedContext,
                    LocalResources provides localizedContext.resources,
                    LocalConfiguration provides localizedContext.resources.configuration,
                    LocalLayoutDirection provides localizedLayoutDirection(locale),
                ) {
                    QrScannerScreen(
                        cameraAllowed = cameraAllowed,
                        cameraDenied = cameraDenied,
                        onRequestCameraPermission = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                        onOpenAppSettings = {
                            startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", packageName, null),
                                ),
                            )
                        },
                        onPairingCode = { pairingCode ->
                            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_RAW_VALUE, pairingCode))
                            finish()
                        },
                        onCancel = {
                            setResult(Activity.RESULT_CANCELED)
                            finish()
                        },
                    )
                }
            }
        }
    }

    companion object {
        private const val EXTRA_LOCALE = "com.lanshare.QR_SCANNER_LOCALE"
        private const val EXTRA_RAW_VALUE = "com.lanshare.QR_SCANNER_RAW_VALUE"

        fun createIntent(context: Context, locale: String): Intent =
            Intent(context, QrScannerActivity::class.java).putExtra(EXTRA_LOCALE, locale)

        fun resultRawValue(intent: Intent?): String? =
            intent?.getStringExtra(EXTRA_RAW_VALUE)
    }

    private fun enterFullScreenScannerMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

@Composable
private fun QrScannerScreen(
    cameraAllowed: Boolean,
    cameraDenied: Boolean,
    onRequestCameraPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onPairingCode: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    var cameraUnavailable by remember { mutableStateOf(false) }
    var lastInvalidQrToastAtMs by remember { mutableLongStateOf(0L) }
    var previewActive by remember { mutableStateOf(false) }
    var lockedPoint by remember { mutableStateOf<QrOverlayPoint?>(null) }
    var pendingPairingCode by remember { mutableStateOf<String?>(null) }
    var invalidQrLockToken by remember { mutableLongStateOf(0L) }

    LaunchedEffect(pendingPairingCode) {
        val pairingCode = pendingPairingCode ?: return@LaunchedEffect
        delay(QR_LOCK_VISIBLE_MS)
        onPairingCode(pairingCode)
    }

    LaunchedEffect(invalidQrLockToken) {
        if (invalidQrLockToken == 0L) return@LaunchedEffect
        delay(QR_LOCK_VISIBLE_MS)
        if (pendingPairingCode == null) lockedPoint = null
    }

    LaunchedEffect(cameraAllowed, cameraUnavailable) {
        if (!cameraAllowed || cameraUnavailable) previewActive = false
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF07120E)) {
        if (cameraAllowed && !cameraUnavailable) {
            Box(modifier = Modifier.fillMaxSize()) {
                QrCameraPreview(
                    onPreviewActiveChanged = { previewActive = it },
                    onQrCode = { candidate ->
                        val rawValue = candidate.rawValue
                        // Keep the whole payload: the local QR carries the
                        // host addresses that avoid an mDNS lookup.
                        val pairingCode = pairingCodeFromScanResult(rawValue)
                        if (pairingCode == null) {
                            lockedPoint = candidate.overlayPoint ?: QrOverlayPoint(0.5f, 0.5f)
                            invalidQrLockToken += 1
                            lastInvalidQrToastAtMs = showInvalidQrToast(context, lastInvalidQrToastAtMs)
                            QrScanHandling.Pause
                        } else {
                            lockedPoint = candidate.overlayPoint ?: QrOverlayPoint(0.5f, 0.5f)
                            pendingPairingCode = rawValue
                            QrScanHandling.Complete
                        }
                    },
                    onCameraUnavailable = {
                        previewActive = false
                        cameraUnavailable = true
                    },
                )
                QrScannerOverlay(
                    scanLineVisible = previewActive && lockedPoint == null,
                    lockedPoint = lockedPoint,
                    onCancel = onCancel,
                )
            }
        } else if (cameraDenied) {
            QrScannerPermissionDialog(
                onOpenAppSettings = onOpenAppSettings,
                onCancel = onCancel,
            )
        } else {
            QrScannerPermissionPanel(
                onRequestPermission = onRequestCameraPermission,
                onCancel = onCancel,
            )
        }
    }
}

@Composable
private fun QrCameraPreview(
    onPreviewActiveChanged: (Boolean) -> Unit,
    onQrCode: (QrScanCandidate) -> QrScanHandling,
    onCameraUnavailable: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val delivered = remember { AtomicBoolean(false) }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize(),
    )

    DisposableEffect(context, lifecycleOwner, previewView) {
        val pausedUntilMs = AtomicLong(0L)
        val analyzerExecutor = Executors.newSingleThreadExecutor()
        val scannerOptions = BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        val scanner = BarcodeScanning.getClient(scannerOptions)
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val streamStateObserver = Observer<PreviewView.StreamState> { streamState ->
            if (streamState == PreviewView.StreamState.STREAMING) {
                onPreviewActiveChanged(true)
            } else {
                onPreviewActiveChanged(false)
            }
        }
        previewView.previewStreamState.observe(lifecycleOwner, streamStateObserver)
        cameraProviderFuture.addListener(
            {
                runCatching {
                    val cameraProvider = cameraProviderFuture.get()
                    val cameraSelector = firstAvailableQrCameraSelector(cameraProvider)
                    if (cameraSelector == null) {
                        onCameraUnavailable()
                    } else {
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also { imageAnalysis ->
                                imageAnalysis.setAnalyzer(analyzerExecutor) { imageProxy ->
                                    if (SystemClock.elapsedRealtime() < pausedUntilMs.get()) {
                                        imageProxy.close()
                                        return@setAnalyzer
                                    }
                                    processQrImageProxy(
                                        scanner = scanner,
                                        imageProxy = imageProxy,
                                        previewWidth = previewView.width,
                                        previewHeight = previewView.height,
                                    ) { candidate ->
                                        if (!delivered.get()) {
                                            when (onQrCode(candidate)) {
                                                QrScanHandling.Pause -> {
                                                    pausedUntilMs.set(SystemClock.elapsedRealtime() + QR_LOCK_VISIBLE_MS)
                                                }
                                                QrScanHandling.Complete -> {
                                                    delivered.set(true)
                                                    onPreviewActiveChanged(false)
                                                    runCatching { cameraProvider.unbindAll() }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            analysis,
                        )
                    }
                }.onFailure { onCameraUnavailable() }
            },
            ContextCompat.getMainExecutor(context),
        )

        onDispose {
            previewView.previewStreamState.removeObserver(streamStateObserver)
            onPreviewActiveChanged(false)
            if (cameraProviderFuture.isDone) {
                runCatching { cameraProviderFuture.get().unbindAll() }
            }
            scanner.close()
            analyzerExecutor.shutdown()
        }
    }
}

private fun firstAvailableQrCameraSelector(cameraProvider: ProcessCameraProvider): CameraSelector? =
    listOf(CameraSelector.DEFAULT_BACK_CAMERA, CameraSelector.DEFAULT_FRONT_CAMERA)
        .firstOrNull { selector ->
            runCatching { cameraProvider.hasCamera(selector) }.getOrDefault(false)
        }

@Composable
private fun QrScannerOverlay(scanLineVisible: Boolean, lockedPoint: QrOverlayPoint?, onCancel: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        QrScannerBackButton(
            onBack = onCancel,
            modifier = Modifier.align(Alignment.TopStart),
        )
        ScanLineOverlay(visible = scanLineVisible)
        if (lockedPoint != null) {
            QrLockOverlay(lockedPoint = lockedPoint)
        }
    }
}

@Composable
private fun QrScannerBackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val backDescription = stringResource(R.string.back)
    val layoutDirection = LocalLayoutDirection.current
    Box(
        modifier = modifier
            .statusBarsPadding()
            .padding(start = 12.dp, top = 14.dp)
            .size(48.dp)
            .semantics { contentDescription = backDescription },
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onBack) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(
                    modifier = Modifier
                        .size(16.dp)
                        .scale(
                            scaleX = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f,
                            scaleY = 1f,
                        ),
                ) {
                    val chevron = Path().apply {
                        moveTo(size.width * 0.64f, size.height * 0.20f)
                        lineTo(size.width * 0.34f, size.height * 0.50f)
                        lineTo(size.width * 0.64f, size.height * 0.80f)
                    }
                    drawPath(
                        path = chevron,
                        color = Color(0xFF8A8A8A),
                        style = Stroke(
                            width = size.minDimension * 0.18f,
                            cap = StrokeCap.Square,
                            join = StrokeJoin.Miter,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun ScanLineOverlay(
    visible: Boolean,
    modifier: Modifier = Modifier,
    scanLineColor: Color = Color(0xFF00C853),
    // 将高度提取为参数，默认 null 表示占满全屏，也可以传入特定高度如 260.dp
    customTravelHeight: Dp? = null
) {
    if (!visible) return

    val lineHeight = 3.dp

    val transition = rememberInfiniteTransition(label = "qr_scan_line")
    val scanProgress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "qr_scan_line_progress",
    )

    // 🌟 性能优化：在外部 remember Path，避免 draw 阶段每帧分配内存引发 GC
    val taperedPath = remember { Path() }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        // 🌟 需求适配：如果没有传入指定高度，则默认使用父容器最大高度（实现全屏上下扫动）
        val travelHeight = customTravelHeight ?: maxHeight / 2

        Box(
            modifier = Modifier
                // 🌟 性能优化：使用 Lambda 版本的 offset，将状态读取延迟，避免每帧 Recomposition
                .offset {
                    val travelPx = travelHeight.toPx()
                    val linePx = lineHeight.toPx()
                    val scanOffsetPx = (travelPx - linePx) * scanProgress
                    val yOffset = scanOffsetPx - (travelPx / 2f) + (linePx / 2f)

                    IntOffset(x = 0, y = yOffset.roundToInt())
                }
                .fillMaxWidth(0.86f)
                .height(lineHeight),
        ) {
            Canvas(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(28.dp),
            ) {
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to scanLineColor.copy(alpha = 0.22f),
                        1f to scanLineColor.copy(alpha = 0f),
                    ),
                )
            }
            // 🌟 核心高光线条：变细 + 水平渐变透明
            Canvas(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(3.dp),
            ) {
                val centerY = size.height / 2f
                val halfWidth = size.width / 2f
                val maxHalfThickness = size.height / 2f // 中间最粗（占据整个 lineHeight 的高度）

                // 💡 定义水平方向的渐变刷：边缘 0%，中心 100%
                val horizontalFadeBrush = Brush.horizontalGradient(
                    0.0f to scanLineColor.copy(alpha = 0f),
                    0.06f to scanLineColor.copy(alpha = 0f),
                    0.10f to scanLineColor.copy(alpha = 0.22f),
                    0.14f to scanLineColor.copy(alpha = 0.62f),
                    0.18f to scanLineColor.copy(alpha = 0.88f),
                    0.22f to scanLineColor.copy(alpha = 0.96f),
                    0.5f to scanLineColor.copy(alpha = 1f),
                    0.78f to scanLineColor.copy(alpha = 0.96f),
                    0.82f to scanLineColor.copy(alpha = 0.88f),
                    0.86f to scanLineColor.copy(alpha = 0.62f),
                    0.90f to scanLineColor.copy(alpha = 0.22f),
                    0.94f to scanLineColor.copy(alpha = 0f),
                    1.0f to scanLineColor.copy(alpha = 0f),
                )

                // 💡 绘制完美向两端收束变细的柳叶形路径
                taperedPath.reset()
                taperedPath.apply {
                    // 从左侧边缘 Y 轴正中心开始（此时厚度为 0）
                    moveTo(0f, centerY)

                    // 1. 左半边上沿：先保持极细，再逐步放大到中心厚度
                    cubicTo(
                        size.width * 0.06f, centerY,
                        size.width * 0.10f, centerY - maxHalfThickness * 0.20f,
                        size.width * 0.14f, centerY - maxHalfThickness * 0.58f,
                    )
                    cubicTo(
                        size.width * 0.18f, centerY - maxHalfThickness * 0.92f,
                        size.width * 0.26f, centerY - maxHalfThickness,
                        halfWidth, centerY - maxHalfThickness,
                    )

                    // 2. 右半边上沿：从中心厚度逐步收成不可见尖点
                    cubicTo(
                        size.width * 0.74f, centerY - maxHalfThickness,
                        size.width * 0.82f, centerY - maxHalfThickness * 0.92f,
                        size.width * 0.86f, centerY - maxHalfThickness * 0.58f,
                    )
                    cubicTo(
                        size.width * 0.90f, centerY - maxHalfThickness * 0.20f,
                        size.width * 0.94f, centerY,
                        size.width, centerY,
                    )

                    // 3. 右半边下沿：从不可见尖点回到中心厚度
                    cubicTo(
                        size.width * 0.94f, centerY,
                        size.width * 0.90f, centerY + maxHalfThickness * 0.20f,
                        size.width * 0.86f, centerY + maxHalfThickness * 0.58f,
                    )
                    cubicTo(
                        size.width * 0.82f, centerY + maxHalfThickness * 0.92f,
                        size.width * 0.74f, centerY + maxHalfThickness,
                        halfWidth, centerY + maxHalfThickness,
                    )

                    // 4. 左半边下沿：逐步收回不可见尖点
                    cubicTo(
                        size.width * 0.26f, centerY + maxHalfThickness,
                        size.width * 0.18f, centerY + maxHalfThickness * 0.92f,
                        size.width * 0.14f, centerY + maxHalfThickness * 0.58f,
                    )
                    cubicTo(
                        size.width * 0.10f, centerY + maxHalfThickness * 0.20f,
                        size.width * 0.06f, centerY,
                        0f, centerY,
                    )
                    close()
                }

                // 绘制收束的背景路径，应用水平渐变
                drawPath(
                    path = taperedPath,
                    brush = horizontalFadeBrush
                )

                // 绘制最中心的纯色极细亮线，同样应用渐变，让中心线也融入两侧
                drawLine(
                    brush = horizontalFadeBrush,
                    start = Offset(x = 0f, y = centerY),
                    end = Offset(x = size.width, y = centerY),
                    strokeWidth = 2f,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun QrLockOverlay(lockedPoint: QrOverlayPoint) {
    val transition = rememberInfiniteTransition(label = "qr_lock_pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 360, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "qr_lock_scale",
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .offset(
                    x = maxWidth * lockedPoint.xFraction - 14.dp,
                    y = maxHeight * lockedPoint.yFraction - 14.dp,
                )
                .size(28.dp)
                .scale(pulse)
                .background(Color(0xFF0F6B55), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(Color.White, CircleShape),
            )
        }
    }
}

@Composable
private fun QrScannerPermissionPanel(onRequestPermission: () -> Unit, onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.qr_scanner_unavailable),
            color = Color.White,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onRequestPermission,
            modifier = Modifier.padding(top = 18.dp),
        ) {
            Text(stringResource(R.string.scan_to_connect))
        }
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text(stringResource(R.string.cancel_transfer))
        }
    }
}

@Composable
private fun QrScannerPermissionDialog(onOpenAppSettings: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.qr_scanner_permission_title)) },
        text = { Text(stringResource(R.string.qr_scanner_permission_message)) },
        confirmButton = {
            TextButton(onClick = onOpenAppSettings) {
                Text(stringResource(R.string.open_settings))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel_transfer))
            }
        },
    )
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
private fun processQrImageProxy(
    scanner: BarcodeScanner,
    imageProxy: ImageProxy,
    previewWidth: Int,
    previewHeight: Int,
    onQrCode: (QrScanCandidate) -> Unit,
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }

    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
    scanner.process(image)
        .addOnSuccessListener { barcodes ->
            val barcode = barcodes
                .firstOrNull { it.format == Barcode.FORMAT_QR_CODE && !it.rawValue.isNullOrBlank() }
            val rawValue = barcode?.rawValue
            if (!rawValue.isNullOrBlank()) {
                onQrCode(
                    QrScanCandidate(
                        rawValue = rawValue,
                        overlayPoint = barcode.boundingBox?.let { bounds ->
                            qrOverlayPointFromImageCenter(
                                imageWidth = imageProxy.width,
                                imageHeight = imageProxy.height,
                                rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                                previewWidth = previewWidth,
                                previewHeight = previewHeight,
                                centerX = bounds.exactCenterX(),
                                centerY = bounds.exactCenterY(),
                            )
                        },
                    ),
                )
            }
        }
        .addOnCompleteListener {
            imageProxy.close()
        }
}

internal fun qrOverlayPointFromImageCenter(
    imageWidth: Int,
    imageHeight: Int,
    rotationDegrees: Int,
    previewWidth: Int,
    previewHeight: Int,
    centerX: Float,
    centerY: Float,
): QrOverlayPoint? {
    if (imageWidth <= 0 || imageHeight <= 0 || previewWidth <= 0 || previewHeight <= 0) return null
    val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
    val rotated = normalizedRotation == 90 || normalizedRotation == 270
    val analysisWidth = if (rotated) imageHeight.toFloat() else imageWidth.toFloat()
    val analysisHeight = if (rotated) imageWidth.toFloat() else imageHeight.toFloat()
    if (analysisWidth <= 0f || analysisHeight <= 0f) return null

    val scale = max(previewWidth / analysisWidth, previewHeight / analysisHeight)
    val displayedWidth = analysisWidth * scale
    val displayedHeight = analysisHeight * scale
    val offsetX = (previewWidth - displayedWidth) / 2f
    val offsetY = (previewHeight - displayedHeight) / 2f
    val overlayX = (centerX * scale) + offsetX
    val overlayY = (centerY * scale) + offsetY

    return QrOverlayPoint(
        xFraction = (overlayX / previewWidth.toFloat()).coerceIn(0f, 1f),
        yFraction = (overlayY / previewHeight.toFloat()).coerceIn(0f, 1f),
    )
}

private fun showInvalidQrToast(context: Context, lastShownAtMs: Long): Long {
    val now = SystemClock.elapsedRealtime()
    if (now - lastShownAtMs >= 1_500L) {
        Toast.makeText(context, context.getString(R.string.qr_scanner_invalid_code), Toast.LENGTH_SHORT).show()
        return now
    }
    return lastShownAtMs
}
