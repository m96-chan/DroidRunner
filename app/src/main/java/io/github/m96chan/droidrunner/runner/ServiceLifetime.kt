package io.github.m96chan.droidrunner.runner

import java.util.concurrent.atomic.AtomicBoolean

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

    /**
     * Starts a halt on a thread of its own, and only ever one at a time
     * (issue #209).
     *
     * The stop the user taps arrives through `startService`, so it is delivered
     * on the main thread — and the halt behind it waits out a SIGINT shutdown
     * measured here at about nineteen seconds. Run inline, that is an
     * input-dispatch ANR at five seconds, the twenty-second foreground service
     * limit at twenty, and a frozen app for the whole of it. Which is how a
     * listener gets orphaned: the second tap, and then the force-stop.
     *
     * Taking the thread as a parameter rather than making one is what lets the
     * *does not block the caller* part be a unit test instead of a claim.
     * [halting] is held for the whole halt so a second tap joins the one in
     * flight; the caller keeps it, because the service has to be able to ask
     * whether a halt is under way.
     *
     * @return true if this call is the one that started the halt.
     */
    fun beginStop(halting: AtomicBoolean, onThread: (Runnable) -> Unit, halt: Runnable): Boolean {
        if (!halting.compareAndSet(false, true)) return false
        onThread(
            Runnable {
                try {
                    halt.run()
                } finally {
                    halting.set(false)
                }
            },
        )
        return true
    }

    /**
     * Whether a supervisor pass that threw is one to carry on from (issue #211).
     *
     * Everything the loop touches can fail for a moment and be fine the next:
     * `StatFs` on a path that has gone away, a receiver the system would not
     * register, a fork refused under memory pressure while a job is building.
     * None of those is a reason to stop being a runner for the quarter of an
     * hour it takes the watchdog to notice. An interrupt is different — it is
     * `shutdownNow`, which only the service's own teardown sends.
     */
    fun survivesIterationError(error: Throwable): Boolean = error !is InterruptedException

    /**
     * Whether a failing pass is worth a line in the log.
     *
     * Something that is broken for good — a sensor that will throw until the
     * phone is rebooted — would otherwise write a line every poll, twelve a
     * minute, and the log that explains a bad night is a hundred lines long.
     * So the first of a kind is always said, and a repeat only every
     * [QUIET_REPEATS] passes after that.
     */
    fun shouldReportIterationError(previous: String?, current: String, repeats: Int): Boolean =
        previous != current || repeats % QUIET_REPEATS == 0

    /** Roughly five minutes at the supervisor's poll interval. */
    const val QUIET_REPEATS = 60
}
