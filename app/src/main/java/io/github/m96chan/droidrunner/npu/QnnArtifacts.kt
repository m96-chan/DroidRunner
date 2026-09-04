package io.github.m96chan.droidrunner.npu

/**
 * What a Snapdragon needs from Qualcomm's QNN release, and what it must hash
 * to (issue #82, stage 2).
 *
 * Qualcomm publishes the runtime on Maven Central under a licence that permits
 * redistribution, so the app fetches it rather than shipping it — 101MB
 * unpacked for one Hexagon generation is not something to put in an APK that
 * most devices cannot use.
 *
 * Maven publishes only SHA-1 and MD5 digests beside its artifacts, so neither
 * is worth checking; the SHA-256 values below were computed once from the
 * published AARs and are pinned here. `art/qnn-pins.sh` regenerates them, and
 * a version bump means running it — a hash that no longer matches is the
 * intended outcome of an unexpected change, not a bug to work around.
 */
internal object QnnArtifacts {

    /** The QNN release these pins were taken from. */
    const val VERSION = "2.49.0"

    /** Which AAR an entry comes from. */
    enum class Module(val artifact: String) {
        RUNTIME("qnn-runtime"),
        DELEGATE("qnn-litert-delegate"),
    }

    data class Entry(
        val module: Module,
        val library: String,
        val sha256: String,
        /** Unpacked size, which is what it costs on disk. */
        val bytes: Long,
        /** Compressed size, which is what it costs to transfer. */
        val downloadBytes: Long,
    ) {
        /** Where it sits inside the AAR. */
        val zipEntry: String get() = "jni/$ABI/$library"
    }

    fun url(module: Module): String =
        "https://repo1.maven.org/maven2/com/qualcomm/qti/${module.artifact}/" +
            "$VERSION/${module.artifact}-$VERSION.aar"

    /**
     * Every file a device on [htpVersion] needs, or null when that generation
     * is not in the published runtime.
     */
    fun entriesFor(htpVersion: Int): List<Entry>? {
        val perVersion = HEXAGON[htpVersion] ?: return null
        return SHARED + perVersion
    }

    /** Disk cost of installing for [htpVersion], for the consent step to quote. */
    fun installBytes(htpVersion: Int): Long =
        entriesFor(htpVersion)?.sumOf { it.bytes } ?: 0L

    /**
     * Transfer cost, which is the number a metered connection cares about and
     * is under half the disk cost — quoting either one alone misleads.
     */
    fun downloadBytes(htpVersion: Int): Long =
        entriesFor(htpVersion)?.sumOf { it.downloadBytes } ?: 0L

    /** What marks an install as complete and identifies what it holds. */
    fun stamp(htpVersion: Int): String = "$VERSION v$htpVersion"

    private const val ABI = "arm64-v8a"

    /**
     * Needed whatever the generation. `libqnn_delegate_jni.so` is deliberately
     * absent: it exports only `Java_com_qualcomm_qti_QnnDelegate_*` entry
     * points for Qualcomm's own Java wrapper, whose classes this project does
     * not ship. The delegate is reached through its C API instead, so those
     * 279KB would never be called.
     * `libQnnHtpPrepare.so` is 79MB of the
     * 101MB and cannot be dropped: it compiles a model's graph for the NPU,
     * which is exactly what running an arbitrary `.tflite` asks for.
     */
    private val SHARED = listOf(
        Entry(Module.RUNTIME, "libQnnHtp.so", "4c13d31eff0d86336faceeaf0b3e8c6c2ccafc14e3d9fee72d5f1de441c6901d", 3_786_336, 1_463_692),
        Entry(Module.RUNTIME, "libQnnSystem.so", "a78a9b637ed814d5a4dc49219c673a794d9fca94e1149430530040aff925509a", 4_072_432, 1_491_137),
        Entry(Module.RUNTIME, "libQnnHtpPrepare.so", "2297c95919a389dc1d7b2c8f06970a4365be8b26f5cff34986ad0863a46724ea", 79_343_312, 32_362_670),
        Entry(Module.DELEGATE, "libQnnTFLiteDelegate.so", "143271c6a7637c6d1138f5c46b851c1f2889d27333c1c21ef666caf1104fa0c2", 1_032_472, 372_740),
    )

    /**
     * The generation-specific pair: the stub runs on the CPU, the skel on the
     * DSP itself. Loading the wrong generation's pair is the mistake the whole
     * of stage 1 exists to prevent, so only the pair named by the device's own
     * HTP version is ever fetched.
     */
    private val HEXAGON = mapOf(
        68 to listOf(
            Entry(Module.RUNTIME, "libQnnHtpV68Skel.so", "2758c2db5fe781b86aac905688797cd7ee0e2cf2915f3fec61568d390dd619b5", 10_631_272, 3_547_266),
            Entry(Module.RUNTIME, "libQnnHtpV68Stub.so", "aafeea34fd1f146cefeb069e1302f2612a80d5ddaaa69e79e33e3037fd2db7e4", 764_384, 280_667),
        ),
        69 to listOf(
            Entry(Module.RUNTIME, "libQnnHtpV69Skel.so", "3cf0781bb06754a7a1d1470b92a7fa8cf4faa2d85da84de23c48172ee9953af0", 12_007_340, 4_000_918),
            Entry(Module.RUNTIME, "libQnnHtpV69Stub.so", "81ea9c93d172eb2926466944c16a1cffe368278559568536c18c9eeb9c49d001", 764_944, 280_876),
        ),
        73 to listOf(
            Entry(Module.RUNTIME, "libQnnHtpV73Skel.so", "7be4f8a4ec21a9d8d51f59c73094154f42d2f8fc91cfaadaef03441b77d7ddb1", 17_909_588, 4_246_908),
            Entry(Module.RUNTIME, "libQnnHtpV73Stub.so", "f89096915f6707c9e7a780deaf47dedfec5cb7e3e2c3459208ef66e3861441ba", 772_200, 283_237),
        ),
        75 to listOf(
            Entry(Module.RUNTIME, "libQnnHtpV75Skel.so", "a56519d6ef8510c47bf955f919a119eb3d249f4845576f723cfb40ee8010ed5c", 17_913_608, 4_264_944),
            Entry(Module.RUNTIME, "libQnnHtpV75Stub.so", "78025b9ff8c5cf1c0017560bee0f447ae58fb8255f5fca0daca7d6a4818b909e", 772_200, 283_235),
        ),
        79 to listOf(
            Entry(Module.RUNTIME, "libQnnHtpV79Skel.so", "9cad65a621d154e5282ea9d2849d0a8838932ed91dc7e2514db4e992e2d933c6", 17_721_548, 4_290_647),
            Entry(Module.RUNTIME, "libQnnHtpV79Stub.so", "9908fb2cdc22bd35651e358bc851d203dcb170dec52df0f8779437863158599c", 772_200, 283_236),
        ),
        81 to listOf(
            Entry(Module.RUNTIME, "libQnnHtpV81Skel.so", "b3453265c4574c69bb446bcb98dda117ded531b86b2307e0f02c595050fab8b1", 18_844_384, 4_659_937),
            Entry(Module.RUNTIME, "libQnnHtpV81Stub.so", "a5235e7927a5074c4d22244696f84f2c007d90f2f609c6ba0f047e2f0c6abf65", 796_352, 292_096),
        ),
    )
}
