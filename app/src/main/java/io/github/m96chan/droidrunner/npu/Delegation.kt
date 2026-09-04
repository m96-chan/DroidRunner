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
internal data class Delegation(
    val delegated: Int,
    val total: Int,
    val partitions: Int,
    /** What TFLite called the delegate that took the nodes, when it said. */
    val delegate: String? = null,
) {

    /** Nothing reached the accelerator; whatever was measured is a CPU number. */
    val none: Boolean get() = delegated <= 0

    /** Some operators stayed behind, so the timing covers both processors. */
    val partial: Boolean get() = delegated in 1 until total

    /**
     * The word for who did the work, which is what #93 asked for: a caller
     * reading `avgUs` alone cannot tell "ran on the NPU" from "fell back to
     * the CPU", and those produce the same JSON.
     */
    val executed: String get() = when {
        none -> "cpu-fallback"
        partial -> "partial"
        else -> "accelerator"
    }

    /**
     * [where] names what took the nodes. It has a default rather than being
     * hard-coded because the first version said "on the Hexagon" whatever ran
     * the graph, and cheerfully reported a CPU run through `nnapi-reference`
     * as 64 operators on an accelerator — the exact claim this is here to stop.
     */
    fun describe(where: String = delegate ?: "the delegate"): String = when {
        none -> "no operator ran on $where"
        partial -> "$delegated of $total operators on $where, " +
            "${total - delegated} on the CPU, $partitions partitions"
        else -> "all $total operators on $where, $partitions partitions"
    }

    companion object {
        /**
         * The last delegation the delegate reported in [log], or null when it
         * never said.
         *
         * The last, not the first: warmup and the timed run each apply the
         * delegate, and an older line may belong to a previous model.
         */
        fun parse(log: String): Delegation? =
            TFLITE_REPORT.findAll(log).lastOrNull()?.let { match ->
                Delegation(
                    delegated = match.groupValues[1].toInt(),
                    total = match.groupValues[2].toInt(),
                    partitions = match.groupValues[4].toInt(),
                    delegate = match.groupValues[3],
                )
            } ?: DELEGATE_REPORT.findAll(log).lastOrNull()?.let { match ->
                Delegation(
                    delegated = match.groupValues[1].toInt(),
                    total = match.groupValues[2].toInt(),
                    partitions = match.groupValues[3].toInt(),
                )
            }

        /**
         * Operators a delegate turned down, with the name it used.
         *
         * "12 of 14 nodes went to the accelerator" tells a compiler its
         * operator table is wrong somewhere; this tells it where. The NNAPI
         * delegate names them as it walks the graph, and nothing else does.
         */
        fun unsupported(log: String): List<String> =
            REFUSED.findAll(log)
                .map { it.groupValues[1].trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(MAX_REFUSALS)
                .toList()

        private const val MAX_REFUSALS = 40

        /**
         * Two ways the same fact gets stated, and both are looked for. TFLite
         * announces the partitioning itself and names the delegate that took
         * the nodes — the stronger statement, since a line about some other
         * delegate cannot be mistaken for this one. The delegate's own wording
         * is kept for where TFLite is not the one talking.
         */
        /** TFLite's own announcement, which names the delegate that claimed the nodes. */
        private val TFLITE_REPORT = Regex(
            """Replacing (\d+) out of (\d+) node\(s\) with delegate """ +
                """\(([A-Za-z0-9_]+)\) node, yielding (\d+) partitions""",
        )

        /** The QNN delegate's own wording, for where TFLite is not the one talking. */
        private val DELEGATE_REPORT =
            Regex("""(\d+) nodes delegated out of (\d+) nodes with (\d+) partitions""")

        /** How the NNAPI delegate reports an operator it will not take. */
        private val REFUSED = Regex(
            """(?:Operator|OP) ([A-Z_0-9]+)(?: \(v\d+\))? (?:is not supported|refused)""",
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
    delegation: Delegation?,
    profilingBytes: Int,
    /** The delegate this run is entitled to claim, when TFLite named one. */
    expectedDelegate: String? = null,
): String? = when {
    // XNNPACK announces its partitioning in exactly the same words. Reading
    // its line as ours would attribute a CPU run to the accelerator, which is
    // the one thing that must never happen.
    expectedDelegate != null && delegation?.delegate != null &&
        delegation.delegate != expectedDelegate ->
        "the graph was taken by ${delegation.delegate}, not $expectedDelegate"

    delegation == null && profilingBytes <= 0 ->
        "the delegate did not report what it executed, so this timing cannot be " +
            "attributed to the Hexagon"
    delegation != null && delegation.none ->
        "the delegate took no operators: ${delegation.describe()}"
    else -> null
}


