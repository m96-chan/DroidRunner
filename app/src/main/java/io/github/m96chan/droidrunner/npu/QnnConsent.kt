package io.github.m96chan.droidrunner.npu

import android.content.Context

/**
 * Where this device remembers that someone read Qualcomm's terms and agreed to
 * them (issue #82, stage 3).
 *
 * Kept in SharedPreferences rather than beside the installed libraries, so an
 * acceptance outlives the thing it permitted: reinstalling or updating the QNN
 * runtime does not ask again, and neither does updating this app. Clearing the
 * app's data does, which is right — that is a new start on a device whose
 * owner may have changed.
 *
 * What is stored is [QnnLicences.fingerprint], not a boolean. A `true` would
 * still read as consent after Qualcomm changed the terms under it.
 */
class QnnConsent(private val context: Context) {

    private val prefs
        get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The licences this device accepted, or null if it never did. */
    val record: String? get() = prefs.getString(KEY_RECORD, null)

    /** Whether that record still covers what would be fetched now. */
    val granted: Boolean get() = QnnLicences.accepted(record)

    /** When it was accepted, or 0. Shown so the answer can be re-examined. */
    val acceptedAt: Long get() = prefs.getLong(KEY_AT, 0L)

    fun accept() {
        prefs.edit()
            .putString(KEY_RECORD, QnnLicences.fingerprint())
            .putLong(KEY_AT, System.currentTimeMillis())
            .apply()
    }

    /** Withdraws acceptance. The installed libraries are the caller's to remove. */
    fun withdraw() {
        prefs.edit().remove(KEY_RECORD).remove(KEY_AT).apply()
    }

    private companion object {
        const val PREFS = "qnn_consent"
        const val KEY_RECORD = "accepted_licences"
        const val KEY_AT = "accepted_at"
    }
}
