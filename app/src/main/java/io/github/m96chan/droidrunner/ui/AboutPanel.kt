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
import io.github.m96chan.droidrunner.runner.RunnerLog
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

        // Then where each of those licences' source is, in the order the
        // READMEs' licence table uses: what the APK ships, then what the
        // runtime bundle does. Both are offers a recipient can act on, not
        // descriptions of the project (issue #226).
        val offer = sourceOffer(
            versionName = BuildConfig.VERSION_NAME,
            prootCommit = BuildConfig.PROOT_COMMIT,
            gitCommit = BuildConfig.GIT_COMMIT,
        )
        Spacer(Modifier.padding(top = 10.dp))
        Note(offer.text)
        Spacer(Modifier.padding(top = 4.dp))
        Link(offer.linkLabel) { open(offer.url) }

        Spacer(Modifier.padding(top = 10.dp))
        Note(ROOTFS_SOURCE)

        Spacer(Modifier.padding(top = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Link("project source") { open(PROJECT_URL) }
            // Upstream at the pinned commit, which is still worth having: it
            // is where the history and the issue tracker are. It is not the
            // source offer — that is the archive above, on our own release.
            Link("proot source") { open("$PROOT_URL/tree/${BuildConfig.PROOT_COMMIT}") }
            Link("report an issue") { open("$PROJECT_URL/issues/new") }
        }

        Spacer(Modifier.padding(top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Link(if (copied) "copied" else "copy device info") {
                clipboard.setText(AnnotatedString(deviceReport(capabilities, runtime)))
                copied = true
            }
            CopyDiagnosticsLink(capabilities, runtime)
        }
    }
}

/**
 * The corresponding-source offer for the GPL binaries in the APK, and where to
 * get it (issue #226).
 *
 * GPL-2.0 §3 is discharged by offering the source from the same place as the
 * binary, and every release publishes `droidrunner-<tag>-source.tar.gz` beside
 * the APK: proot at the pinned commit, the talloc tarball, the patches and the
 * script that builds them. Naming a commit and a patch directory instead — as
 * this screen did until #226 — hands the reader a recipe and asks them to
 * reconstruct what we already shipped for them.
 */
internal data class SourceOffer(val text: String, val linkLabel: String, val url: String)

/**
 * A release build's versionName is its tag with the "v" removed; see `semver`
 * in app/build.gradle.kts. Anything else was never released.
 */
private val RELEASE_VERSION = Regex("""\d+\.\d+\.\d+""")

/**
 * What the screen says about the source, and what it links to.
 *
 * Split out of the composable because the part that can be wrong is a string:
 * the release asset URL is reconstructed from the version name, and a build
 * that was never released has no such asset. Linking there anyway would answer
 * the one question this panel exists to answer with a 404, so a development
 * build is told plainly that it is one and pointed at the commit it came from,
 * which is the corresponding source for it. `GIT_COMMIT` is empty where there
 * was no git to ask — the source archive itself builds that way — and then
 * only the repository can be offered.
 */
internal fun sourceOffer(versionName: String, prootCommit: String, gitCommit: String): SourceOffer {
    val proot = prootCommit.take(12)
    val tag = versionName.takeIf { RELEASE_VERSION.matches(it) }?.let { "v$it" }
    if (tag != null) {
        val archive = "droidrunner-$tag-source.tar.gz"
        return SourceOffer(
            text = "Source for the GPL binaries in this APK: $archive, published " +
                "beside the APK on the $tag release. It holds proot at commit " +
                "$proot, the talloc tarball, the patches and the build script.",
            linkLabel = "source archive",
            url = "$PROJECT_URL/releases/download/$tag/$archive",
        )
    }
    val builtFrom = if (gitCommit.isEmpty()) "" else " from commit $gitCommit"
    return SourceOffer(
        text = "This is a $versionName build$builtFrom, not a release, so no " +
            "source archive was published for it; proot here is commit $proot. " +
            "Every release carries droidrunner-<tag>-source.tar.gz beside the " +
            "APK, with proot, talloc, the patches and the build script.",
        linkLabel = if (gitCommit.isEmpty()) "project source" else "source at $gitCommit",
        url = if (gitCommit.isEmpty()) PROJECT_URL else "$PROJECT_URL/tree/$gitCommit",
    )
}

/**
 * Where the rootfs's source is, which is inside the bundle rather than here.
 *
 * The runtime bundle is hundreds of Ubuntu packages, and redistributing them
 * makes us their distributor too (issue #116). The offer travels with the
 * tarball: `runtime/build-bundle.sh` writes `PACKAGES.txt`, every package and
 * its exact version, and `SOURCE-OFFER.txt`, how to turn that list into
 * source. The READMEs cite both; "Ubuntu packages, own licences" on its own
 * told the person holding the app nothing they could act on.
 */
internal const val ROOTFS_SOURCE =
    "The runtime bundle carries PACKAGES.txt and SOURCE-OFFER.txt at its root: " +
        "every Ubuntu package in the rootfs, its exact version, and how to get " +
        "that package's source."

/**
 * The device report, plus what the device actually did (issue #152).
 *
 * A release build refuses `run-as`, so `runner.log` — the only account of an
 * admission hold, a restart loop, or a session that never came back — cannot be
 * taken off the phone at all. This is the way out, and it goes to the clipboard
 * because the destination is the text box in an issue.
 */
internal fun diagnosticsReport(
    capabilities: DeviceCapabilities,
    runtime: RuntimeInstaller,
    tail: List<String>,
): String = buildString {
    appendLine(deviceReport(capabilities, runtime))
    appendLine()
    if (tail.isEmpty()) {
        append("runner log: nothing recorded yet")
    } else {
        appendLine("--- runner log, last ${tail.size} lines ---")
        append(tail.joinToString("\n"))
    }
}

/** Everything worth pasting into a bug report. */
internal fun deviceReport(capabilities: DeviceCapabilities, runtime: RuntimeInstaller): String =
    buildString {
        appendLine("DroidRunner ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("device: ${capabilities.manufacturer} ${capabilities.model}")
        appendLine("android: API ${android.os.Build.VERSION.SDK_INT}")
        appendLine("soc: ${capabilities.soc}")
        appendLine("labels: ${capabilities.labels().sorted().joinToString(" ")}")
        appendLine("runtime: ${runtime.installedVersion ?: "not installed"}")
        append("proot: ${BuildConfig.PROOT_COMMIT.take(12)}")
    }

/** A dim paragraph under the licence list: where a component's source is. */
@Composable
private fun Note(text: String) {
    Text(text, color = BtopColors.Dim, style = MaterialTheme.typography.labelSmall)
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

/**
 * A tappable label.
 *
 * Keep labels to plain text. A tick — "copied ✓" — is drawn from a fallback
 * font whose line box is taller than the body font's, and swapping it in grew
 * the disk panel on the dashboard by 9px, broke its alignment with the mem
 * panel beside it, and pushed everything below down. Measured on device, and
 * not fixed by pinning `lineHeight`: Compose sizes the line from the font's own
 * metrics whatever the style asks for.
 */
@Composable
internal fun Link(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = BtopColors.Cyan,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.clickable { onClick() }.padding(vertical = 2.dp),
    )
}
