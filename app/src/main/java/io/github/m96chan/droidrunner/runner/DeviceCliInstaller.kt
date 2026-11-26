package io.github.m96chan.droidrunner.runner

import android.content.Context
import io.github.m96chan.droidrunner.runtime.GuestFs
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
    private val GUEST_DIRECTORY = listOf("rootfs", "usr", "local", "bin")

    fun install(context: Context, runtimeDir: File) {
        runCatching {
            GuestFs.replaceFile(runtimeDir, GUEST_DIRECTORY, ASSET, "rwxr-xr-x") { staged ->
                context.assets.open(ASSET).use { input -> staged.outputStream().use(input::copyTo) }
            }
        }.onFailure {
            android.util.Log.e("DroidRunner", "cannot install device CLI", it)
        }
    }
}
