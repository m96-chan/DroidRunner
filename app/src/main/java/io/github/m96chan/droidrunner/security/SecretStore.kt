package io.github.m96chan.droidrunner.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The part of the store a [io.github.m96chan.droidrunner.github.UserSession]
 * reads and rewrites while renewing a sign-in. Narrow on purpose: renewal is
 * what races with itself (issue #196), and a test of that race needs a store
 * it can hold in memory, not a Keystore.
 */
interface UserTokenStore {
    fun getUserToken(): String?
    fun getUserRefreshToken(): String?
    fun getUserTokenExpiresAt(): Long?
    fun putUserToken(value: String, refreshToken: String? = null, expiresAtMillis: Long? = null)
}

/**
 * The preferences are taken rather than looked up, so a test can hand in its
 * own and see which writes actually reached them (issue #213). Callers keep
 * using the [Context] constructor.
 */
class SecretStore internal constructor(private val prefs: SharedPreferences) : UserTokenStore {

    constructor(context: Context) : this(context.getSharedPreferences("secrets", Context.MODE_PRIVATE))

    fun putPat(value: String) = putSecret(PAT, value)

    fun getPat(): String? = getSecret(PAT)

    /**
     * Stores a sign-in whole (issue #42): renewing one is only possible if the
     * refresh token and the expiry survive a restart with the access token, so
     * all three live here under the same Keystore key.
     */
    override fun putUserToken(value: String, refreshToken: String?, expiresAtMillis: Long?) {
        putSecret(USER_TOKEN, value)
        // A renewal that returned no new refresh token leaves the stored one in
        // place; dropping it would end the chain after a single renewal.
        refreshToken?.let { putSecret(USER_REFRESH_TOKEN, it) }
        if (expiresAtMillis == null) prefs.edit().remove(USER_TOKEN_EXPIRES_AT).commit()
        else putSecret(USER_TOKEN_EXPIRES_AT, expiresAtMillis.toString())
    }

    override fun getUserToken(): String? = getSecret(USER_TOKEN)

    override fun getUserRefreshToken(): String? = getSecret(USER_REFRESH_TOKEN)

    /** When the stored access token lapses, or null if GitHub never said. */
    override fun getUserTokenExpiresAt(): Long? = getSecret(USER_TOKEN_EXPIRES_AT)?.toLongOrNull()

    // commit(), for the reason putSecret uses it and with more at stake
    // (issue #213): the user taps Disconnect and the OS kills the app, and a
    // queued removal that never landed leaves the device resuming as a runner
    // on a sign-in its owner revoked.
    fun clearUserToken() = prefs.edit()
        .remove(USER_TOKEN)
        .remove(USER_REFRESH_TOKEN)
        .remove(USER_TOKEN_EXPIRES_AT)
        .commit()

    /** Stable Device Agent capability token: survives app restarts so a
     *  runner started by a previous app process can still authenticate. */
    fun agentToken(): String {
        getSecret(AGENT_TOKEN)?.let { return it }
        val token = java.security.SecureRandom().let { rng ->
            ByteArray(24).also(rng::nextBytes).joinToString("") { "%02x".format(it) }
        }
        putSecret(AGENT_TOKEN, token)
        return token
    }

    fun putPendingAuth(value: String) = putSecret(PENDING_AUTH, value)

    fun getPendingAuth(): String? = getSecret(PENDING_AUTH)

    fun clearPendingAuth() = prefs.edit().remove(PENDING_AUTH).commit()

    /** Reached when a phone is handed on, so it is the one removal that must
     *  not be still queued when the app goes away (issue #213). */
    fun clear() = prefs.edit().clear().commit()

    private fun putSecret(name: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val packed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        // commit(): these writes must survive an imminent process death (the OS
        // often kills the app while the user approves the code in the browser).
        prefs.edit().putString(name, Base64.encodeToString(packed, Base64.NO_WRAP)).commit()
    }

    private fun getSecret(name: String): String? {
        val encoded = prefs.getString(name, null) ?: return null
        return runCatching {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
            cipher.doFinal(packed.copyOfRange(12, packed.size)).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }
    }

    private companion object {
        const val ALIAS = "droidrunner-pat"
        const val PAT = "github_pat"
        const val USER_TOKEN = "github_user_token"
        const val USER_REFRESH_TOKEN = "github_user_refresh_token"
        const val USER_TOKEN_EXPIRES_AT = "github_user_token_expires_at"
        const val PENDING_AUTH = "github_pending_auth"
        const val AGENT_TOKEN = "device_agent_token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
