package io.github.m96chan.droidrunner.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import io.github.m96chan.droidrunner.device.DeviceCapabilities
import io.github.m96chan.droidrunner.runner.RunnerLog
import io.github.m96chan.droidrunner.runtime.RuntimeInstaller

/**
 * One doorway to the device's own account of itself, offered twice (#152).
 *
 * The About panel is where somebody goes to file an issue, and the link sits
 * beside "report an issue" for that reason. It is not where anybody goes when
 * a runner has stopped doing its job — that is the dashboard, which is already
 * open and already showing that something is wrong. Two moments, one action,
 * so it is one composable rather than two that drift.
 */
@Composable
internal fun CopyDiagnosticsLink(
    capabilities: DeviceCapabilities,
    runtime: RuntimeInstaller,
    label: String = "copy diagnostics",
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    Link(if (copied) "copied" else label) {
        clipboard.setText(
            AnnotatedString(
                diagnosticsReport(
                    capabilities,
                    runtime,
                    RunnerLog.readTail(context.applicationContext.filesDir),
                ),
            ),
        )
        copied = true
    }
}
