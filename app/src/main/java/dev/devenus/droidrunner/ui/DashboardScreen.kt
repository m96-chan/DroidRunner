package dev.devenus.droidrunner.ui

import android.content.Context
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.devenus.droidrunner.device.DeviceCapabilities
import dev.devenus.droidrunner.github.GitHubApi
import dev.devenus.droidrunner.model.RunnerConfig
import dev.devenus.droidrunner.monitor.SystemMonitor
import dev.devenus.droidrunner.monitor.SystemSnapshot
import dev.devenus.droidrunner.runner.RunnerCommand
import dev.devenus.droidrunner.runner.RunnerSnapshot
import dev.devenus.droidrunner.runner.RunnerState
import dev.devenus.droidrunner.runner.RunnerStatus
import dev.devenus.droidrunner.runtime.RuntimeInstaller
import dev.devenus.droidrunner.security.SecretStore
import dev.devenus.droidrunner.ui.theme.BtopColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun DashboardScreen(
    capabilities: DeviceCapabilities,
    runtime: RuntimeInstaller,
    secretStore: SecretStore,
    monitor: SystemMonitor,
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
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Header(capabilities, runner)
        CpuPanel(system)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MemPanel(system, Modifier.weight(1f))
            DiskPanel(system, Modifier.weight(1f))
        }
        PowerNetPanel(system)
        RunnerPanel(runner, runtime)
        SetupPanel(capabilities, runtime, secretStore, runner, onStartRunner, onStopRunner)
        Spacer(Modifier.padding(bottom = 8.dp))
    }
}

@Composable
private fun Header(capabilities: DeviceCapabilities, runner: RunnerSnapshot) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("DroidRunner", style = MaterialTheme.typography.headlineMedium, color = BtopColors.Text)
        Spacer(Modifier.weight(1f))
        Text("● ", color = runner.state.color(), style = MaterialTheme.typography.bodyLarge)
        Text(runner.state.label(), color = runner.state.color(), style = MaterialTheme.typography.bodyMedium)
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
private fun RunnerPanel(runner: RunnerSnapshot, runtime: RuntimeInstaller) {
    val configuredRepo = remember(runner.state) {
        runCatching { File(runtime.runtimeDir, ".configured").readText().trim() }.getOrNull()
    }
    Panel("runner", titleColor = BtopColors.Green) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("● ", color = runner.state.color(), style = MaterialTheme.typography.bodyLarge)
            Text(runner.state.label(), color = runner.state.color(), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            runner.startedAtMillis?.let {
                Text("up ${formatUptime(it)}", color = BtopColors.Dim, style = MaterialTheme.typography.labelMedium)
            }
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
        if (runner.recentLog.isNotEmpty()) {
            Spacer(Modifier.padding(top = 6.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(BtopColors.Background)
                    .padding(6.dp),
            ) {
                runner.recentLog.takeLast(8).forEach { line ->
                    Text(
                        line,
                        color = BtopColors.Dim,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupPanel(
    capabilities: DeviceCapabilities,
    runtime: RuntimeInstaller,
    secretStore: SecretStore,
    runner: RunnerSnapshot,
    onStartRunner: () -> Unit,
    onStopRunner: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("setup", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val configured = remember { File(runtime.runtimeDir, ".configured").isFile }
    var expanded by remember { mutableStateOf(!configured) }
    var owner by remember { mutableStateOf(prefs.getString("owner", "").orEmpty()) }
    var repo by remember { mutableStateOf(prefs.getString("repo", "").orEmpty()) }
    var pat by remember { mutableStateOf(secretStore.getPat().orEmpty()) }
    var manifestUrl by remember { mutableStateOf(prefs.getString("manifest", "").orEmpty()) }
    var status by remember { mutableStateOf(if (runtime.installed) "runtime installed" else "runtime not installed") }
    var busy by remember { mutableStateOf(false) }

    Panel("setup", titleColor = BtopColors.Yellow) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (expanded) "▼" else "▶", color = BtopColors.Yellow, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(8.dp))
            Text(status, color = BtopColors.Dim, style = MaterialTheme.typography.labelMedium)
        }
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                SetupField(owner, { owner = it }, "GitHub owner")
                SetupField(repo, { repo = it }, "Repository")
                SetupField(pat, { pat = it }, "Fine-grained PAT")
                SetupField(manifestUrl, { manifestUrl = it }, "Runtime manifest URL")

                Button(
                    enabled = !busy && manifestUrl.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = BtopColors.Cyan, contentColor = BtopColors.Background),
                    onClick = {
                        busy = true
                        prefs.edit().putString("manifest", manifestUrl).apply()
                        scope.launch {
                            status = runCatching {
                                withContext(Dispatchers.IO) { runtime.install(manifestUrl) { status = "runtime: $it" } }
                                "runtime installed"
                            }.getOrElse { "failed: ${it.message}" }
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Install runtime") }

                Button(
                    enabled = !busy && runtime.installed && owner.isNotBlank() && repo.isNotBlank() && pat.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = BtopColors.Green, contentColor = BtopColors.Background),
                    onClick = {
                        busy = true
                        prefs.edit().putString("owner", owner).putString("repo", repo).apply()
                        val deviceId = android.provider.Settings.Secure.getString(
                            context.contentResolver, android.provider.Settings.Secure.ANDROID_ID,
                        )?.takeLast(6) ?: "device"
                        val config = RunnerConfig(
                            owner, repo,
                            "android-${android.os.Build.MODEL}-$deviceId",
                            capabilities.labels(),
                        )
                        scope.launch {
                            status = runCatching {
                                config.validate()?.let { error(it) }
                                secretStore.putPat(pat)
                                withContext(Dispatchers.IO) {
                                    val token = GitHubApi().createRegistrationToken(owner, repo, pat)
                                    val process = ProcessBuilder(RunnerCommand.configure(runtime.runtimeDir, config, token))
                                        .redirectErrorStream(true).start()
                                    val output = process.inputStream.bufferedReader().readText()
                                    check(process.waitFor() == 0) { output.takeLast(1000) }
                                    File(runtime.runtimeDir, ".configured").writeText(config.repositoryUrl)
                                }
                                "registered to $owner/$repo"
                            }.getOrElse { "failed: ${it.message}" }
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Register repository") }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = runtime.installed && !busy && runner.state == RunnerState.STOPPED,
                        colors = ButtonDefaults.buttonColors(containerColor = BtopColors.Green, contentColor = BtopColors.Background),
                        onClick = onStartRunner,
                        modifier = Modifier.weight(1f),
                    ) { Text("Start") }
                    OutlinedButton(
                        enabled = runner.state != RunnerState.STOPPED,
                        onClick = onStopRunner,
                        modifier = Modifier.weight(1f),
                    ) { Text("Stop") }
                }
                Text(
                    "Never route untrusted fork PRs to this runner. Jobs can read data on this device.",
                    color = BtopColors.Red,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun SetupField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value, onChange,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = BtopColors.Cyan,
            unfocusedBorderColor = BtopColors.Border,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun RunnerState.label(): String = when (this) {
    RunnerState.STOPPED -> "stopped"
    RunnerState.STARTING -> "starting"
    RunnerState.LISTENING -> "listening for jobs"
    RunnerState.JOB_RUNNING -> "running job"
}

private fun RunnerState.color() = when (this) {
    RunnerState.STOPPED -> BtopColors.Dim
    RunnerState.STARTING -> BtopColors.Yellow
    RunnerState.LISTENING -> BtopColors.Green
    RunnerState.JOB_RUNNING -> BtopColors.Cyan
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
