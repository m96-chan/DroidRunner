package io.github.m96chan.droidrunner

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.m96chan.droidrunner.device.DeviceCapabilities
import io.github.m96chan.droidrunner.monitor.SystemMonitor
import io.github.m96chan.droidrunner.runner.RunnerService
import io.github.m96chan.droidrunner.runner.RunnerState
import io.github.m96chan.droidrunner.runner.RunnerStatus
import io.github.m96chan.droidrunner.runtime.RuntimeInstaller
import io.github.m96chan.droidrunner.security.SecretStore
import io.github.m96chan.droidrunner.ui.DashboardScreen
import io.github.m96chan.droidrunner.ui.PipScreen
import io.github.m96chan.droidrunner.ui.SetupScreen
import io.github.m96chan.droidrunner.ui.shouldOfferPip
import io.github.m96chan.droidrunner.ui.theme.BtopTheme
import java.io.File

class MainActivity : ComponentActivity() {

    /** Whether the activity is currently a picture-in-picture window (#33). */
    private val inPipMode = mutableStateOf(false)

    /** Set while leaving the app should leave a window behind. */
    private var pipOffered = false

    private val pipSupported: Boolean by lazy {
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RunnerStatus.attach(this)
        setContent {
            BtopTheme {
                val capabilities = remember { DeviceCapabilities.detect() }
                val runtime = remember { RuntimeInstaller(this) }
                val secretStore = remember { SecretStore(this) }
                val monitor = remember { SystemMonitor(this) }
                val configuredAtLaunch = remember { File(runtime.runtimeDir, ".configured").isFile }
                // First run drops the user straight into setup.
                var showSetup by remember { mutableStateOf(!configuredAtLaunch) }
                val notificationPermission =
                    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
                val runner by RunnerStatus.snapshot.collectAsState()
                val inPip by inPipMode

                fun startRunner() {
                    if (Build.VERSION.SDK_INT >= 33) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    startForegroundService(Intent(this, RunnerService::class.java))
                }

                // A configured device is a runner: bring it up on launch.
                LaunchedEffect(Unit) {
                    if (configuredAtLaunch && runtime.installed &&
                        RunnerStatus.snapshot.value.state == RunnerState.STOPPED
                    ) {
                        startRunner()
                    }
                }

                // Keep the system's idea of whether to shrink this activity in
                // step with what is actually on screen.
                LaunchedEffect(showSetup, runner.state) {
                    offerPip(shouldOfferPip(pipSupported, showSetup, runner.state))
                }

                BackHandler(enabled = showSetup) { showSetup = false }

                if (inPip) {
                    PipScreen(monitor = monitor)
                } else if (showSetup) {
                    SetupScreen(
                        capabilities = capabilities,
                        runtime = runtime,
                        secretStore = secretStore,
                        onClose = { showSetup = false },
                        // Repairing a runtime from setup leaves a device that
                        // is a runner again; the launch-time autostart above
                        // has already run, so bring it up here (issue #46).
                        onStartRunner = { startRunner() },
                    )
                } else {
                    DashboardScreen(
                        capabilities = capabilities,
                        runtime = runtime,
                        monitor = monitor,
                        onOpenSetup = { showSetup = true },
                        onStartRunner = { startRunner() },
                        onStopRunner = {
                            startService(Intent(this, RunnerService::class.java).setAction(RunnerService.ACTION_STOP))
                        },
                    )
                }
            }
        }
    }

    /**
     * On API 31+ the system enters picture-in-picture on its own when the user
     * leaves; below that the activity has to ask, and [onUserLeaveHint] is the
     * only moment it may.
     */
    private fun offerPip(offered: Boolean) {
        pipOffered = offered
        if (!pipSupported || Build.VERSION.SDK_INT < 31) return
        runCatching { setPictureInPictureParams(pipParams(autoEnter = offered)) }
    }

    private fun pipParams(autoEnter: Boolean): PictureInPictureParams =
        PictureInPictureParams.Builder()
            // The system rejects anything outside 1:2.39..2.39:1, and the
            // compact layout is written for a landscape strip.
            .setAspectRatio(Rational(16, 9))
            .apply { if (Build.VERSION.SDK_INT >= 31) setAutoEnterEnabled(autoEnter) }
            .build()

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= 31 || !pipOffered) return
        runCatching { enterPictureInPictureMode(pipParams(autoEnter = false)) }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPipMode.value = isInPictureInPictureMode
    }
}
