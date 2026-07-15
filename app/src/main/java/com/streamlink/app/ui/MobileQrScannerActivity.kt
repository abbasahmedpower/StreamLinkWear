package com.streamlink.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.streamlink.shared.ConnectionPayload
import com.streamlink.shared.DirectSocketServer
import com.streamlink.shared.trustedDeviceStore
import com.streamlink.app.stream.PhoneStreamingForegroundService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject

/** Timeout for connecting to the watch after a successful QR scan (5 seconds). */
private const val CONNECTION_TIMEOUT_MS = 5_000L

@AndroidEntryPoint
class MobileQrScannerActivity : BaseActivity() {

    @Inject
    lateinit var socketServer: DirectSocketServer

    private lateinit var cameraExecutor: ExecutorService

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCameraAndScanner()
        } else {
            Toast.makeText(this, "Camera permission is required to scan QR.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()

        // ✅ Trust on First Use — إذا الجهاز موثوق، اتصل تلقائياً بدون QR
        if (trustedDeviceStore.hasTrustedDevice) {
            Log.i("QrScanner", "Trusted device found — attempting auto-connect")
            val code = trustedDeviceStore.trustedPairingCode ?: ""
            if (code.isNotEmpty()) {
                autoConnectWithTrustedDevice(code)
                return // لا داعي لفتح الكاميرا
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraAndScanner()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /**
     * اتصال تلقائي بالجهاز الموثوق دون الحاجة لمسح QR.
     * يُعيد محاولة الاتصال مرة واحدة إذا فشل (قد تكون الساعة خارج النطاق).
     */
    private fun autoConnectWithTrustedDevice(pairingCode: String) {
        socketServer.pairingCode = pairingCode
        setContent {
            // عرض شاشة بسيطة «جاري الاتصال…» بدل كاميرا QR
            AutoConnectScreen(
                deviceName = trustedDeviceStore.trustedDeviceName ?: "الجهاز الموثوق"
            )
        }
        kotlinx.coroutines.MainScope().launch {
            val connected = withTimeoutOrNull(CONNECTION_TIMEOUT_MS) {
                var elapsed = 0L
                while (elapsed < CONNECTION_TIMEOUT_MS) {
                    if (socketServer.isClientConnected) return@withTimeoutOrNull true
                    delay(250)
                    elapsed += 250
                }
                null
            }
            if (connected == true) {
                trustedDeviceStore.updateLastSeen()
                PhoneStreamingForegroundService.start(this@MobileQrScannerActivity)
                Toast.makeText(this@MobileQrScannerActivity, "✅ تم الاتصال!", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                // الجهاز غير موجود على الشبكة — افتح الكاميرا كـ fallback
                Log.w("QrScanner", "Auto-connect failed — falling back to QR scan")
                Toast.makeText(
                    this@MobileQrScannerActivity,
                    "تعذّر الاتصال التلقائي — امسح الـ QR مجدداً",
                    Toast.LENGTH_LONG
                ).show()
                socketServer.pairingCode = ""
                if (ContextCompat.checkSelfPermission(this@MobileQrScannerActivity, Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED) {
                    startCameraAndScanner()
                } else {
                    requestPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }
        }
    }

    private fun startCameraAndScanner() {
        setContent {
            QrScannerScreen(
                onQrScanned = { payloadStr ->
                    val payload = ConnectionPayload.fromJson(payloadStr)
                    if (payload != null) {
                        handleSuccessfulScan(payload)
                    }
                }
            )
        }
    }

    /**
     * Applies the scanned pairing code and validates the connection within [CONNECTION_TIMEOUT_MS].
     *
     * If the watch is not reachable (different network, offline, stale QR), shows a clear,
     * actionable error message instead of leaving the user confused.
     */
    private fun handleSuccessfulScan(payload: ConnectionPayload) {
        socketServer.pairingCode = payload.pairingCode

        // Show "Connecting…" feedback immediately then verify within timeout
        kotlinx.coroutines.MainScope().launch {
            val connected = withTimeoutOrNull(CONNECTION_TIMEOUT_MS) {
                // Poll the server's connection state for up to 5s
                var elapsed = 0L
                while (elapsed < CONNECTION_TIMEOUT_MS) {
                    if (socketServer.isClientConnected) return@withTimeoutOrNull true
                    delay(250)
                    elapsed += 250
                }
                null // timeout
            }

            if (connected == true) {
                // ✅ حفظ الجهاز كموثوق — المرة القادمة بدون QR
                trustedDeviceStore.trust(
                    deviceId = payload.pairingCode, // نستخدم pairingCode كـ unique identifier مؤقت
                    pairingCode = payload.pairingCode,
                    deviceName = "الهاتف"
                )
                PhoneStreamingForegroundService.start(this@MobileQrScannerActivity)
                Toast.makeText(
                    this@MobileQrScannerActivity,
                    "✅ تم الاتصال بالساعة بنجاح!",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            } else {
                // Reset the stale pairing code so it doesn't block future attempts
                socketServer.pairingCode = ""
                Log.w("QrScanner", "Connection timeout after ${CONNECTION_TIMEOUT_MS}ms")
                Toast.makeText(
                    this@MobileQrScannerActivity,
                    "❌ تعذّر الاتصال. تأكد أن الهاتف والساعة على نفس شبكة Wi-Fi، ثم امسح الـ QR مجدداً.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Composable UI
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun QrScannerScreen(onQrScanned: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasScanned by remember { mutableStateOf(false) }
    var isConnecting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
        // Camera preview — full screen
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val imageAnalyzer = ImageAnalysis.Builder()
                        .setTargetResolution(Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also {
                            it.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                                if (hasScanned) {
                                    imageProxy.close()
                                    return@setAnalyzer
                                }
                                processImageProxy(imageProxy) { qrValue ->
                                    hasScanned = true
                                    isConnecting = true
                                    onQrScanned(qrValue)

                                    // Reset after timeout so user can retry
                                    scope.launch {
                                        delay(CONNECTION_TIMEOUT_MS + 1_000L)
                                        isConnecting = false
                                        hasScanned = false
                                    }
                                }
                            }
                        }

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Connecting overlay
        AnimatedVisibility(
            visible = isConnecting,
            modifier = Modifier.align(Alignment.Center)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .background(Color(0xCC000000), RoundedCornerShape(16.dp))
                    .padding(horizontal = 32.dp, vertical = 24.dp)
            ) {
                CircularProgressIndicator(color = Color(0xFF10B981))
                Spacer(Modifier.height(12.dp))
                Text("جاري الاتصال بالساعة…", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }

        // Bottom instruction label
        if (!isConnecting) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xBB000000))
                    .padding(vertical = 20.dp, horizontal = 16.dp)
            ) {
                Text(
                    text = "امسح الـ QR الخاص بالساعة",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "تأكد أن الهاتف والساعة على نفس شبكة Wi-Fi",
                    color = Color(0xFFAAAAAA),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}


/**
 * شاشة الاتصال التلقائي — تظهر بدل كاميرا QR عندما يوجد جهاز موثوق محفوظ.
 * تجربة ذهبية — اضغط وابدأ، بدون إعداد.
 */
@Composable
fun AutoConnectScreen(deviceName: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            CircularProgressIndicator(
                color = Color(0xFF10B981),
                strokeWidth = 3.dp
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = "جاري الاتصال بالساعة…",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "الجهاز: $deviceName",
                color = Color(0xFF6B7280),
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
private fun processImageProxy(
    imageProxy: ImageProxy,
    onQrFound: (String) -> Unit
) {
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        val scanner = BarcodeScanning.getClient()
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    if (barcode.valueType == Barcode.TYPE_TEXT) {
                        val value = barcode.rawValue
                        if (value != null && value.contains("pairingCode")) {
                            onQrFound(value)
                            break
                        }
                    }
                }
            }
            .addOnFailureListener { /* silent — next frame will retry */ }
            .addOnCompleteListener { imageProxy.close() }
    } else {
        imageProxy.close()
    }
}
