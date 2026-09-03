package io.github.m96chan.droidrunner.runner

/**
 * The decisions the supervisor makes in one pass of its loop (issue #59).
 *
 * Keeping this separate from [RunnerService] makes the safety-sensitive order
 * testable without constructing an Android service, starting a thread, or
 * launching a process. The service still owns all side effects.
 */
object SupervisorStep {
    sealed interface Action {
        data class Stop(val reason: String, val stopsActiveJob: Boolean) : Action
        data class AnnounceHold(val reason: String) : Action
        data object Resume : Action
        data object SweepStrays : Action
        data object Start : Action
    }

    data class Result(
        val actions: List<Action>,
        val pausedFor: String?,
    )

    fun decide(
        decision: Admission,
        hasProcess: Boolean,
        jobRunning: Boolean,
        nowMillis: Long,
        nextStartAtMillis: Long,
        pausedFor: String?,
    ): Result {
        val actions = mutableListOf<Action>()
        var nextPausedFor = pausedFor

        when (decision) {
            Admission.Allowed -> {
                if (pausedFor != null) {
                    actions += Action.Resume
                    nextPausedFor = null
                }
                if (!hasProcess && nowMillis >= nextStartAtMillis) {
                    actions += Action.SweepStrays
                    actions += Action.Start
                }
            }

            is Admission.Blocked -> {
                // A normal hold waits for the current job; critical heat does not.
                val mayStop = !jobRunning || decision.urgent
                if (hasProcess && mayStop) {
                    actions += Action.Stop(decision.reason, decision.urgent && jobRunning)
                }
                // Stop is synchronous and clears the process before this action runs.
                val willHaveProcess = hasProcess && !mayStop
                if (!willHaveProcess && pausedFor != decision.reason) {
                    actions += Action.AnnounceHold(decision.reason)
                    nextPausedFor = decision.reason
                }
            }
        }

        return Result(actions, nextPausedFor)
    }
}
