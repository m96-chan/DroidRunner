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
    fun loadOrder(htpVersion: Int, backend: String = "htp"): List<String>? {
        if (QnnArtifacts.entriesFor(htpVersion) == null) return null
        // The Adreno needs none of the Hexagon's apparatus: no prepare, no
        // stub, and no skel on the DSP side. Asking for them would only fail
        // to find files this device may never have fetched.
        if (backend == "gpu") {
            return listOf("libQnnSystem.so", QnnArtifacts.GPU_LIBRARY, DELEGATE)
        }
        val (_, stub) = HexagonVersion.libraries(htpVersion)
        return listOf(
            "libQnnSystem.so",
            "libQnnHtpPrepare.so",
            "libQnnHtp.so",
            stub,
            DELEGATE,
        )
    }

    /**
     * The library a backend cannot run without, when we do not ship it.
     *
     * `QnnBackend` has accepted "gpu" and "dsp" since #82 while the installer
     * fetched neither, so asking for one failed deep inside the delegate with
     * "Failed to apply delegate" and an empty vendor string — for a backend we
     * knew perfectly well was not there. Naming the file is the difference
     * between a caller filing a bug against Qualcomm and a caller installing
     * something.
     */
    fun missingLibraryFor(backend: String, installed: Set<String>): String? = when (backend) {
        "gpu" -> QnnArtifacts.GPU_LIBRARY.takeIf { it !in installed }
        "dsp" -> "libQnnDsp.so"
        else -> null
    }

    /** The library the probe asks its questions of, once everything is open. */
    const val DELEGATE = "libQnnTFLiteDelegate.so"
}
