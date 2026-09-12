package io.github.m96chan.droidrunner.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a request carries before it is opened, and that the manifest fetch is
 * one of them (issues #202, #244).
 *
 * The fifteen seconds are the fix for the captive portal that accepts a
 * connection and never answers, and nothing asserted them. Waiting one out
 * would cost CI fifteen seconds, or a socket that accepts and never replies
 * plus a duration to shorten — and neither would have caught what actually
 * broke, which was not the value but the path: the manifest fetch set its own
 * copy of the timeouts on its own connection and never came here at all.
 *
 * So it is asserted in two halves. Here, that every request this class sends is
 * configured with them, read back off the connection before anything is opened
 * — `openConnection` does no I/O, so this costs nothing and touches no network.
 * And in `ResolveRuntimeManifestTest`, that the manifest fetch goes out through
 * this class's sender, which is the half that was missing.
 */
class GitHubRequestTest {

    @Test fun everyRequestWaitsFifteenSecondsAndNoLonger() {
        val connection = gitHubConnection("GET", "https://api.github.com/user", "t", null)

        assertEquals(15_000, connection.connectTimeout)
        assertEquals(15_000, connection.readTimeout)
    }

    @Test fun theManifestFetchGetsTheSameTimeouts() {
        // A release asset URL rather than api.github.com: this is the request
        // that had no timeout at all (#202), and then its own copy of one
        // (#244). Nothing here is special to the GitHub API, so nothing about
        // it justifies a second connection.
        val connection =
            gitHubConnection("GET", "https://example.invalid/runtime-manifest.json", null, null)

        assertEquals(15_000, connection.connectTimeout)
        assertEquals(15_000, connection.readTimeout)
    }

    @Test fun aBodyIsAnnouncedAsJsonAndTheApiVersionIsPinned() {
        // Not the Authorization header: the JDK refuses to read that one back
        // off a connection, so `getRequestProperty` answers null whether it
        // was set or not. Which token goes out is asserted at the sender
        // instead — see `fetchTextIsAPlainGetThroughTheSameSender` below.
        val connection = gitHubConnection("POST", "https://api.github.com/x", "secret", 12)

        assertEquals("2022-11-28", connection.getRequestProperty("X-GitHub-Api-Version"))
        assertEquals("application/json", connection.getRequestProperty("Content-Type"))
        assertEquals("application/vnd.github+json", connection.getRequestProperty("Accept"))
        assertEquals("POST", connection.requestMethod)
        assertTrue(connection.doOutput)
    }

    @Test fun aGetCarriesNoBodyAndDoesNotAnnounceOne() {
        val connection = gitHubConnection("GET", "https://api.github.com/user", "t", null)

        assertNull(connection.getRequestProperty("Content-Type"))
        assertFalse(connection.doOutput)
    }

    @Test fun fetchTextIsAPlainGetThroughTheSameSender() {
        var sent: List<String?>? = null
        val api = GitHubApi { method, url, token, body ->
            sent = listOf(method, url, token, body)
            """{"version":"0.2.0"}"""
        }

        val body = api.fetchText("https://example.invalid/runtime-manifest.json")

        assertEquals("""{"version":"0.2.0"}""", body)
        assertEquals(
            listOf("GET", "https://example.invalid/runtime-manifest.json", null, null),
            sent,
        )
    }
}
