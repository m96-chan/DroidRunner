package io.github.m96chan.droidrunner.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures are real: the key pair was generated with openssl and the signature
 * produced by `openssl dgst -sha256 -sign`, which is exactly what the release
 * workflow will run. A hand-rolled fake would prove only that the code agrees
 * with itself.
 */
class ManifestSignatureTest {
    private val manifest = """{"version":"runner-2.337.0-ubuntu-24.04.3","url":"https://example.com/b.tar.gz","sha256":"abc"}""".toByteArray()
    private val signature = "MEUCIHGmFwoUiGqm4HiykXi6Smt9XHdiHRqtWRjzMB7dWx8XAiEAkDvjPUuz7DH5UiPHyMMm04Gcm19TY8Pv/BuZUoN1vDI="
    private val trustedKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEGG9zcB4RI2uBS7Y6tWokxlT7SOPL4LvLj854H8CvU1obd13uwKuHJ8pUlQT0s7VNzeT+CWQgg1Gr/2JkzDjmbA=="
    private val otherKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE+J5lEyjpYdarfwE58dn2vC9FmiJ/ibKaa/RF3upsCEIkLFHOsbpEsJsI6XcmGufqC+RHFGWmhsaaMdO+0vOLaQ=="

    @Test fun acceptsAManifestSignedByTheTrustedKey() {
        assertEquals(
            ManifestSignature.Result.Valid,
            ManifestSignature.verify(manifest, signature, trustedKey),
        )
    }

    @Test fun rejectsAManifestSignedByAnotherKey() {
        val result = ManifestSignature.verify(manifest, signature, otherKey)
        assertTrue(result is ManifestSignature.Result.Invalid)
    }

    @Test fun acceptsDuringKeyRotationWhenEitherKeyMatches() {
        // Rotation ships the new key alongside the old one; manifests signed
        // by either must verify until the old key is dropped.
        assertEquals(
            ManifestSignature.Result.Valid,
            ManifestSignature.verify(manifest, signature, "$otherKey,$trustedKey"),
        )
    }

    @Test fun rejectsAModifiedManifest() {
        val tampered = String(manifest).replace("example.com", "evil.example").toByteArray()
        val result = ManifestSignature.verify(tampered, signature, trustedKey)
        assertTrue(result is ManifestSignature.Result.Invalid)
    }

    @Test fun rejectsAMissingSignatureWhenAKeyIsConfigured() {
        val result = ManifestSignature.verify(manifest, null, trustedKey)
        assertTrue(result is ManifestSignature.Result.Invalid)
        assertTrue((result as ManifestSignature.Result.Invalid).reason.contains("not signed"))
    }

    @Test fun rejectsGarbageSignature() {
        assertTrue(ManifestSignature.verify(manifest, "not base64 !!", trustedKey)
            is ManifestSignature.Result.Invalid)
    }

    @Test fun reportsUnverifiableWhenNoKeyIsCompiledIn() {
        // Self-builders get a warning, not a hard failure.
        assertEquals(
            ManifestSignature.Result.Unverifiable,
            ManifestSignature.verify(manifest, signature, ""),
        )
    }
}
