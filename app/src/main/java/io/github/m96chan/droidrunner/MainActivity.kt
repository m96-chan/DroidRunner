package io.github.m96chan.droidrunner

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
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
import io.github.m96chan.droidrunner.ui.SetupScreen
import io.github.m96chan.droidrunner.ui.theme.BtopTheme
import java.io.File

class MainActivity : ComponentActivity() {
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

                BackHandler(enabled = showSetup) { showSetup = false }

                if (showSetup) {
                    SetupScreen(
                        capabilities = capabilities,
                        runtime = runtime,
                        secretStore = secretStore,
                        onClose = { showSetup = false },
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
}
