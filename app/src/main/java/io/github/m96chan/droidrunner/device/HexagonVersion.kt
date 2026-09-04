package io.github.m96chan.droidrunner.device

/**
 * Which Hexagon Tensor Processor a Snapdragon carries (issue #82, stage 1).
 *
 * Qualcomm's NPU runtime is versioned per HTP generation: a device needs
 * `libQnnHtpV75.so` or `libQnnHtpV73.so`, not both, and loading the wrong one
 * fails somewhere far away from the mistake. Getting this right is the first
 * thing the QNN work depends on, so it is decided here from the SoC string
 * alone and tested.
 *
 * Deliberately conservative: an unrecognised Snapdragon returns null rather
 * than a guess. Fetching several megabytes of the wrong runtime and failing
 * during inference is worse than saying "this device is not supported yet",
 * which is at least a sentence someone can act on.
 */
object HexagonVersion {

    /**
     * SoC model to HTP version. Only entries confirmed against Qualcomm's own
     * runtime packaging belong here — the AAR ships V68 through V81, and
     * Google's `fetch_qualcomm_library.sh` names the device groups.
     */
    private val KNOWN = mapOf(
        "sm8650" to 75, // Snapdragon 8 Gen 3 — nubia NX769J in the fleet
        "sm8550" to 73, // Snapdragon 8 Gen 2 — nubia NX729J in the fleet
        "sm8450" to 69, // Snapdragon 8 Gen 1
        "sm8350" to 68, // Snapdragon 888
        "sm8750" to 79, // Snapdragon 8 Elite
    )

    /**
     * The HTP version for this SoC string, or null when it is not a Snapdragon
     * or not one whose generation is known here.
     */
    fun of(soc: String): Int? {
        if (SocVendor.detect(soc) != SocVendor.QUALCOMM) return null
        val text = soc.lowercase()
        return KNOWN.entries.firstOrNull { (model, _) -> model in text }?.value
    }

    /**
     * The two libraries a device of this version needs. They come as a pair
     * because the work is split across two processors: the stub runs on the
     * CPU and marshals calls, the skel runs on the Hexagon DSP itself.
     *
     * There is no single `libQnnHtpV75.so` — an earlier guess at the name that
     * the published runtime does not contain.
     */
    fun libraries(version: Int): List<String> =
        listOf("libQnnHtpV${version}Skel.so", "libQnnHtpV${version}Stub.so")

    /** What to say when a Snapdragon is not in the table. */
    fun unsupportedReason(soc: String): String? =
        if (SocVendor.detect(soc) == SocVendor.QUALCOMM && of(soc) == null) {
            "Snapdragon with an unrecognised HTP generation; QNN support needs a mapping for this SoC"
        } else {
            null
        }
}
