package io.github.m96chan.droidrunner.runner

/**
 * Decides when a failure is worth interrupting someone over (issue #34).
 *
 * GitHub already emails when a job fails, so the app only speaks about what
 * GitHub cannot see. Even then it speaks once per problem: a phone that buzzes
 * on every retry teaches its owner to ignore it, which costs more than the
 * silence it replaces.
 */
object AlertPolicy {

    /** Failures the device can see and GitHub cannot. */
    enum class Failure { LISTENER, REGISTRATION, SIGN_IN }

    data class FailureRecord(
        val consecutiveFailures: Int,
        val alerted: Boolean,
        val alertNow: Boolean,
    )

    /**
     * A listener that exits gets the benefit of the doubt for a few restarts —
     * most of them recover on their own (issue #6), and recovery is not news.
     * By the fourth consecutive failure the backoff is already a minute long
     * and the device is not serving anything.
     */
    const val LISTENER_FAILURES_BEFORE_ALERT = 4

    /**
     * Registration failing usually means the sign-in expired, which retrying
     * will never fix, so it speaks up as soon as it is more than a blip.
     */
    const val REGISTRATION_FAILURES_BEFORE_ALERT = 2

    /**
     * A refused renewal is not a blip (issue #42). GitHub rejected the one
     * credential that could restore the sign-in, and every retry would present
     * the same one, so waiting for a second opinion only delays the person who
     * has to reconnect.
     */
    const val SIGN_IN_FAILURES_BEFORE_ALERT = 1

    fun shouldAlert(
        failure: Failure,
        consecutiveFailures: Int,
        alreadyAlerted: Boolean,
    ): Boolean {
        if (alreadyAlerted) return false
        val threshold = when (failure) {
            Failure.LISTENER -> LISTENER_FAILURES_BEFORE_ALERT
            Failure.REGISTRATION -> REGISTRATION_FAILURES_BEFORE_ALERT
            Failure.SIGN_IN -> SIGN_IN_FAILURES_BEFORE_ALERT
        }
        return consecutiveFailures >= threshold
    }

    /** Counts a failure and latches the first alert for the current streak. */
    fun recordFailure(
        failure: Failure,
        consecutiveFailures: Int,
        alreadyAlerted: Boolean,
    ): FailureRecord {
        val count = consecutiveFailures + 1
        val alertNow = shouldAlert(failure, count, alreadyAlerted)
        return FailureRecord(
            consecutiveFailures = count,
            alerted = alreadyAlerted || alertNow,
            alertNow = alertNow,
        )
    }
}
