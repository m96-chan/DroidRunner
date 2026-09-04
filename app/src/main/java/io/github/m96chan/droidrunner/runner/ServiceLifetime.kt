package io.github.m96chan.droidrunner.runner

/**
 * Who owns the service when a start and a stop overlap (issue #68).
 *
 * Tapping Stop and then Start leaves two things in flight: the supervisor
 * thread from the previous run, still on its way out, and a fresh start that
 * has already claimed the service. Both then reach for the same switches. The
 * rules are small, and they are here because getting them wrong leaves a
 * device that looks idle — the dashboard shows Stopped, which is exactly what
 * it shows when someone stopped it on purpose.
 */
internal object ServiceLifetime {

    /**
     * Whether a supervisor that has finished should take the service down with
     * it.
     *
     * Only if it is still the current one. A supervisor that was superseded
     * while it wound down is looking at a service that now belongs to a newer
     * start, and stopping it would strand that start with nothing running.
     */
    fun shouldStopService(
        myGeneration: Int,
        currentGeneration: Int,
        stopRequested: Boolean,
    ): Boolean = myGeneration == currentGeneration && !stopRequested

    /**
     * Whether a supervisor should keep looping.
     *
     * A newer start supersedes it: the supervisor runs on a single-thread
     * executor, so an old one that keeps going holds the queue and the new one
     * never begins.
     */
    fun shouldKeepRunning(
        myGeneration: Int,
        currentGeneration: Int,
        stopRequested: Boolean,
    ): Boolean = myGeneration == currentGeneration && !stopRequested
}
