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


        // Checked against the shipped library by tools/check-tflite-wording.sh:
        // this is a regex over prose, and prose is not an API. TFLite 2.16.1
        // exposes nothing about partitioning — `InterpreterApi` has tensors and
        // timings, `NnApiDelegate` has an errno — so there is no alternative to
        // read instead, only a canary that fails when the wording moves (#128).
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

    }
}

/**
 * Whether the file is loadable at all, asked after something went wrong.
 *
 * The same move the operator matrix makes with its CPU control (#119): run it
 * with nothing attached, and if that fails too then the model is the defect and
 * no driver has been implicated. Only reached on a failure, so the second load
 * costs nothing in the normal case.
 */
internal fun modelIsUnloadable(model: java.io.File): Boolean =
    runCatching { org.tensorflow.lite.Interpreter(model).close() }.isFailure

/**
 * Who ran the graph, in the two words a result reports (issue #93).
 *
 * Naming the delegate is not enough on its own. Pinning to `mtk-mdla_shim` and
 * having XNNPACK take the graph means the NNAPI delegate refused and the CPU
 * picked it up — the pinned driver did nothing — and the first version of this
 * reported exactly that as `executed: accelerator`,
 * `executedBy: TfLiteXNNPackDelegate:mtk-mdla_shim`. Found by running on a
 * second vendor, which is the only reason it was found at all.
 */
internal fun executedFor(delegation: Delegation?, deviceName: String?): Pair<String, String> {
    val delegate = delegation?.delegate
    return when {
        delegation == null -> (if (deviceName == null) "cpu" else "unknown") to "cpu"

        // A CPU delegate took it. Whatever was asked for did not run it.
        delegate in CPU_DELEGATES ->
            (if (deviceName == null) "cpu" else "cpu-fallback") to (delegate ?: "cpu")

        // NNAPI's own reference driver is the CPU, whatever route reached it.
        // Reporting it as an accelerator is the same wrong claim as reporting
        // XNNPACK as one.
        deviceName in CPU_DEVICES -> "cpu" to "${delegate ?: "delegate"}:$deviceName"

        // Something took it that is not the delegate a pinned NNAPI device
        // would have gone through, so the pin did not happen.
        deviceName != null && delegate != null && delegate != NNAPI_DELEGATE ->
            "cpu-fallback" to delegate

        delegation.none -> "cpu-fallback" to "cpu"

        deviceName != null -> delegation.executed to "${delegate ?: "delegate"}:$deviceName"

        else -> delegation.executed to (delegate ?: "delegate")
    }
}

/** TFLite's own CPU delegate. It is not an accelerator however it is reached. */
private val CPU_DELEGATES = setOf("TfLiteXNNPackDelegate")

/** NNAPI drivers that are the CPU. The name says so; the report should too. */
private val CPU_DEVICES = setOf("nnapi-reference")

private const val NNAPI_DELEGATE = "TfLiteNnapiDelegate"

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


