package io.github.m96chan.droidrunner.runtime

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Verifies that a runtime manifest was signed by a key this build trusts.
 *
 * The SHA-256 in the manifest proves the bundle matches the manifest; it says
 * nothing about who wrote the manifest. Whoever can replace the manifest can
 * point a device at a rootfs of their choosing, and that rootfs is where CI
 * jobs execute — so the manifest, not just the archive, needs an origin.
 *
 * ECDSA P-256 over the exact manifest bytes, verified with keys compiled into
 * the APK. Several keys may be trusted at once so a key can be rotated: a new
 * one ships in a release, and manifests signed by either verify until the old
 * one is dropped.
 */
object ManifestSignature {

    sealed interface Result {
        /** Signed by one of the trusted keys. */
        data object Valid : Result

        /** No key is compiled in, so signatures cannot be checked at all. */
        data object Unverifiable : Result

        data class Invalid(val reason: String) : Result
    }

    /**
     * @param manifestBytes the manifest exactly as served — re-serialising it
     *   would change the bytes the signature covers.
     * @param signatureBase64 contents of the `.sig` file published beside it.
     * @param trustedKeysBase64 comma-separated X.509 public keys (BuildConfig).
     */
    fun verify(
        manifestBytes: ByteArray,
        signatureBase64: String?,
        trustedKeysBase64: String,
    ): Result {
        val keys = trustedKeysBase64.split(",").map(String::trim).filter { it.isNotEmpty() }
        if (keys.isEmpty()) return Result.Unverifiable
        if (signatureBase64.isNullOrBlank()) {
            return Result.Invalid("manifest is not signed, but this build requires a signature")
        }
        val signature = runCatching { Base64.getMimeDecoder().decode(signatureBase64.trim()) }
            .getOrNull()
            ?: return Result.Invalid("signature is not valid base64")

        val verified = keys.any { key ->
            runCatching {
                val publicKey = KeyFactory.getInstance("EC")
                    .generatePublic(X509EncodedKeySpec(Base64.getMimeDecoder().decode(key)))
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(publicKey)
                    update(manifestBytes)
                    verify(signature)
                }
            }.getOrDefault(false)
        }
        return if (verified) Result.Valid else Result.Invalid("signature does not match a trusted key")
    }
}
