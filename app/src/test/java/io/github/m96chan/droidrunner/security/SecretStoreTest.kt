package io.github.m96chan.droidrunner.security

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #213: the removals have to be on disk before the method returns.
 *
 * Only the deletions are exercised here. Everything stored goes through the
 * Keystore, which a JVM test has no access to, but the clears touch nothing but
 * the preferences — which is the whole point: they are plain edits, and the
 * only question is whether they were written or queued.
 */
class SecretStoreTest {

    private class RecordingPreferences : SharedPreferences {
        val removed = mutableSetOf<String>()
        var cleared = false
        var commits = 0
        var applies = 0

        private val editor = object : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?) = this
            override fun putStringSet(key: String?, values: MutableSet<String>?) = this
            override fun putInt(key: String?, value: Int) = this
            override fun putLong(key: String?, value: Long) = this
            override fun putFloat(key: String?, value: Float) = this
            override fun putBoolean(key: String?, value: Boolean) = this
            override fun remove(key: String?) = this.also { key?.let(removed::add) }
            override fun clear() = this.also { cleared = true }
            override fun commit(): Boolean = true.also { commits++ }
            override fun apply() { applies++ }
        }

        override fun edit(): SharedPreferences.Editor = editor
        override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any>()
        override fun getString(key: String?, defValue: String?): String? = defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String?, defValue: Int) = defValue
        override fun getLong(key: String?, defValue: Long) = defValue
        override fun getFloat(key: String?, defValue: Float) = defValue
        override fun getBoolean(key: String?, defValue: Boolean) = defValue
        override fun contains(key: String?) = false
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
    }

    private val prefs = RecordingPreferences()
    private val store = SecretStore(prefs)

    @Test fun disconnectingWritesTheRemovalBeforeItReturns() {
        // The user tapped Disconnect and the OS killed the app a moment later.
        // A queued removal leaves the device resuming as a signed-in runner on
        // a sign-in its owner revoked, and the screen showing them connected.
        store.clearUserToken()

        assertEquals(0, prefs.applies)
        assertEquals(1, prefs.commits)
        assertTrue(
            prefs.removed.containsAll(
                setOf(
                    "github_user_token",
                    "github_user_refresh_token",
                    "github_user_token_expires_at",
                ),
            ),
        )
    }

    @Test fun anAbandonedDeviceFlowIsForgottenBeforeItReturns() {
        // The pending authorization is what makes the app resume polling after
        // the OS kills it, so one that outlives its own cancellation gets
        // picked up again on the next launch.
        store.clearPendingAuth()

        assertEquals(0, prefs.applies)
        assertEquals(1, prefs.commits)
        assertTrue(prefs.removed.contains("github_pending_auth"))
    }

    @Test fun wipingTheDeviceWritesBeforeItReturns() {
        // The one a user reaches for when handing the phone on.
        store.clear()

        assertEquals(0, prefs.applies)
        assertEquals(1, prefs.commits)
        assertTrue(prefs.cleared)
    }
}
