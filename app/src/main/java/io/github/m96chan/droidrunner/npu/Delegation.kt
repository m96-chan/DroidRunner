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
        /**
         * Every claim in the log, in the order TFLite made them (issue #159).
         *
         * [parse] answers "what happened to this graph" and takes the last
         * line, which is the right answer while one delegate is attached. With
         * two, the last line is one delegate's share and reads as the whole —
         * so a partitioned run needs each claim kept apart, and the order is
         * what says which delegate was offered the graph first.
         */
        fun parseAll(log: String): List<Delegation> =
            TFLITE_REPORT.findAll(log).map { match ->
                Delegation(
                    delegated = match.groupValues[1].toInt(),
                    total = match.groupValues[2].toInt(),
                    partitions = match.groupValues[4].toInt(),
                    delegate = match.groupValues[3],
                )
            }.toList()

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
        // this is a regex over prose, and prose is not an API. TFLite exposes
        // nothing about partitioning — `InterpreterApi` has tensors and
        // timings, `NnApiDelegate` has an errno — so there is no alternative to
        // read instead, only a canary that fails when the wording moves (#128).
        //
        // Deliberately not naming a version. The one written here went stale on
        // the first dependency bump, and the artifact and the runtime do not
        // even agree with each other: Maven says 2.17.0 and the library's own
        // string says 2.18.0. `tools/check-tflite-wording.sh` is the claim that
        // stays true, because it re-asks on every build.
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
/**
 * Attribution when several delegates were attached (issue #170).
 *
 * [executedFor] asks whether the delegate that claimed the graph is the one a
 * pinned device would have gone through. With two attached that question has no
 * answer: the second delegate is a different one by design, so the rule that
 * catches a failed pin reports a working partitioned run as `cpu-fallback` —
 * which it did, on a graph where nothing touched the CPU at all.
 *
 * So the question becomes the union: between them, did they take everything.
 * Each entry's `delegated` counts original nodes, and a delegate node left by an
 * earlier pass is not claimable by a later one, so the counts are disjoint and
 * the first entry's `total` is the graph as it arrived.
 */
internal fun executedForAll(all: List<Delegation>): Pair<String, String> {
    if (all.isEmpty()) return "cpu-fallback" to "cpu"
    val claimed = all.sumOf { it.delegated }
    // Only the delegates that took something. Naming one that claimed nothing
    // was the other half of what #170 reported, and it is the same mistake
    // twice: reporting what was asked for as though it were what happened.
    val by = all.filter { it.delegated > 0 }
        .joinToString("+") { it.delegate ?: "delegate" }
        .ifEmpty { "cpu" }
    return when {
        claimed == 0 -> "cpu-fallback" to by
        claimed >= all.first().total -> "accelerator" to by
        else -> "partial" to by
    }
}

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

        // Asked for the GPU and the GPU delegate took it. Without this the
        // rule below would call a working GPU run a CPU fallback, because it
        // was written when NNAPI was the only way to ask for anything (#140).
        deviceName == GPU_DEVICE && delegate == GPU_DELEGATE ->
            delegation.executed to "$GPU_DELEGATE:$GPU_DEVICE"

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
 * What TFLite calls the GPU delegate, read out of the shipped library rather
 * than remembered: `strings libtensorflowlite_gpu_jni.so` has exactly one
 * name that the partitioning line can print.
 */
internal const val GPU_DELEGATE = "TfLiteGpuDelegateV2"

/** The device name a job asks for to reach it. */
internal const val GPU_DEVICE = "gpu"

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


