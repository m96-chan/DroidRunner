package io.github.m96chan.droidrunner.npu

import android.content.Context
import org.json.JSONObject

/**
 * Whether this device has been shown to run a model on its Hexagon, and what
 * that showed (issue #82, stage 6).
 *
 * The point of recording it is the label. `npu-qnn` has been on these phones
 * since long before any of this worked, put there by [SocVendor] from the SoC
 * name — so a workflow selecting `npu-qnn` landed on a device that ran
 * everything on its CPU and said nothing (#80). A label is worth having only
 * if it is evidence, and the evidence available now is a model that
 * demonstrably executed on the accelerator.
 *
 * Installing the runtime is not that evidence. It proves the files are on
 * disk; every failure between stages 4 and 5 had the files on disk.
 */
internal data class QnnVerdict(
    val verified: Boolean,
    /** One line for the panel, saying what was actually observed. */
    val detail: String,
    val medianUs: Double? = null,
    val delegated: Int? = null,
    val total: Int? = null,
) {
    /** What this device may advertise, which is nothing unless it was shown. */
    val labels: Set<String> get() = if (verified) setOf(LABEL) else emptySet()

    companion object {
        const val LABEL = "npu-qnn"
    }
}

/**
 * Reads the verdict out of a run reported by `QnnModelRunner`.
 *
 * A run is only evidence when the delegate said what it executed *and* what it
 * executed was not nothing. `ok` alone is not enough: the runner refuses
 * unattributable timings, but this is the place where a mistake becomes a
 * label other people rely on, so it checks again rather than trusting.
 */
internal fun verdictFrom(runJson: String): QnnVerdict {
    val root = runCatching { JSONObject(runJson) }.getOrNull()
        ?: return QnnVerdict(false, "the NPU check returned no readable answer")

    if (!root.optBoolean("ok")) {
        return QnnVerdict(
            verified = false,
            detail = root.optString("error").ifBlank { "the NPU check failed" },
        )
    }

    val delegation = root.optJSONObject("delegation")
        ?: return QnnVerdict(false, "the run did not say which processor executed it")
    val delegated = delegation.optInt("delegated", 0)
    val total = delegation.optInt("total", 0)
    if (delegated <= 0) {
        return QnnVerdict(false, "no operator ran on the Hexagon")
    }

    val median = root.optDouble("medianUs").takeIf { !it.isNaN() }
    return QnnVerdict(
        verified = true,
        detail = buildString {
            append("verified: ")
            append(delegation.optString("describe").ifBlank { "$delegated of $total operators" })
            median?.let { append(", %.2fms".format(it / 1000.0)) }
        },
        medianUs = median,
        delegated = delegated,
        total = total,
    )
}

/**
 * Where the verdict lives between runs of the app.
 *
 * In SharedPreferences beside the consent, not beside the libraries: it
 * describes what this hardware did, and reinstalling the runtime does not make
 * that untrue. Reinstalling the *app* does not either — but a QNN release
 * whose libraries differ might, so the release it was shown with is recorded
 * and a change clears it.
 */
internal class QnnVerificationStore(private val context: Context) {

    private val prefs
        get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The stored verdict, or null when this device has never been shown to work. */
    fun read(): QnnVerdict? {
        val release = prefs.getString(KEY_RELEASE, null) ?: return null
        if (release != QnnArtifacts.VERSION) return null
        return QnnVerdict(
            verified = prefs.getBoolean(KEY_VERIFIED, false),
            detail = prefs.getString(KEY_DETAIL, "").orEmpty(),
            medianUs = prefs.getFloat(KEY_MEDIAN, 0f).toDouble().takeIf { it > 0 },
            delegated = prefs.getInt(KEY_DELEGATED, 0).takeIf { it > 0 },
            total = prefs.getInt(KEY_TOTAL, 0).takeIf { it > 0 },
        )
    }

    fun write(verdict: QnnVerdict) {
        prefs.edit()
            .putString(KEY_RELEASE, QnnArtifacts.VERSION)
            .putBoolean(KEY_VERIFIED, verdict.verified)
            .putString(KEY_DETAIL, verdict.detail)
            .putFloat(KEY_MEDIAN, (verdict.medianUs ?: 0.0).toFloat())
            .putInt(KEY_DELEGATED, verdict.delegated ?: 0)
            .putInt(KEY_TOTAL, verdict.total ?: 0)
            .apply()
    }

    /** What this device may advertise about its Hexagon. */
    fun labels(): Set<String> = read()?.labels.orEmpty()

    private companion object {
        const val PREFS = "qnn_verification"
        const val KEY_RELEASE = "qnn_release"
        const val KEY_VERIFIED = "verified"
        const val KEY_DETAIL = "detail"
        const val KEY_MEDIAN = "median_us"
        const val KEY_DELEGATED = "delegated"
        const val KEY_TOTAL = "total"
    }
}
