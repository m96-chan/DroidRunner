package io.github.m96chan.droidrunner.ui

import io.github.m96chan.droidrunner.github.GitHubApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four answers the release lookup can give, and what the panel does with
 * each (issues #193, #202).
 *
 * They used to be one null, and the screen read all of them as the build's
 * configuration. #193 gave the lookup a type that tells them apart; this is
 * the half that makes the screen act on the difference — so a dropped
 * connection offers Retry, and only a repository that genuinely publishes no
 * runtime sends the reader to `advanced`.
 */
class ResolveRuntimeManifestTest {

    private fun api(body: (url: String) -> String) =
        GitHubApi { _, url, _, _ -> body(url) }

    private fun releases(vararg tags: String) =
        tags.joinToString(",", "[", "]") {
            """{"tag_name":"$it","assets":[{"name":"runtime-manifest.json",""" +
                """"browser_download_url":"https://example.invalid/$it/runtime-manifest.json"}]}"""
        }

    @Test fun aRepositoryWithNoRuntimeReleaseSendsYouToAdvanced() {
        val resolution = resolveRuntimeManifest(api { releases("v0.14.0", "v0.13.0") }, "o/r", null)

        assertEquals(ManifestResolution.NoRelease, resolution)
        assertTrue(runtimeUnavailableMessage(resolution).contains("advanced"))
    }

    @Test fun aBuildNamingNoRepositoryIsNotAskedAboutTheNetwork() {
        // Nothing is sent — the blank repo is answered before GitHub is asked.
        val resolution = resolveRuntimeManifest(api { error("GitHub must not be asked") }, "", null)

        assertEquals(ManifestResolution.NotConfigured, resolution)
    }

    @Test fun aDroppedConnectionIsNotAMissingRelease() {
        // The whole point of #202: this must not read as "this repository
        // publishes none", because that sends the reader somewhere useless.
        val resolution = resolveRuntimeManifest(
            api { throw java.io.IOException("Unable to resolve host \"api.github.com\"") },
            "o/r",
            null,
        )

        assertTrue(resolution is ManifestResolution.Unreachable)
        assertTrue(runtimeUnavailableMessage(resolution).contains("retry"))
    }

    @Test fun aReleaseFoundIsResolvedWithItsUrl() {
        val resolution = resolveRuntimeManifest(
            api { url ->
                if (url.contains("/releases")) releases("runtime-0.2.0") else """{"version":"x"}"""
            },
            "o/r",
            null,
        )

        assertEquals(
            "https://example.invalid/runtime-0.2.0/runtime-manifest.json",
            (resolution as ManifestResolution.Resolved).url,
        )
    }
}
