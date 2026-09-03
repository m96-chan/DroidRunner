package io.github.m96chan.droidrunner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.m96chan.droidrunner.device.DeviceCapabilities
import io.github.m96chan.droidrunner.monitor.SystemMonitor
import io.github.m96chan.droidrunner.monitor.SystemSnapshot
import io.github.m96chan.droidrunner.runner.BootGapPolicy
import io.github.m96chan.droidrunner.runner.RunnerSnapshot
import io.github.m96chan.droidrunner.runner.RunnerState
import io.github.m96chan.droidrunner.runner.RunnerStatus
import io.github.m96chan.droidrunner.runtime.RuntimeInstaller
import io.github.m96chan.droidrunner.ui.theme.BtopColors
import java.io.File

@Composable
fun DashboardScreen(
    capabilities: DeviceCapabilities,
    runtime: RuntimeInstaller,
    monitor: SystemMonitor,
    onOpenSetup: () -> Unit,
    onStartRunner: () -> Unit,
    onStopRunner: () -> Unit,
) {
    val system by produceState(SystemSnapshot()) {
        monitor.snapshots().collect { value = it }
    }
    val runner by RunnerStatus.snapshot.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .background(BtopColors.Background)
            .safeDrawingPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Header(capabilities, runner, onOpenSetup)
        CpuPanel(system)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MemPanel(system, Modifier.weight(1f))
            DiskPanel(system, Modifier.weight(1f))
        }
        PowerNetPanel(system)
        // Claims the space the monitors leave, so the log grows with the
        // screen instead of stranding an empty gap below the panel.
        RunnerPanel(runner, runtime, onStartRunner, onStopRunner, Modifier.weight(1f))
    }
}

