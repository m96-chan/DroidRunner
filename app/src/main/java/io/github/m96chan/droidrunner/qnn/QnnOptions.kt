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
