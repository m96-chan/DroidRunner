package io.github.m96chan.droidrunner.device

/**
 * Maps the SoC strings Android actually reports onto a vendor.
 *
 * Devices report internal identifiers, not marketing names: a Snapdragon 8
 * Gen 3 says `QTI` / `SM8650` / `qcom` and never says "Qualcomm". Matching
 * marketing words alone labelled every such phone `android-no-npu` (#23).
 *
 * These labels remain hints. The probe-verified NNAPI labels are what a
 * workflow should target; see [io.github.m96chan.droidrunner.npu.NpuLabels].
 */
enum class SocVendor(val npuLabel: String?) {
    /**
     * Deliberately without a hint label.
     *
     * A Snapdragon's Hexagon is not reachable through NNAPI at all — Qualcomm
     * ships no NNAPI driver, and these phones enumerate only
     * `nnapi-reference`, the CPU. So `npu-qnn` from the SoC name promised
     * something no job could use, and a workflow selecting it landed on a
     * device that ran everything on its CPU and said nothing (#80). The label
     * is now emitted only where it has been earned: after a model has been
     * shown to execute on the Hexagon (#82).
     */
    QUALCOMM(null),
    MEDIATEK("npu-neuron"),
    GOOGLE_TENSOR("npu-tflite"),
    SAMSUNG_EXYNOS("npu-enn"),
    HISILICON("npu-unknown"),
    ;

    companion object {
        private val CODENAMES =
            Regex("""\b(kona|lahaina|taro|kalama|pineapple|sun|cape|waipio)\b""")

        /** Vendor for the combined SoC string, or null when unrecognised. */
        fun detect(soc: String): SocVendor? {
            val text = soc.lowercase()
            fun has(vararg needles: String) = needles.any { it in text }
            return when {
                // Qualcomm ships as QTI/qcom with SM/SDM/MSM model numbers, and
                // older builds use codenames such as kona or lahaina.
                has("qualcomm", "snapdragon", "qti", "qcom", "kryo", "adreno") ||
                    Regex("""\b(sm|sdm|msm|apq)\d{3,4}\b""").containsMatchIn(text) ||
                    // Codenames need word boundaries: "sun" (SM8750) otherwise
                    // matches "Samsung" and hands Exynos devices to Qualcomm.
                    CODENAMES.containsMatchIn(text) -> QUALCOMM

                // MediaTek reports MediaTek plus an MT model number.
                has("mediatek", "dimensity", "helio") ||
                    Regex("""\bmt\d{4}\b""").containsMatchIn(text) -> MEDIATEK

                has("google", "tensor", "gs101", "gs201", "zuma") -> GOOGLE_TENSOR
                has("exynos", "samsung", "s5e") -> SAMSUNG_EXYNOS
                has("kirin", "hisilicon") -> HISILICON
                else -> null
            }
        }
    }
}
