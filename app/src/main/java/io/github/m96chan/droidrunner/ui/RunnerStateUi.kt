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
