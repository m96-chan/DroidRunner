package dev.devenus.droidrunner.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestartPolicyTest {

    @Test fun firstFailureWaitsTheInitialDelay() {
        assertEquals(
            RestartPolicy.INITIAL_DELAY_MS,
            RestartPolicy.nextDelayMs(previousDelayMs = 0, ranMillis = 1_000),
        )
    }

    @Test fun repeatedQuickFailuresBackOffExponentially() {
        var delay = 0L
        val observed = (1..4).map {
            delay = RestartPolicy.nextDelayMs(delay, ranMillis = 500)
            delay
        }
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L), observed)
    }

    @Test fun backoffIsCapped() {
        var delay = RestartPolicy.MAX_DELAY_MS
        delay = RestartPolicy.nextDelayMs(delay, ranMillis = 100)
        assertEquals(RestartPolicy.MAX_DELAY_MS, delay)
    }

    @Test fun aHealthyRunResetsTheBackoff() {
        // A listener that served jobs for a while and then exited should come
        // back promptly instead of inheriting an old crash-loop penalty.
        val delay = RestartPolicy.nextDelayMs(
            previousDelayMs = RestartPolicy.MAX_DELAY_MS,
            ranMillis = RestartPolicy.HEALTHY_RUN_MS + 1,
        )
        assertEquals(RestartPolicy.INITIAL_DELAY_MS, delay)
    }

    @Test fun recognisesRecoverableListenerNotices() {
        assertTrue(RestartPolicy.isRecoverableNotice("A session for this runner already exists."))
        assertTrue(
            RestartPolicy.isRecoverableNotice(
                "2026-09-02 12:53:59Z: Runner connect error: Error: Conflict",
            ),
        )
        assertFalse(RestartPolicy.isRecoverableNotice("Authentication failed: bad credentials"))
    }
}
