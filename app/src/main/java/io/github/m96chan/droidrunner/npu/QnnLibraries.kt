package io.github.m96chan.droidrunner.npu

import io.github.m96chan.droidrunner.device.HexagonVersion

/**
 * The order Qualcomm's libraries have to be opened in (issue #82, stage 4).
 *
 * Computed here, in the main process, and sent to the isolated process as a
 * plain list of names. That keeps the decision on this side of the boundary,
 * where it is GPL like the rest of the app and can be tested, and leaves the
 * process that loads Qualcomm's code holding nothing of ours but the loader
 * itself.
 *
 * Order matters twice over. `libQnnTFLiteDelegate.so` is opened last because
 * it is the one we then ask questions of, and everything it will reach for has
 * to be in the namespace by then: the QNN runtime opens its own pieces by bare
 * soname later, and the dynamic linker does not search app data directories.
 * A library already loaded is found by soname whatever path it came from,
 * which is what makes fetching them to `filesDir` work at all.
 */
internal object QnnLibraries {

    /**
     * What to open, in order, for a device on [htpVersion] — or null when that
     * generation has no published runtime.
     *
     * The skel is deliberately absent: it runs on the DSP, is loaded by the
     * DSP loader rather than by us, and is found through `ADSP_LIBRARY_PATH`.
     * dlopen'ing it here would be loading an aarch64 process's idea of a
     * Hexagon binary, which fails.
     */
    fun loadOrder(htpVersion: Int): List<String>? {
        if (QnnArtifacts.entriesFor(htpVersion) == null) return null
        val (_, stub) = HexagonVersion.libraries(htpVersion)
        return listOf(
            "libQnnSystem.so",
            "libQnnHtpPrepare.so",
            "libQnnHtp.so",
            stub,
            DELEGATE,
        )
    }

    /** The library the probe asks its questions of, once everything is open. */
    const val DELEGATE = "libQnnTFLiteDelegate.so"
}
