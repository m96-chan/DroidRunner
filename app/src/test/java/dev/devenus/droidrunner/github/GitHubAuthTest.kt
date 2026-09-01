package dev.devenus.droidrunner.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubAuthTest {
    @Test fun pendingKeepsInterval() {
        assertEquals(5, GitHubAuth.nextDelaySeconds("authorization_pending", 5))
    }

    @Test fun slowDownAddsFiveSeconds() {
        assertEquals(10, GitHubAuth.nextDelaySeconds("slow_down", 5))
    }

    @Test fun terminalErrorsStopPolling() {
        assertNull(GitHubAuth.nextDelaySeconds("access_denied", 5))
        assertNull(GitHubAuth.nextDelaySeconds("expired_token", 5))
    }
}
