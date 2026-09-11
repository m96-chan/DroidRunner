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
        data class ReportCondition(val reason: String) : Action
        data class AnnounceHold(val reason: String) : Action
        data object ClearCondition : Action
        data object Resume : Action
        data object SweepStrays : Action
        data object Start : Action
    }

    data class Result(
        val actions: List<Action>,
        val reportedFor: String?,
        val held: Boolean,
    )

    fun decide(
        decision: Admission,
        hasProcess: Boolean,
        jobRunning: Boolean,
        nowMillis: Long,
        nextStartAtMillis: Long,
        reportedFor: String?,
        held: Boolean,
    ): Result {
        val actions = mutableListOf<Action>()
        var nextReportedFor = reportedFor
        var nextHeld = held

        when (decision) {
            Admission.Allowed -> {
                if (reportedFor != null) {
                    actions += if (held) Action.Resume else Action.ClearCondition
                    nextReportedFor = null
                    nextHeld = false
                }
                if (!hasProcess && nowMillis >= nextStartAtMillis) {
                    actions += Action.SweepStrays
                    actions += Action.Start
                }
            }

            is Admission.Pending -> {
                if (reportedFor != decision.reason) actions += Action.ReportCondition(decision.reason)
                nextReportedFor = decision.reason
            }

            is Admission.Blocked -> {
                if (reportedFor != decision.reason) actions += Action.ReportCondition(decision.reason)
                nextReportedFor = decision.reason
                // A normal hold waits for the current job; critical heat does not.
                val mayStop = !jobRunning || decision.urgent
                if (hasProcess && mayStop) {
                    actions += Action.Stop(decision.reason, decision.urgent && jobRunning)
                }
                // Stop is synchronous and clears the process before this action runs.
                val willHaveProcess = hasProcess && !mayStop
                if (!willHaveProcess && !held) {
                    actions += Action.AnnounceHold(decision.reason)
                    nextHeld = true
                }
            }
        }

        return Result(actions, nextReportedFor, nextHeld)
    }
}

/**
 * What the end of a listener process meant (issue #210).
 *
 * The thread reading a listener's output is the one that finds out it has
 * ended, and an exit code cannot tell it why. A hold — thermal, battery,
 * storage — reaches that thread as exit 130, which is also what a listener
 * killed by anything else reports, so every hold was counted as a crash: four
 * of them raised "DroidRunner is not staying up" about a runner doing exactly
 * what it was built to do, and each one doubled a backoff toward its
 * five-minute cap. Since [SupervisorStep] gates `Start` on that backoff, a
 * phone whose battery dipped then sat idle for minutes *after* it was plugged
 * back in.
 *
 * The supervisor knows the answer, because it is the one that asked. This is
 * where it tells the output thread.
 */
object ListenerExit {

    enum class Kind {
        /** The service is stopping. Nothing to report, nothing to restart. */
        SERVICE_STOP,

        /** Admission control asked for it. A hold is the design working. */
        POLICY_STOP,

        /** An ephemeral listener that finished the one job it registered for. */
        JOB_COMPLETED,

        /** Nobody asked. This is the one the backoff and the alerts are for. */
        FAILURE,
    }

    /**
     * [stoppedOnPurpose] is whether this exact process is the one the
     * supervisor killed — identity, not a flag, so a listener that crashed
     * while an older one was being stopped is still read as a crash.
     */
    fun classify(
        stopRequested: Boolean,
        stoppedOnPurpose: Boolean,
        ephemeral: Boolean,
        exitCode: Int,
    ): Kind = when {
        stopRequested -> Kind.SERVICE_STOP
        stoppedOnPurpose -> Kind.POLICY_STOP
        ephemeral && exitCode == 0 -> Kind.JOB_COMPLETED
        else -> Kind.FAILURE
    }

    /**
     * Whether the restart backoff should be forgotten.
     *
     * Both of the deliberate endings leave the runner ready to start again the
     * instant the supervisor is willing: the condition clearing is the only
     * thing anybody should be waiting for, and making them also wait out a
     * penalty accrued for stopping on request is the bug.
     */
    fun clearsBackoff(kind: Kind): Boolean = kind == Kind.POLICY_STOP || kind == Kind.JOB_COMPLETED

    /** Whether this ending says anything about the health of the device. */
    fun countsAsFailure(kind: Kind): Boolean = kind == Kind.FAILURE
}
