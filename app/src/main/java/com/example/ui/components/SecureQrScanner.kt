package com.example.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.ZoomSuggestionOptions
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** A scanner session gate: at most one result can be delivered. */
internal class SecureQrScanGate {
    private val consumed = AtomicBoolean(false)

    fun consume(value: String, onConsumed: (String) -> Unit): Boolean {
        if (value.isBlank() || !consumed.compareAndSet(false, true)) return false
        onConsumed(value)
        return true
    }
}

@Composable
fun SecureQrScanner(
    onScanned: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val sessionGate = remember { SecureQrScanGate() }
    val sessionActive = remember { AtomicBoolean(true) }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionAsked by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torchEnabled by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!sessionActive.get()) return@rememberLauncherForActivityResult
        hasCameraPermission = granted
        if (!granted) cameraError = "يلزم السماح بالكاميرا لمسح رمز QR."
    }

    fun dismiss() {
        if (sessionActive.compareAndSet(true, false)) onDismiss()
    }

    BackHandler(enabled = sessionActive.get(), onBack = ::dismiss)

    LaunchedEffect(Unit) {
        if (!hasCameraPermission && !permissionAsked && sessionActive.get()) {
            permissionAsked = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    DisposableEffect(Unit) {
        onDispose { sessionActive.set(false) }
    }

    Surface(modifier = Modifier.fillMaxSize().testTag("qr_scanner_root"), color = Color.Black) {
        SecureQrScannerContent(
            context = context,
            lifecycleOwner = lifecycleOwner,
            sessionActive = sessionActive,
            sessionGate = sessionGate,
            hasCameraPermission = hasCameraPermission,
            cameraError = cameraError,
            camera = camera,
            torchEnabled = torchEnabled,
            onCameraReady = { camera = it },
            onTorchChanged = { torchEnabled = it },
            onError = { cameraError = it },
            onRetryPermission = {
                cameraError = null
                permissionAsked = true
                permissionLauncher.launch(Manifest.permission.CAMERA)
            },
            onDismiss = ::dismiss,
            onScanned = onScanned,
        )
    }
}

@Composable
@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
private fun SecureQrScannerContent(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    sessionActive: AtomicBoolean,
    sessionGate: SecureQrScanGate,
    hasCameraPermission: Boolean,
    cameraError: String?,
    camera: Camera?,
    torchEnabled: Boolean,
    onCameraReady: (Camera) -> Unit,
    onTorchChanged: (Boolean) -> Unit,
    onError: (String) -> Unit,
    onRetryPermission: () -> Unit,
    onDismiss: () -> Unit,
    onScanned: (String) -> Unit,
) {
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    if (hasCameraPermission) {
        DisposableEffect(lifecycleOwner, previewView) {
            val executor = Executors.newSingleThreadExecutor()
            var provider: ProcessCameraProvider? = null
            var analysis: ImageAnalysis? = null
            var preview: Preview? = null
            var scanner: com.google.mlkit.vision.barcode.BarcodeScanner? = null
            val future = ProcessCameraProvider.getInstance(context)
            val listener = Runnable {
                if (!sessionActive.get()) return@Runnable
                try {
                    provider = future.get()
                    val cameraProvider = provider ?: return@Runnable
                    val selector = CameraSelector.DEFAULT_BACK_CAMERA
                    if (!cameraProvider.hasCamera(selector)) {
                        onError("لا توجد كاميرا خلفية متاحة على هذا الجهاز.")
                        return@Runnable
                    }
                    val localAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build()
                    val localPreview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                    val boundCamera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, localPreview, localAnalysis)
                    if (!sessionActive.get()) {
                        cameraProvider.unbind(localPreview, localAnalysis)
                        return@Runnable
                    }
                    preview = localPreview
                    analysis = localAnalysis
                    onCameraReady(boundCamera)
                    previewView.post {
                        if (sessionActive.get() && previewView.width > 0 && previewView.height > 0) {
                            val point = previewView.meteringPointFactory.createPoint(previewView.width / 2f, previewView.height / 2f)
                            val action = FocusMeteringAction.Builder(point).build()
                            if (boundCamera.cameraInfo.isFocusMeteringSupported(action)) {
                                boundCamera.cameraControl.startFocusAndMetering(action)
                            }
                        }
                    }
                    val maxZoom = (boundCamera.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f).coerceAtLeast(1f)
                    val zoomSuggestions = ZoomSuggestionOptions.Builder { ratio ->
                        if (sessionActive.get()) {
                            val safeRatio = ratio.coerceIn(1f, maxZoom)
                            ContextCompat.getMainExecutor(context).execute {
                                if (sessionActive.get()) boundCamera.cameraControl.setZoomRatio(safeRatio)
                            }
                            true
                        } else {
                            false
                        }
                    }.setMaxSupportedZoomRatio(maxZoom).build()
                    scanner = BarcodeScanning.getClient(
                        BarcodeScannerOptions.Builder()
                            .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE)
                            .setZoomSuggestionOptions(zoomSuggestions)
                            .build(),
                    )
                    localAnalysis.setAnalyzer(executor) { imageProxy ->
                        val mediaImage = imageProxy.image
                        if (mediaImage == null || !sessionActive.get()) {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        try {
                            val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                            scanner?.process(input)
                                ?.addOnSuccessListener(ContextCompat.getMainExecutor(context)) { codes ->
                                    if (!sessionActive.get()) return@addOnSuccessListener
                                    val value = codes.firstOrNull()?.rawValue ?: return@addOnSuccessListener
                                    sessionGate.consume(value) { onScanned(it); onDismiss() }
                                }
                                ?.addOnFailureListener { /* A bad frame is not a session error. */ }
                                ?.addOnCompleteListener { imageProxy.close() }
                                ?: imageProxy.close()
                        } catch (_: Exception) {
                            imageProxy.close()
                        }
                    }
                } catch (_: Exception) {
                    if (sessionActive.get()) onError("تعذر تشغيل الكاميرا. حاول مرة أخرى.")
                }
            }
            future.addListener(listener, ContextCompat.getMainExecutor(context))
            onDispose {
                sessionActive.set(false)
                analysis?.clearAnalyzer()
                provider?.let { cameraProvider ->
                    preview?.let { cameraProvider.unbind(it) }
                    analysis?.let { cameraProvider.unbind(it) }
                }
                scanner?.close()
                executor.shutdownNow()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (hasCameraPermission && cameraError == null) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    view.setOnTouchListener { v, event ->
                        if (event.action == MotionEvent.ACTION_UP) {
                            val currentCamera = camera
                            if (currentCamera != null) {
                                val point = view.meteringPointFactory.createPoint(event.x, event.y)
                                val action = FocusMeteringAction.Builder(point).build()
                                if (currentCamera.cameraInfo.isFocusMeteringSupported(action)) {
                                    currentCamera.cameraControl.startFocusAndMetering(action)
                                }
                            }
                            v.performClick()
                        }
                        true
                    }
                },
            )
        }
        Column(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f))
                .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("مسح رمز QR", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                Text("اعرض رمز QR من شاشة الكمبيوتر، ثم وافق على الاقتران أولاً.", color = Color.White, modifier = Modifier.padding(top = 8.dp))
            }
            if (cameraError != null || !hasCameraPermission) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(cameraError ?: "جارٍ طلب إذن الكاميرا…", color = Color.White)
                    if (!hasCameraPermission) {
                        Row(modifier = Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = onRetryPermission) { Text("إعادة المحاولة") }
                            Button(onClick = onDismiss) { Text("إغلاق") }
                        }
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(48.dp).testTag("qr_close_button"),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "إغلاق ماسح QR", tint = Color.White)
                }
                if (camera?.cameraInfo?.hasFlashUnit() == true) {
                    SecureQrTorchButton(
                        enabled = true,
                        checked = torchEnabled,
                        onCheckedChange = { next ->
                            val result = camera.cameraControl.enableTorch(next)
                            result.addListener({
                                if (sessionActive.get()) {
                                    try { result.get(); onTorchChanged(next) }
                                    catch (_: Exception) { onError("تعذر تشغيل المصباح.") }
                                }
                            }, ContextCompat.getMainExecutor(context))
                        },
                    )
                }
            }
        }
    }
}

/** Icon-only torch control; kept separate so its accessibility contract is testable. */
@Composable
internal fun SecureQrTorchButton(
    enabled: Boolean,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    IconToggleButton(
        checked = checked,
        enabled = enabled,
        onCheckedChange = onCheckedChange,
        modifier = Modifier.size(48.dp).testTag("qr_torch_button").semantics {
            role = Role.Switch
            stateDescription = if (checked) "المصباح مفعّل" else "المصباح معطّل"
        },
    ) {
        Icon(
            imageVector = if (checked) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff,
            contentDescription = if (checked) "إطفاء المصباح" else "تشغيل المصباح",
            tint = Color.White,
        )
    }
}
