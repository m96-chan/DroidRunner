package io.github.m96chan.droidrunner.npu

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
            REPORT.findAll(log).lastOrNull()?.let { match ->
                QnnDelegation(
                    delegated = match.groupValues[1].toInt(),
                    total = match.groupValues[2].toInt(),
                    partitions = match.groupValues[3].toInt(),
                )
            }

        private val REPORT =
            Regex("""(\d+) nodes delegated out of (\d+) nodes with (\d+) partitions""")
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
