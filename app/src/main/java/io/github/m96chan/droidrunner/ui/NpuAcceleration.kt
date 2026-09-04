package io.github.m96chan.droidrunner.ui

import io.github.m96chan.droidrunner.device.HexagonVersion
import io.github.m96chan.droidrunner.npu.QnnArtifacts

/**
 * What the setup screen offers a Snapdragon about its NPU (issue #82, stage 3).
 *
 * Kept apart from the screen so the sequence — nothing to offer, terms to
 * read, bytes to fetch, done — is decided in one place and tested. The order
 * matters: acceptance gates the download, and a device whose Hexagon
 * generation is unknown must be told so rather than shown a button that would
 * fail.
 */
internal sealed interface NpuAcceleration {

    /** Not a Snapdragon, or not one QNN reaches: the panel says nothing. */
    data object Irrelevant : NpuAcceleration

    /** A Snapdragon whose generation has no mapping, or none Qualcomm ships. */
    data class Unsupported(val reason: String) : NpuAcceleration

    /** The terms have to be read before anything is fetched. */
    data class NeedsAcceptance(
        val htpVersion: Int,
        val downloadBytes: Long,
        val installBytes: Long,
    ) : NpuAcceleration

    /** Accepted, not installed. */
    data class Installable(
        val htpVersion: Int,
        val downloadBytes: Long,
        val installBytes: Long,
    ) : NpuAcceleration

    /** Installed and current. Carries the generation so it can be re-checked. */
    data class Installed(val stamp: String, val htpVersion: Int) : NpuAcceleration
}

/**
 * The state of the NPU panel for a device reporting [soc].
 *
 * [installed] is the stamp [QnnArtifacts.stamp] wrote, so a runtime installed
 * for another release or another generation reads as not installed — which is
 * what it is, for this device, today.
 */
internal fun npuAcceleration(
    soc: String,
    consentGranted: Boolean,
    installed: String?,
): NpuAcceleration {
    val version = HexagonVersion.of(soc)
        ?: return HexagonVersion.unsupportedReason(soc)
            ?.let { NpuAcceleration.Unsupported(it) }
            ?: NpuAcceleration.Irrelevant

    if (QnnArtifacts.entriesFor(version) == null) {
        return NpuAcceleration.Unsupported(
            "Qualcomm publishes no runtime for Hexagon v$version in ${QnnArtifacts.VERSION}",
        )
    }
    if (installed == QnnArtifacts.stamp(version)) {
        return NpuAcceleration.Installed(installed, version)
    }
    return if (consentGranted) {
        NpuAcceleration.Installable(
            htpVersion = version,
            downloadBytes = QnnArtifacts.downloadBytes(version),
            installBytes = QnnArtifacts.installBytes(version),
        )
    } else {
        NpuAcceleration.NeedsAcceptance(
            htpVersion = version,
            downloadBytes = QnnArtifacts.downloadBytes(version),
            installBytes = QnnArtifacts.installBytes(version),
        )
    }
}

/**
 * Both numbers, because either alone misleads: the transfer is what a metered
 * connection pays and the unpacked size is what the phone gives up, and here
 * the second is nearly three times the first.
 */
internal fun downloadSummary(downloadBytes: Long, installBytes: Long): String =
    "${megabytes(downloadBytes)} to download, ${megabytes(installBytes)} on disk"

private fun megabytes(bytes: Long): String = "%.0f MB".format(bytes / 1024.0 / 1024.0)
