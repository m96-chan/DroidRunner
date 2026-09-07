package io.github.m96chan.droidrunner.runner

/**
 * When a listener that has ended should take the job's capability token with it
 * (issue #172).
 *
 * A listener that dies mid-job never prints its completion line, so the
 * log-parsing path that normally ends a job never fires and the token stayed
 * valid for as long as the service lived. Revoking on every termination fixes
 * that and introduces the opposite hazard: the supervisor restarts quickly, so
 * a replacement listener can already be running a new job — with a new token —
 * by the time the old thread finishes waiting on its process. An unconditional
 * revoke there would cut off the job that is actually running.
 *
 * So the rule is identity, not timing: revoke only if the process that ended is
 * still the one the service considers current.
 *
 * Named rather than left as `if (process === started)` at the call site,
 * because that reads as null-tidying and is a security guard.
 */
object TokenRevocation {

    /**
     * [ended] has exited; [current] is what the service holds now. Null means
     * nothing is running, so there is no newer job to protect.
     */
    fun shouldRevoke(current: Process?, ended: Process): Boolean =
        current == null || current === ended
}
