package dev.devenus.droidrunner.runner

/**
 * Restart backoff for the listener process (issue #6). An unattended phone
 * must recover on its own, but a listener that fails instantly — expired
 * credentials, no network — should not be respawned in a tight loop.
 */
object RestartPolicy {
    const val INITIAL_DELAY_MS = 5_000L
    const val MAX_DELAY_MS = 300_000L

    /** A run at least this long counts as healthy and clears the backoff. */
    const val HEALTHY_RUN_MS = 60_000L

    /**
     * Next delay after a listener exit. [ranMillis] is how long it stayed up,
     * so a runner that served jobs for a while restarts promptly while a
     * crash-looping one backs off.
     */
    fun nextDelayMs(previousDelayMs: Long, ranMillis: Long): Long = when {
        ranMillis >= HEALTHY_RUN_MS -> INITIAL_DELAY_MS
        previousDelayMs <= 0 -> INITIAL_DELAY_MS
        else -> (previousDelayMs * 2).coerceAtMost(MAX_DELAY_MS)
    }

    /**
     * The listener retries this on its own after an unclean shutdown left a
     * session registered; it is a recovering state, not a failure.
     */
    fun isRecoverableNotice(line: String): Boolean =
        line.contains("already exists", ignoreCase = true) ||
            line.contains("Runner connect error", ignoreCase = true)
}
