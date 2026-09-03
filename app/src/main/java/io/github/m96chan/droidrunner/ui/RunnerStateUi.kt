package io.github.m96chan.droidrunner.ui

import androidx.compose.ui.graphics.Color
import io.github.m96chan.droidrunner.runner.RunnerState
import io.github.m96chan.droidrunner.ui.theme.BtopColors

/**
 * How a runner state reads on screen. Shared, so the dashboard and the
 * picture-in-picture window cannot drift into describing the same state
 * differently.
 */
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
