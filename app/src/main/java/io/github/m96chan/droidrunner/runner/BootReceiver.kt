package io.github.m96chan.droidrunner.runner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.m96chan.droidrunner.runtime.RuntimeInstaller
import java.io.File

/**
 * Brings a registered runner back up after a reboot, so a dedicated CI phone
 * recovers from power loss without anyone opening the app. Opt-out via the
 * "start on boot" toggle on the setup screen.
 *
 * Only as unattended as the device allows: Android withholds
 * `BOOT_COMPLETED` while the user is credential-locked and delivers it at the
 * first unlock, so on a phone with a secure lock screen this runs when somebody
 * picks the phone up, not when the phone boots (issue #41). Making the receiver
 * `directBootAware` would not help — the runtime bundle and the credentials
 * live in credential-encrypted storage and are unreadable until then.
 * [BootGapPolicy] measures the wait afterwards.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val enabled = context.getSharedPreferences("setup", Context.MODE_PRIVATE)
            .getBoolean("boot_autostart", true)
        val runtime = RuntimeInstaller(context)
        if (enabled && runtime.installed && File(runtime.runtimeDir, ".configured").isFile) {
            context.startForegroundService(Intent(context, RunnerService::class.java))
        }
    }
}
