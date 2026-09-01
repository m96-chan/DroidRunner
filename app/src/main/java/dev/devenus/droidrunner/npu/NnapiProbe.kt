package dev.devenus.droidrunner.npu

/** JNI bridge to the NNAPI probe (see cpp/nnapi_probe.c). */
object NnapiProbe {
    @Volatile
    private var loaded: Boolean? = null

    private fun ensureLoaded(): Boolean {
        loaded?.let { return it }
        return runCatching { System.loadLibrary("nnapi_probe") }
            .isSuccess
            .also { loaded = it }
    }

    /** JSON: {"available":bool,"devices":[{name,type,version,featureLevel}]}. */
    fun devices(): String =
        if (ensureLoaded()) devicesJson()
        else "{\"available\":false,\"error\":\"probe library not loaded\"}"

    /** Runs a small ADD model, optionally pinned to one NNAPI device. */
    fun benchmark(deviceName: String?, iterations: Int): String =
        if (ensureLoaded()) addBenchmark(deviceName, iterations)
        else "{\"ok\":false,\"error\":\"probe library not loaded\"}"

    /** Runs a CONV_2D model — the workload vendor NPUs are actually built for. */
    fun conv(deviceName: String?, iterations: Int, size: Int, channels: Int, filters: Int): String =
        if (ensureLoaded()) convBenchmark(deviceName, iterations, size, channels, filters)
        else "{\"ok\":false,\"error\":\"probe library not loaded\"}"

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
