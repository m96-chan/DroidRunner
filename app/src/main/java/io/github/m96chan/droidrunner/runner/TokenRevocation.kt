package io.github.m96chan.droidrunner.runner

import java.util.concurrent.atomic.AtomicReference

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
 * The identity test was right and was not atomic with what it guarded (issue
 * #212): the caller read the current process, found it was its own, and then
 * wrote null in a second step. A replacement listener starting in between meant
 * the old thread cleared a *live* handle — the supervisor then saw no listener,
 * swept the strays, and SIGKILLed the proot tree of a job that was running —
 * and revoked that job's token on the way past. Which is precisely the failure
 * described above, arrived at through the guard meant to prevent it. So the
 * test and the clear are one instruction, and the answer to "may I revoke" is
 * the same thing as having already taken the handle.
 *
 * Named rather than left as a `compareAndSet` at the call site, because that
 * reads as null-tidying and is a security guard.
 */
object TokenRevocation {

    /**
     * [ended] has exited; [current] is the handle the service keeps on whatever
     * listener it considers live.
     *
     * Clears it and answers true only for the caller whose own process was
     * still the current one — which is the caller that owes the token a
     * revocation. A handle already cleared, or already replaced, belongs to
     * somebody else: it is left exactly as found.
     */
    fun revokeIfCurrent(current: AtomicReference<Process?>, ended: Process): Boolean =
        current.compareAndSet(ended, null)
}
