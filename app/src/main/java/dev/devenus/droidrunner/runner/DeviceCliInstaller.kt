package dev.devenus.droidrunner.runner

import android.content.Context
import java.io.File

/**
 * Copies the `droidrunner-device` CLI from the APK into the guest on every
 * runner start (issue #9).
 *
 * The CLI and the Device Agent API it calls are two halves of the same
 * contract, so shipping it with the app keeps them in step — baking it into
 * the runtime bundle meant a CLI fix needed a ~200MB republish and a runtime
 * reinstall on every device.
 */
object DeviceCliInstaller {
    private const val ASSET = "droidrunner-device"
    private const val GUEST_PATH = "rootfs/usr/local/bin/droidrunner-device"

    fun install(context: Context, runtimeDir: File) {
        val target = File(runtimeDir, GUEST_PATH)
        runCatching {
            target.parentFile?.mkdirs()
            context.assets.open(ASSET).use { input ->
                target.outputStream().use(input::copyTo)
            }
            target.setExecutable(true, false)
        }.onFailure {
            android.util.Log.e("DroidRunner", "cannot install device CLI", it)
        }
    }
}
