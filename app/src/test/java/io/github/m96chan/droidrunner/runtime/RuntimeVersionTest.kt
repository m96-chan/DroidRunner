package io.github.m96chan.droidrunner.runtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The update check compares the installed bundle version against the version
 * named by the latest release's manifest (issue #14).
 */
class RuntimeVersionTest {
    private fun versionOf(manifest: String): String? =
        runCatching { JSONObject(manifest).optString("version").takeIf { it.isNotBlank() } }.getOrNull()

    @Test fun readsTheVersionFromAManifest() {
        val manifest = """
            {"version":"runner-2.337.0-ubuntu-24.04.3",
             "url":"https://example.com/bundle.tar.gz","sha256":"abc"}
        """.trimIndent()
        assertEquals("runner-2.337.0-ubuntu-24.04.3", versionOf(manifest))
    }

    @Test fun anIdenticalVersionIsNotAnUpdate() {
        val installed = "runner-2.337.0-ubuntu-24.04.3"
        assertEquals(installed, versionOf("""{"version":"$installed"}"""))
    }

    @Test fun aNewerRunnerIsAnUpdate() {
        val installed = "runner-2.337.0-ubuntu-24.04.3"
        val latest = versionOf("""{"version":"runner-2.340.0-ubuntu-24.04.3"}""")
        assertNotEquals(installed, latest)
    }

    /**
     * The case the ingredients alone cannot express: a release that changed
     * only what is inside the bundle — `droidrunner-device` — on a day when
     * actions/runner and ubuntu-base are the versions they already were. The
     * release tag is what makes the two strings differ, and differing is the
     * whole of the update check.
     */
    @Test fun aBundleRebuiltFromTheSameIngredientsIsStillAnUpdate() {
        val installed = "runner-2.337.0-ubuntu-24.04.3+runtime-0.2.0"
        val latest = versionOf("""{"version":"runner-2.337.0-ubuntu-24.04.3+runtime-0.3.0"}""")
        assertNotEquals(installed, latest)
    }

    /** A device that installed before the tag joined the version still updates. */
    @Test fun aVersionFromBeforeTheTagIsCarriedIsAnUpdate() {
        val installed = "runner-2.337.0-ubuntu-24.04.3"
        val latest = versionOf("""{"version":"runner-2.337.0-ubuntu-24.04.3+runtime-0.3.0"}""")
        assertNotEquals(installed, latest)
    }

    @Test fun aManifestWithoutAVersionYieldsNull() {
        assertEquals(null, versionOf("""{"url":"https://example.com/bundle.tar.gz"}"""))
    }

    @Test fun malformedManifestYieldsNull() {
        assertEquals(null, versionOf("not json"))
    }
}
