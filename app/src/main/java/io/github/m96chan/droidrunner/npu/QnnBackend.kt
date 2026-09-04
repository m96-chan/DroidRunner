package io.github.m96chan.droidrunner.npu

/**
 * Which device names mean "Qualcomm's own runtime" (issue #82, stage 5).
 *
 * These are not NNAPI accelerator names and must not fall through to NNAPI: on
 * these phones that path reaches only `nnapi-reference`, the CPU. A job asking
 * for `qnn-htp` and quietly getting a CPU number back is the failure this whole
 * issue is about.
 */
internal object QnnBackend {

    /** The backend [device] names, or null when it is not a QNN request. */
    fun of(device: String?): String? {
        val name = device?.lowercase() ?: return null
        if (!name.startsWith(PREFIX)) return null
        return name.removePrefix(PREFIX).takeIf { it in SUPPORTED }
            ?: throw IllegalArgumentException(
                "unknown QNN backend '$name'; expected ${SUPPORTED.joinToString { "$PREFIX$it" }}",
            )
    }

    private const val PREFIX = "qnn-"

    /**
     * `dsp` is deliberately absent: it names the older Hexagon DSP backend,
     * reached through a library this app does not fetch.
     */
    private val SUPPORTED = setOf("htp", "gpu")
}
