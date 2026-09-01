package dev.devenus.droidrunner.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    fun putPat(value: String) = putSecret(PAT, value)

    fun getPat(): String? = getSecret(PAT)

    fun putUserToken(value: String) = putSecret(USER_TOKEN, value)

    fun getUserToken(): String? = getSecret(USER_TOKEN)

    fun clearUserToken() = prefs.edit().remove(USER_TOKEN).apply()

    fun putPendingAuth(value: String) = putSecret(PENDING_AUTH, value)

    fun getPendingAuth(): String? = getSecret(PENDING_AUTH)

    fun clearPendingAuth() = prefs.edit().remove(PENDING_AUTH).apply()

    fun clear() = prefs.edit().clear().apply()

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
        const val PENDING_AUTH = "github_pending_auth"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
