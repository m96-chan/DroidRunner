/*
 * Part of DroidRunner. GPL-2.0-only, with the additional permission below.
 *
 * Additional permission under GNU GPL version 2, as a special exception:
 *
 * The copyright holders of this file give you permission to combine it with
 * Qualcomm's QNN runtime and LiteRT delegate libraries, and to convey the
 * resulting work. This permission covers this file only; it does not extend to
 * any other part of DroidRunner, which remains GPL-2.0-only.
 */
package io.github.m96chan.droidrunner.qnn

/**
 * How much of a model actually reached the Hexagon (issue #82, stage 5).
 *
 * The delegate does not refuse work it cannot take — it takes what it can and
 * leaves the rest to the CPU, silently. A benchmark that reports "1.2ms on the
 * NPU" while every operator ran on the CPU is worse than having no Qualcomm
 * support at all, because it looks like a result. So the number is only
 * published alongside the split, and a run that reached the accelerator not at
 * all is refused rather than reported.
 *
 * The split comes from the delegate's own statement, which it prints while
 * applying itself:
 *
 *     INFO: [Qnn Delegate] QNN delegate: 47 nodes delegated out of 47 nodes
 *     with 1 partitions.
 *
 * Parsed rather than inferred from timings, because timings are exactly the
 * thing in question.
 */
internal data class QnnDelegation(
    val delegated: Int,
    val total: Int,
    val partitions: Int,
) {

    /** Nothing reached the accelerator; whatever was measured is a CPU number. */
    val none: Boolean get() = delegated <= 0

    /** Some operators stayed behind, so the timing covers both processors. */
    val partial: Boolean get() = delegated in 1 until total

    fun describe(): String = when {
        none -> "no operator ran on the Hexagon"
        partial -> "$delegated of $total operators on the Hexagon, " +
            "${total - delegated} on the CPU, $partitions partitions"
        else -> "all $total operators on the Hexagon, $partitions partitions"
    }

    companion object {
        /**
         * The last delegation the delegate reported in [log], or null when it
         * never said.
         *
         * The last, not the first: warmup and the timed run each apply the
         * delegate, and an older line may belong to a previous model.
         */
        fun parse(log: String): QnnDelegation? =
            REPORTS.firstNotNullOfOrNull { pattern ->
                pattern.findAll(log).lastOrNull()?.let { match ->
                    QnnDelegation(
                        delegated = match.groupValues[1].toInt(),
                        total = match.groupValues[2].toInt(),
                        partitions = match.groupValues[3].toInt(),
                    )
                }
            }

        /**
         * Two ways the same fact gets stated, and both are looked for.
         *
         * TFLite announces the partitioning itself and names the delegate that
         * took the nodes — which is the stronger statement, since a line about
         * some other delegate cannot be mistaken for this one. The delegate's
         * own wording is kept because it is what appears when TFLite is not
         * the one talking.
         */
        private val REPORTS = listOf(
            Regex(
                """Replacing (\d+) out of (\d+) node\(s\) with delegate """ +
                    """\(TfLiteQnnDelegate\) node, yielding (\d+) partitions""",
            ),
            Regex("""(\d+) nodes delegated out of (\d+) nodes with (\d+) partitions"""),
        )
    }
}

/**
 * Whether a run may be published as an accelerator measurement.
 *
 * Silence counts as a no. If the delegate never said what it took, the honest
 * answer is that we do not know which processor produced the number — and an
 * unattributable number is the thing this is here to prevent.
 */
internal fun refuseUnattributable(
    delegation: QnnDelegation?,
    profilingBytes: Int,
): String? = when {
    delegation == null && profilingBytes <= 0 ->
        "the delegate did not report what it executed, so this timing cannot be " +
            "attributed to the Hexagon"
    delegation != null && delegation.none ->
        "the delegate took no operators: ${delegation.describe()}"
    else -> null
}


/**
 * Which backend a name asks for (issue #82, stage 5).
 *
 * The delegate is created from the library's own default options with this
 * written into the first field, which is how Qualcomm's Java wrapper does it.
 * The string-keyed `tflite_plugin_create_delegate` was tried first and does not
 * work: it builds a delegate TFLite then refuses to apply, and passing
 * `log_level` through it loses the backend entirely. Both were established on
 * hardware, one option at a time.
 */
internal object QnnOptions {

    /** `TfLiteQnnDelegateBackendType`, as the published header numbers it. */
    private val BACKENDS = mapOf("gpu" to 1, "htp" to 2, "dsp" to 3, "ir" to 4)

    /** The code for [backend], or null when it is not one QNN has. */
    fun backendCode(backend: String): Int? = BACKENDS[backend.lowercase()]
}
