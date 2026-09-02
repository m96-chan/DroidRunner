package io.github.m96chan.droidrunner.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.m96chan.droidrunner.BuildConfig
import io.github.m96chan.droidrunner.device.DeviceCapabilities
import io.github.m96chan.droidrunner.runtime.RuntimeInstaller
import io.github.m96chan.droidrunner.ui.theme.BtopColors

private const val PROJECT_URL = "https://github.com/m96-chan/DroidRunner"
private const val PROOT_URL = "https://github.com/termux/proot"

/**
 * Version, licences, and the pointers a GPL distribution owes its users.
 *
 * The APK ships proot binaries built from a pinned commit, so the exact
 * revision is compiled in (see `PROOT_COMMIT` in build.gradle.kts) rather than
 * written by hand here, where it could drift from what was actually built.
 */
@Composable
fun AboutPanel(capabilities: DeviceCapabilities, runtime: RuntimeInstaller) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    Panel("about", titleColor = BtopColors.Cyan) {
        Field("app", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        Field("runtime", runtime.installedVersion ?: "not installed")
        Field("device", "${capabilities.manufacturer} ${capabilities.model}")
        Field("android", "API ${android.os.Build.VERSION.SDK_INT}")

        Spacer(Modifier.padding(top = 10.dp))
        Text("licences", color = BtopColors.Yellow, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.padding(top = 4.dp))
        Field("app", "GPL-2.0-only")
        Field("proot", "GPL-2.0")
        Field("talloc", "LGPL-3.0 (${BuildConfig.TALLOC_VERSION})")
        Field("runner", "MIT (in the runtime bundle)")
        Field("rootfs", "Ubuntu packages, own licences")

        Spacer(Modifier.padding(top = 10.dp))
        Text(
            "Source for the proot binaries in this APK: commit " +
                BuildConfig.PROOT_COMMIT.take(12) + ", built by runtime/build-proot.sh " +
                "with the patches in runtime/patches/.",
            color = BtopColors.Dim,
            style = MaterialTheme.typography.labelSmall,
        )

        Spacer(Modifier.padding(top = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Link("project source") { open(PROJECT_URL) }
            Link("proot source") { open("$PROOT_URL/tree/${BuildConfig.PROOT_COMMIT}") }
            Link("report an issue") { open("$PROJECT_URL/issues/new") }
        }

        Spacer(Modifier.padding(top = 8.dp))
        Link(if (copied) "copied ✓" else "copy device info") {
            clipboard.setText(AnnotatedString(deviceReport(capabilities, runtime)))
            copied = true
        }
    }
}

/** Everything worth pasting into a bug report. */
private fun deviceReport(capabilities: DeviceCapabilities, runtime: RuntimeInstaller): String =
    buildString {
        appendLine("DroidRunner ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("device: ${capabilities.manufacturer} ${capabilities.model}")
        appendLine("android: API ${android.os.Build.VERSION.SDK_INT}")
        appendLine("soc: ${capabilities.soc}")
        appendLine("labels: ${capabilities.labels().sorted().joinToString(" ")}")
        appendLine("runtime: ${runtime.installedVersion ?: "not installed"}")
        append("proot: ${BuildConfig.PROOT_COMMIT.take(12)}")
    }

@Composable
private fun Field(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            label,
            color = BtopColors.Dim,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(76.dp),
        )
        Text(value, color = BtopColors.Text, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun Link(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = BtopColors.Cyan,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.clickable { onClick() }.padding(vertical = 2.dp),
    )
}
