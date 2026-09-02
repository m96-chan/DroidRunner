package dev.devenus.droidrunner.runtime

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

    @Test fun aManifestWithoutAVersionYieldsNull() {
        assertEquals(null, versionOf("""{"url":"https://example.com/bundle.tar.gz"}"""))
    }

    @Test fun malformedManifestYieldsNull() {
        assertEquals(null, versionOf("not json"))
    }
}
