package com.streamlink.app.ui

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Rational
import android.widget.Toast
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.streamlink.app.core.PipModeState
import com.streamlink.app.core.telemetry.GPUBudgetMonitor
import com.streamlink.app.core.telemetry.StartupProfiler
import com.streamlink.app.ui.theme.StreamLinkTheme
import com.streamlink.app.ui.theme.ThemeMode
import com.streamlink.app.ui.viewmodel.TelemetryViewModel
import com.streamlink.app.ui.viewmodel.TelemetryViewModelFactory
import com.streamlink.shared.util.SystemSettingsStore
import com.streamlink.shared.util.safeSystemService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : BaseActivity() {

    private val mainViewModel: MainViewModel by viewModels()
    
    @Inject lateinit var orchestrator: com.streamlink.app.core.StreamingOrchestrator

    private val telemetryViewModel: TelemetryViewModel by viewModels {
        TelemetryViewModelFactory(
            orchestrator = orchestrator,
            wearSender = com.streamlink.app.core.WearTelemetrySender(applicationContext)
        )
    }

    private val OVERLAY_PERMISSION_REQ_CODE = 1001

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            mainViewModel.processIntent(MainIntent.StreamResultReceived(result.resultCode, result.data!!))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!com.streamlink.app.BuildConfig.DEBUG && (com.streamlink.shared.SecurityUtils.isRooted() || com.streamlink.shared.SecurityUtils.isEmulator())) {
            android.app.AlertDialog.Builder(this)
                .setTitle("بيئة غير آمنة")
                .setMessage("التطبيق ده مصمم للعمل على أجهزة أصلية غير معدّلة لحماية بيانات الشاشة اللي بتتشارك.")
                .setCancelable(false)
                .setPositiveButton("فهمت") { _, _ -> finish() }
                .show()
            return
        }

        val settingsStore = SystemSettingsStore.get(applicationContext)

        // Observe Effects
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                mainViewModel.effect.collect { effect ->
                    when (effect) {
                        is MainEffect.LaunchScreenCapture -> requestScreenCapture()
                        is MainEffect.LaunchQrScanner -> {
                            startActivity(Intent(this@MainActivity, MobileQrScannerActivity::class.java))
                        }
                        is MainEffect.ShowToast -> {
                            Toast.makeText(
                                this@MainActivity,
                                effect.message,
                                if (effect.isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                            ).show()
                        }
                        is MainEffect.RequestOverlayPermission -> {
                            checkAndRequestOverlayPermission()
                        }
                    }
                }
            }
        }

        // Keep PiP synced
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                mainViewModel.state.collect { state ->
                    runCatching { setPictureInPictureParams(buildPipParams(state.isStreaming)) }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            window.addOnFrameMetricsAvailableListener({ _, frameMetrics, _ ->
                val renderTimeMs = frameMetrics.getMetric(android.view.FrameMetrics.TOTAL_DURATION) / 1_000_000f
                GPUBudgetMonitor.reportFrameRendered(renderTimeMs)
            }, android.os.Handler(android.os.Looper.getMainLooper()))
        }

        setContent {
            LaunchedEffect(Unit) { StartupProfiler.onFirstFrameRendered() }

            val themeModeString by settingsStore.themeMode.collectAsStateWithLifecycle()
            val themeMode = remember(themeModeString) {
                try { ThemeMode.valueOf(themeModeString) } catch (e: Exception) { ThemeMode.SYSTEM }
            }
            val mainState by mainViewModel.state.collectAsStateWithLifecycle()

            LaunchedEffect(themeMode) {
                val nightMode = when (themeMode) {
                    ThemeMode.LIGHT -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                    ThemeMode.DARK -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                    ThemeMode.SYSTEM -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(nightMode)
            }

            StreamLinkTheme(themeMode = themeMode) {
                MainScreenLayout(
                    settingsStore = settingsStore,
                    themeMode = themeMode,
                    onThemeModeChange = { settingsStore.setThemeMode(it.name) },
                    viewModel = telemetryViewModel,
                    mainState = mainState,
                    onIntent = { mainViewModel.processIntent(it) }
                )
            }
        }
    }

    private fun checkAndRequestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "يرجى تفعيل صلاحية الظهور في الأعلى", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivityForResult(intent, OVERLAY_PERMISSION_REQ_CODE)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQ_CODE) {
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "تم تفعيل صلاحية التعتيم بنجاح! 🛡️", Toast.LENGTH_SHORT).show()
            } else {
                mainViewModel.processIntent(MainIntent.SetPrivacyBlackout(false))
                Toast.makeText(this, "تم رفض الصلاحية، لن يعمل وضع التعتيم التام.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun requestScreenCapture() {
        val mpm: MediaProjectionManager? = safeSystemService(Context.MEDIA_PROJECTION_SERVICE)
        if (mpm == null) {
            Toast.makeText(this, "Screen capture service unavailable.", Toast.LENGTH_LONG).show()
            return
        }
        captureLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun buildPipParams(isStreaming: Boolean): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(9, 16))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(isStreaming)
        }
        return builder.build()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val isStreaming = mainViewModel.state.value.isStreaming
        if (isStreaming && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            runCatching { enterPictureInPictureMode(buildPipParams(isStreaming)) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PipModeState.update(isInPictureInPictureMode)
    }

    override fun onDestroy() {
        super.onDestroy()
        mainViewModel.processIntent(MainIntent.StopStream)
    }
}
