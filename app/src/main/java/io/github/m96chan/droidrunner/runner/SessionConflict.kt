package io.github.m96chan.droidrunner.runner

/**
 * The wait after an app update, and how to end it (issue #79).
 *
 * Replacing the APK kills the whole process group with a signal nothing can
 * catch, so the listener never gets to say it is leaving. GitHub holds its
 * session, and the replacement listener spends the next minute or several
 * being told `A session for this runner already exists` — while the dashboard
 * says `Starting`, which is what it also says when a device is in trouble.
 *
 * Two things are wrong with that, and they are separable. Saying nothing is a
 * bug in its own right and is fixed by naming the wait. Waiting at all is
 * fixable too: re-registering replaces the runner entry, and the stranded
 * session belongs to the entry that is replaced.
 *
 * Re-registering is not the first move, because the runner usually reconnects
 * on its own well inside a minute and a needless re-registration costs a
 * registration token and a new runner id. So: say so at once, act only if the
 * wait outlasts the patience below.
 */
object SessionConflict {

    /**
     * How long to let the listener sort it out before replacing the runner.
     *
     * Measured at 45 seconds on the update that prompted the issue, and worse
     * on a worse day. Waiting a little longer than the good case costs one
     * quiet minute; waiting for the bad case costs several.
     */
    const val PATIENCE_MS = 60_000L

    /** The listener is being told its session is taken. */
    fun isConflict(line: String): Boolean =
        line.contains("A session for this runner already exists", ignoreCase = true) ||
            // The retry line that follows it, which is the one that repeats.
            (line.contains("Runner connect error", ignoreCase = true) &&
                line.contains("Conflict", ignoreCase = true))

    /**
     * The listener got through.
     *
     * "Listening for Jobs" is the only line that means it, and it is already
     * what moves the runner to [RunnerState.LISTENING] — a conflict cleared by
     * anything less is a conflict that has not cleared.
     */
    fun isResolved(line: String): Boolean =
        line.contains("Listening for Jobs", ignoreCase = true)

    /** Whether the wait has gone on long enough to be worth ending. */
    fun shouldReplaceRunner(firstSeenMillis: Long?, nowMillis: Long): Boolean =
        firstSeenMillis != null && nowMillis - firstSeenMillis >= PATIENCE_MS

    /**
     * What the dashboard should say instead of `Starting`.
     *
     * True for a bounded time and about something the device did, rather than
     * a word that also covers a phone that cannot start at all.
     */
    fun describe(firstSeenMillis: Long?, nowMillis: Long): String? {
        if (firstSeenMillis == null) return null
        val seconds = ((nowMillis - firstSeenMillis) / 1000).coerceAtLeast(0)
        return "waiting for GitHub to release the previous session (${seconds}s)"
    }
}
