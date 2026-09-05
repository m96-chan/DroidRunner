package io.github.m96chan.droidrunner.ui

import androidx.compose.ui.graphics.Color
import io.github.m96chan.droidrunner.runner.RunnerSnapshot
import io.github.m96chan.droidrunner.runner.RunnerState
import io.github.m96chan.droidrunner.runner.SessionConflict
import io.github.m96chan.droidrunner.ui.theme.BtopColors

/**
 * How a runner state reads on screen. Shared, so the dashboard and the
 * picture-in-picture window cannot drift into describing the same state
 * differently.
 */
/**
 * What the runner is doing, given everything known about it.
 *
 * `starting` is the word that also covers a phone that cannot start at all, so
 * a wait with a known cause and a bounded end says so instead (issue #79).
 */
internal fun RunnerSnapshot.label(nowMillis: Long = System.currentTimeMillis()): String =
    SessionConflict.describe(sessionHeldSince, nowMillis) ?: state.label()

internal fun RunnerState.label(): String = when (this) {
    RunnerState.STOPPED -> "stopped"
    RunnerState.STARTING -> "starting"
    RunnerState.LISTENING -> "listening for jobs"
    RunnerState.JOB_RUNNING -> "running job"
    RunnerState.PAUSED -> "paused"
}

internal fun RunnerState.color(): Color = when (this) {
    RunnerState.STOPPED -> BtopColors.Dim
    RunnerState.STARTING -> BtopColors.Yellow
    RunnerState.LISTENING -> BtopColors.Green
    RunnerState.JOB_RUNNING -> BtopColors.Cyan
    RunnerState.PAUSED -> BtopColors.Yellow
}

/**
 * Why an action that needs the listener down cannot run yet, in words that fit
 * the state the runner is actually in.
 *
 * Re-registering, installing a runtime and updating one all replace something
 * the listener is using, so all three wait for [RunnerState.STOPPED]. `PAUSED`
 * looks like it should qualify — admission control has already stopped the
 * listener — and it must not: the supervisor is still running, and the moment
 * the hold clears it starts the listener again on its own. A phone plugged back
 * in halfway through `config.sh` would meet a half-written identity.
 *
 * So the answer stays "stop it first", and the job of this is to say that in a
 * way that makes sense to someone looking at a runner that is visibly not
 * running. Told "stop the runner first" while the screen says *paused*, the
 * reasonable conclusion is that the button is broken — which is the report that
 * produced this.
 */
internal fun blockedUntilStopped(
    state: RunnerState,
    pausedReason: String?,
    /** What the user is trying to do, as a verb phrase: "re-registering". */
    action: String,
): String? = when (state) {
    RunnerState.STOPPED -> null

    RunnerState.PAUSED -> {
        val because = pausedReason?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        "Held by admission control$because, and it will start again by itself when " +
            "that clears — so stop it from the dashboard before $action."
    }

    else -> "Stop the runner first from the dashboard — $action replaces something " +
        "it is using."
}
