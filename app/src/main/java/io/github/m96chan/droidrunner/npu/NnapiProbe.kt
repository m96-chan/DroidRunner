package io.github.m96chan.droidrunner.npu

/** JNI bridge to the NNAPI probe (see cpp/nnapi_probe.c). */
object NnapiProbe {
    @Volatile
    private var loaded: Boolean? = null

    /** Whether the probe library — and the output capture beside it — loaded. */
    fun available(): Boolean = ensureLoaded()

    private fun ensureLoaded(): Boolean {
        loaded?.let { return it }
        return runCatching { System.loadLibrary("nnapi_probe") }
            .isSuccess
            .also { loaded = it }
    }

    /**
     * One timed run at a time (issue #197).
     *
     * The native side no longer has any shared state to corrupt: the reply
     * buffers are locals and the ADD tensors are allocated per call, so two
     * threads in the probe at once is now merely legal rather than a race.
     * This lock is not what makes that true, and it is not standing in for
     * it.
     *
     * What it is for is the number. Both benchmarks divide a wall-clock
     * duration by an iteration count and publish the result as this
     * accelerator's; a run that spent its time queued behind another run on
     * the same NPU has measured contention and calls it latency. The
     * dashboard and the matrix in `docs/` present these figures as
     * comparable between devices, and two workers make them incomparable
     * with themselves.
     *
     * It also keeps the app out of a question nobody here has answered: what
     * a given vendor driver does with two compilations in flight. That has
     * not been tested on any phone in the fleet and this is not a claim that
     * it would fail — only that a serialised probe does not need to know.
     *
     * The cost is that the second of two concurrent benchmark requests waits
     * for the first. Both runs are capped (200 CONV_2D iterations, 1000 ADD)
     * so the wait is bounded, and an agent worker blocked here is a slow
     * answer rather than a wrong one.
     *
     * [devices] stays outside the lock. It enumerates, times nothing, and
     * after the buffer fix has nothing left to share.
     */
    private val probeLock = Any()

    /** JSON: {"available":bool,"devices":[{name,type,version,featureLevel}]}. */
    fun devices(): String =
        if (ensureLoaded()) devicesJson()
        else "{\"available\":false,\"error\":\"probe library not loaded\"}"

    /** Runs a small ADD model, optionally pinned to one NNAPI device. */
    fun benchmark(deviceName: String?, iterations: Int): String =
        if (ensureLoaded()) synchronized(probeLock) { addBenchmark(deviceName, iterations) }
        else "{\"ok\":false,\"error\":\"probe library not loaded\"}"

    /** Runs a CONV_2D model — the workload vendor NPUs are actually built for. */
    fun conv(deviceName: String?, iterations: Int, size: Int, channels: Int, filters: Int): String =
        if (ensureLoaded()) {
            synchronized(probeLock) {
                convBenchmark(deviceName, iterations, size, channels, filters)
            }
        } else {
            "{\"ok\":false,\"error\":\"probe library not loaded\"}"
        }

    private external fun devicesJson(): String
    private external fun addBenchmark(deviceName: String?, iterations: Int): String
    private external fun convBenchmark(
        deviceName: String?,
        iterations: Int,
        size: Int,
        channels: Int,
        filters: Int,
    ): String
}
