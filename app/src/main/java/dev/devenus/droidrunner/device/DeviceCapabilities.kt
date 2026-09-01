package dev.devenus.droidrunner.device

import android.os.Build
import java.io.File
import java.util.Locale

data class DeviceCapabilities(
    val manufacturer: String,
    val model: String,
    val soc: String,
    val hasNpuHint: Boolean,
) {
    fun labels(): Set<String> = buildSet {
        add("android")
        add("arm64")
        add("android-api-${Build.VERSION.SDK_INT}")
        add("soc-${slug(soc)}")
        add(if (hasNpuHint) "android-npu" else "android-no-npu")
        when {
            soc.contains("qualcomm", true) || soc.contains("snapdragon", true) -> add("npu-qnn")
            soc.contains("tensor", true) -> add("npu-tflite")
            soc.contains("mediatek", true) || soc.contains("dimensity", true) -> add("npu-neuron")
            soc.contains("exynos", true) -> add("npu-enn")
        }
    }

    companion object {
        fun detect(): DeviceCapabilities {
            val soc = listOfNotNull(
                if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER.takeIf { it.isNotBlank() } else null,
                if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.takeIf { it.isNotBlank() } else null,
                Build.HARDWARE,
                readCpuInfo()
            ).joinToString(" ")
            val npuWords = listOf("qualcomm", "snapdragon", "tensor", "mediatek", "dimensity", "exynos", "kirin")
            return DeviceCapabilities(
                Build.MANUFACTURER,
                Build.MODEL,
                soc,
                npuWords.any { soc.contains(it, ignoreCase = true) }
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
