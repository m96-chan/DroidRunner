package dev.devenus.droidrunner

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.remember
import dev.devenus.droidrunner.device.DeviceCapabilities
import dev.devenus.droidrunner.monitor.SystemMonitor
import dev.devenus.droidrunner.runner.RunnerService
import dev.devenus.droidrunner.runtime.RuntimeInstaller
import dev.devenus.droidrunner.security.SecretStore
import dev.devenus.droidrunner.ui.DashboardScreen
import dev.devenus.droidrunner.ui.theme.BtopTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BtopTheme {
                val capabilities = remember { DeviceCapabilities.detect() }
                val runtime = remember { RuntimeInstaller(this) }
                val secretStore = remember { SecretStore(this) }
                val monitor = remember { SystemMonitor(this) }
                val notificationPermission =
                    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

                DashboardScreen(
                    capabilities = capabilities,
                    runtime = runtime,
                    secretStore = secretStore,
                    monitor = monitor,
                    onStartRunner = {
                        if (Build.VERSION.SDK_INT >= 33) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        startForegroundService(Intent(this, RunnerService::class.java))
                    },
                    onStopRunner = {
                        startService(Intent(this, RunnerService::class.java).setAction(RunnerService.ACTION_STOP))
                    },
                )
            }
        }
    }
}
