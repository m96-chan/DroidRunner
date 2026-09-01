package dev.devenus.droidrunner

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
import dev.devenus.droidrunner.device.DeviceCapabilities
import dev.devenus.droidrunner.monitor.SystemMonitor
import dev.devenus.droidrunner.runner.RunnerService
import dev.devenus.droidrunner.runner.RunnerState
import dev.devenus.droidrunner.runner.RunnerStatus
import dev.devenus.droidrunner.runtime.RuntimeInstaller
import dev.devenus.droidrunner.security.SecretStore
import dev.devenus.droidrunner.ui.DashboardScreen
import dev.devenus.droidrunner.ui.SetupScreen
import dev.devenus.droidrunner.ui.theme.BtopTheme
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
