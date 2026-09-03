package io.github.m96chan.droidrunner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.m96chan.droidrunner.monitor.SystemMonitor
import io.github.m96chan.droidrunner.monitor.SystemSnapshot
import io.github.m96chan.droidrunner.runner.RunnerState
import io.github.m96chan.droidrunner.runner.RunnerStatus
import io.github.m96chan.droidrunner.ui.theme.BtopColors
import kotlin.math.roundToInt

/**
 * The runner in a picture-in-picture window (issue #33).
 *
 * Not a smaller dashboard — the system only accepts aspect ratios between
 * 1:2.39 and 2.39:1, and the window is about a quarter of the screen wide, so
 * this is its own design. Three lines, in the order they are worth reading:
 * what the runner is doing, why if that needs explaining, and the numbers that
 * decide whether it can keep doing it.
 *
 * It reads the same flows the dashboard reads; nothing is computed twice.
 */
@Composable
fun PipScreen(monitor: SystemMonitor) {
    val runner by RunnerStatus.snapshot.collectAsState()
    val system by produceState(SystemSnapshot()) {
        monitor.snapshots().collect { value = it }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(BtopColors.Background)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("● ", color = runner.state.color(), style = MaterialTheme.typography.bodyLarge)
            Text(
                runner.state.label(),
                color = runner.state.color(),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // A held runner has to say why: held and broken look identical from
        // outside, and this window exists to answer exactly that question.
        val detail = runner.pausedReason ?: runner.currentJob
        if (detail != null) {
            Text(
                detail,
                color = if (runner.state == RunnerState.PAUSED) BtopColors.Yellow else BtopColors.Text,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        Text(
            pipStats(system, runner.jobsSucceeded, runner.jobsFailed),
            color = BtopColors.Dim,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * The one line of numbers the window has room for: what the device is doing to
 * itself, and what it has done for GitHub.
 */
internal fun pipStats(system: SystemSnapshot, succeeded: Int, failed: Int): String {
    val cpu = (system.cpuAverage * 100).roundToInt()
    val battery = "${system.batteryPercent}%" + if (system.charging) "⚡" else ""
    return "cpu $cpu% · bat $battery · ok:$succeeded fail:$failed"
}

/**
 * Whether leaving the app should leave a window behind.
 *
 * Only for a runner that is doing something: a stopped runner has nothing to
 * watch, and a window that appears every time someone glances at the app and
 * goes home is an annoyance rather than a feature. Setup is excluded because
 * shrinking a sign-in flow to a quarter of the screen helps nobody.
 */
internal fun shouldOfferPip(
    supported: Boolean,
    showingSetup: Boolean,
    state: RunnerState,
): Boolean = supported && !showingSetup && state != RunnerState.STOPPED
