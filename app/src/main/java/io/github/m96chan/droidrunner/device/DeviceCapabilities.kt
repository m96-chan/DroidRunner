package io.github.m96chan.droidrunner.device

import android.os.Build
import java.io.File
import java.util.Locale

data class DeviceCapabilities(
    val manufacturer: String,
    val model: String,
    val soc: String,
    val vendor: SocVendor?,
) {
    /** A recognised vendor is the only NPU hint the SoC string can give. */
    val hasNpuHint: Boolean get() = vendor != null

    fun labels(): Set<String> = buildSet {
        add("android")
        add("arm64")
        add("android-api-${Build.VERSION.SDK_INT}")
        add("soc-${slug(soc)}")
        // Only the positive claim. "No NPU" was asserted from a SoC string
        // that cannot establish it — a Snapdragon whose NPU is unreachable
        // through NNAPI still has one (#82) — and the probe's
        // `nnapi-accelerator` is the label that says something measured.
        if (hasNpuHint) add("android-npu")
        vendor?.npuLabel?.takeIf { it != "npu-unknown" }?.let(::add)
    }

    companion object {
        fun detect(): DeviceCapabilities {
            val soc = listOfNotNull(
                if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER.takeIf { it.isNotBlank() } else null,
                if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.takeIf { it.isNotBlank() } else null,
                Build.HARDWARE,
                readCpuInfo()
            ).joinToString(" ")
            return DeviceCapabilities(
                Build.MANUFACTURER,
                Build.MODEL,
                soc,
                SocVendor.detect(soc),
            )
        }

        private fun readCpuInfo() = runCatching {
            File("/proc/cpuinfo").useLines { lines ->
                lines.firstOrNull { it.startsWith("Hardware", true) }
                    ?.substringAfter(':')?.trim()
            }
        }.getOrNull()

        private fun slug(value: String): String = value.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "unknown" }
    }
}