@Composable
private fun Header(capabilities: DeviceCapabilities, runner: RunnerSnapshot, onOpenSetup: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("DroidRunner", style = MaterialTheme.typography.headlineMedium, color = BtopColors.Text)
        Spacer(Modifier.weight(1f))
        Text("● ", color = runner.state.color(), style = MaterialTheme.typography.bodyLarge)
        Text(runner.state.label(), color = runner.state.color(), style = MaterialTheme.typography.bodyMedium)
        Text(
            "⚙",
            color = BtopColors.Yellow,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .clickable { onOpenSetup() }
                .padding(start = 12.dp, top = 2.dp, bottom = 2.dp, end = 2.dp),
        )
    }
    Text(
        "${capabilities.manufacturer} ${capabilities.model} · ${capabilities.labels().sorted().joinToString(" ")}",
        color = BtopColors.Dim,
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
private fun CpuPanel(system: SystemSnapshot) {
    Panel("cpu") {
        HistoryGraph(system.cpuHistory, color = BtopColors.Cyan)
        Spacer(Modifier.padding(top = 8.dp))
        Meter("avg", system.cpuAverage)
        Spacer(Modifier.padding(top = 4.dp))
        val cores = system.cores
        val rows = (cores.size + 1) / 2
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(rows) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    for (column in 0..1) {
                        val index = row + column * rows
                        val core = cores.getOrNull(index)
                        if (core != null) {
                            Meter(
                                "C$index",
                                core.usage,
                                Modifier.weight(1f),
                                detail = if (core.curFreqMhz > 0) "${core.curFreqMhz}MHz"
                                else "${(core.usage * 100).toInt()}%",
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemPanel(system: SystemSnapshot, modifier: Modifier = Modifier) {
    Panel("mem", modifier) {
        HistoryGraph(system.memHistory, height = 36.dp, color = BtopColors.Magenta)
        Spacer(Modifier.padding(top = 6.dp))
        Meter("", system.memFraction)
        Text(
            "${formatBytes(system.memUsedBytes)} / ${formatBytes(system.memTotalBytes)}",
            color = BtopColors.Dim,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun DiskPanel(system: SystemSnapshot, modifier: Modifier = Modifier) {
    Panel("disk", modifier) {
        Spacer(Modifier.padding(top = 6.dp))
        Meter("", system.diskFraction)
        Text(
            "${formatBytes(system.diskUsedBytes)} / ${formatBytes(system.diskTotalBytes)}",
            color = BtopColors.Dim,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.padding(top = 8.dp))
        Text("app-private storage", color = BtopColors.Dim, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun PowerNetPanel(system: SystemSnapshot) {
    Panel("pwr/net") {
        Meter(
            if (system.charging) "bat⚡" else "bat",
            system.batteryPercent / 100f,
            detail = "${system.batteryPercent}%",
            color = if (system.batteryPercent < 25 && !system.charging) BtopColors.Red else BtopColors.Green,
        )
        Spacer(Modifier.padding(top = 6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "temp ${system.batteryTempC?.let { "%.1f°C".format(it) } ?: "--"} · ${thermalLabel(system.thermalStatus)}",
                color = thermalColor(system.thermalStatus),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                "▼${formatBytes(system.netRxPerSec)}/s ▲${formatBytes(system.netTxPerSec)}/s",
                color = BtopColors.Blue,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun RunnerPanel(
    runner: RunnerSnapshot,
    runtime: RuntimeInstaller,
    onStartRunner: () -> Unit,
    onStopRunner: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keyed on state + log growth so a fresh registration shows up immediately.
    val configuredRepo = remember(runner.state, runner.recentLog.size) {
        runCatching { File(runtime.runtimeDir, ".configured").readText().trim() }.getOrNull()
    }
    Panel("runner", modifier, titleColor = BtopColors.Green, fillHeight = true) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("● ", color = runner.state.color(), style = MaterialTheme.typography.bodyLarge)
            Text(runner.state.label(), color = runner.state.color(), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            runner.startedAtMillis?.let {
                Text("up ${formatUptime(it)}", color = BtopColors.Dim, style = MaterialTheme.typography.labelMedium)
            }
        }
        runner.pausedReason?.let { reason ->
            Text(
                "holding jobs: $reason",
                color = BtopColors.Yellow,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
            )
        }
        // Dim, and no notification behind it: this is something that already
        // finished happening, and waking someone for it would help nobody.
        runner.bootGapMs?.let { gap ->
            Text(
                "offline ${BootGapPolicy.describe(gap)} after boot — the device was " +
                    "locked, and the runner cannot start before it is unlocked",
                color = BtopColors.Dim,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 3,
            )
        }
        Text(
            configuredRepo ?: "not registered to a repository yet",
            color = if (configuredRepo != null) BtopColors.Text else BtopColors.Dim,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "jobs ok:${runner.jobsSucceeded} fail:${runner.jobsFailed}",
                color = BtopColors.Dim,
                style = MaterialTheme.typography.labelMedium,
            )
            runner.currentJob?.let {
                Text(
                    "job: $it",
                    color = BtopColors.Yellow,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.padding(top = 6.dp))
        val logScroll = rememberScrollState()
        // Newest lines stay in view as the runner talks.
        LaunchedEffect(runner.recentLog.size) { logScroll.animateScrollTo(logScroll.maxValue) }
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(BtopColors.Background)
                .verticalScroll(logScroll)
                .padding(6.dp),
        ) {
            runner.recentLog.forEach { line ->
                Text(
                    line,
                    color = BtopColors.Dim,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = runtime.installed && configuredRepo != null && runner.state == RunnerState.STOPPED,
                colors = ButtonDefaults.buttonColors(
                    containerColor = BtopColors.Green,
                    contentColor = BtopColors.Background,
                ),
                onClick = onStartRunner,
                modifier = Modifier.weight(1f),
            ) { Text("Start") }
            OutlinedButton(
                enabled = runner.state != RunnerState.STOPPED,
                onClick = onStopRunner,
                modifier = Modifier.weight(1f),
            ) { Text("Stop") }
        }
    }
}


private fun thermalLabel(status: Int?): String = when (status) {
    null -> "thermal n/a"
    0 -> "thermal none"
    1 -> "thermal light"
    2 -> "thermal moderate"
    3 -> "thermal severe"
    4 -> "thermal critical"
    else -> "thermal emergency"
}

private fun thermalColor(status: Int?) = when {
    status == null || status <= 0 -> BtopColors.Dim
    status == 1 -> BtopColors.Green
    status == 2 -> BtopColors.Yellow
    else -> BtopColors.Red
}
