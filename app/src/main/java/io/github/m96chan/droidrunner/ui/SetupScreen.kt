package io.github.m96chan.droidrunner.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.m96chan.droidrunner.BuildConfig
import io.github.m96chan.droidrunner.device.DeviceCapabilities
import io.github.m96chan.droidrunner.github.DeviceAuthorization
import io.github.m96chan.droidrunner.github.GitHubApi
import io.github.m96chan.droidrunner.github.GitHubAuth
import io.github.m96chan.droidrunner.github.RepositoryRef
import io.github.m96chan.droidrunner.github.storedDeviceAuthorization
import io.github.m96chan.droidrunner.github.toStoredJson
import io.github.m96chan.droidrunner.model.RunnerConfig
import io.github.m96chan.droidrunner.model.RunnerTarget
import io.github.m96chan.droidrunner.runner.AdmissionThresholds
import io.github.m96chan.droidrunner.runner.RunnerRegistration
import io.github.m96chan.droidrunner.runner.ThermalStatus
import io.github.m96chan.droidrunner.npu.NpuLabels
import io.github.m96chan.droidrunner.runner.RunnerState
import io.github.m96chan.droidrunner.runner.RunnerStatus
import io.github.m96chan.droidrunner.runtime.RuntimeInstaller
import io.github.m96chan.droidrunner.security.SecretStore
import io.github.m96chan.droidrunner.ui.theme.BtopColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File

/** Full-screen setup flow: GitHub login, repository pick, runtime, register. */
@Composable
fun SetupScreen(
    capabilities: DeviceCapabilities,
    runtime: RuntimeInstaller,
    secretStore: SecretStore,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val prefs = remember { context.getSharedPreferences("setup", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val api = remember { GitHubApi() }
    val clientId = BuildConfig.GITHUB_APP_CLIENT_ID
    val runner by RunnerStatus.snapshot.collectAsState()

    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Long-running setup runs behind a modal, so nobody wanders off mid-flight.
    var progress by remember { mutableStateOf<SetupProgress?>(null) }
    var setupJob by remember { mutableStateOf<Job?>(null) }
    val configured = remember(runner.state, status, busy) { File(runtime.runtimeDir, ".configured").isFile }

    // Latest runtime-* release of the configured runtime repo; lets Register
    // install the runtime automatically with no manifest URL to paste.
    var resolvedManifest by remember { mutableStateOf<String?>(null) }
    var resolvingManifest by remember { mutableStateOf(BuildConfig.RUNTIME_REPO.isNotBlank()) }
    var latestRuntimeVersion by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (BuildConfig.RUNTIME_REPO.isNotBlank()) {
            resolvedManifest = runCatching {
                withContext(Dispatchers.IO) {
                    api.latestRuntimeManifestUrl(BuildConfig.RUNTIME_REPO, secretStore.getUserToken())
                }
            }.getOrNull()
            // The manifest names the bundle version, so an installed runtime
            // that has fallen behind the latest release can be reported.
            latestRuntimeVersion = resolvedManifest?.let { url ->
                runCatching {
                    withContext(Dispatchers.IO) {
                        org.json.JSONObject(java.net.URL(url).readText()).optString("version")
                            .takeIf { it.isNotBlank() }
                    }
                }.getOrNull()
            }
            resolvingManifest = false
        }
    }

    val statusText = status ?: when {
        runner.state != RunnerState.STOPPED -> "runner active"
        configured -> "registered — runner starts automatically"
        runtime.installed -> "runtime installed — pick a repository"
        resolvedManifest != null -> "pick a repository — runtime installs on register"
        else -> "runtime not installed"
    }

    var userToken by remember { mutableStateOf(secretStore.getUserToken()) }
    var deviceAuth by remember { mutableStateOf<DeviceAuthorization?>(null) }
    var authJob by remember { mutableStateOf<Job?>(null) }
    var repos by remember { mutableStateOf<List<RepositoryRef>>(emptyList()) }
    var reposLoaded by remember { mutableStateOf(false) }
    var loadingRepos by remember { mutableStateOf(false) }
    var selectedRepo by remember { mutableStateOf<RepositoryRef?>(null) }
    // Organization scope serves every repository in the org, so it is opt-in.
    var organizationScope by remember { mutableStateOf(prefs.getBoolean("org_scope", false)) }
    var organizations by remember { mutableStateOf<List<RunnerTarget.Organization>>(emptyList()) }
    var selectedOrg by remember { mutableStateOf<RunnerTarget.Organization?>(null) }

    // Battery-optimization exemption keeps Doze from throttling the runner
    // when the device sits idle and unplugged. Re-checked on resume because
    // the grant happens in a system dialog outside the app.
    val powerManager = remember { context.getSystemService(android.os.PowerManager::class.java) }
    var batteryExempt by remember {
        mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName))
    }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                batteryExempt = powerManager.isIgnoringBatteryOptimizations(context.packageName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var advanced by remember { mutableStateOf(false) }
    var owner by remember { mutableStateOf(prefs.getString("owner", "").orEmpty()) }
    var repo by remember { mutableStateOf(prefs.getString("repo", "").orEmpty()) }
    var pat by remember { mutableStateOf(secretStore.getPat().orEmpty()) }
    var manifestUrl by remember { mutableStateOf(prefs.getString("manifest", "").orEmpty()) }

    fun disconnect() {
        secretStore.clearUserToken()
        userToken = null
        repos = emptyList()
        reposLoaded = false
        selectedRepo = null
    }

    fun refreshRepos() {
        val token = userToken ?: return
        loadingRepos = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val installations = api.listInstallations(token)
                    // Remember the app slug so the install button can deep-link
                    // without a build-time GITHUB_APP_SLUG.
                    installations.firstOrNull { it.appSlug.isNotBlank() }?.let {
                        prefs.edit().putString("app_slug", it.appSlug).apply()
                    }
                    installations.flatMap { api.listInstallationRepositories(token, it.id) }
                }
            }.onSuccess { found ->
                repos = found
                reposLoaded = true
                organizations = runCatching {
                    withContext(Dispatchers.IO) { api.listOrganizations(token) }
                }.getOrDefault(emptyList())
                if (selectedOrg !in organizations) {
                    val remembered = prefs.getString("selected_org", null)
                    selectedOrg = organizations.firstOrNull { it.org == remembered }
                        ?: organizations.singleOrNull()
                }
                if (selectedRepo !in found) {
                    val remembered = prefs.getString("selected_repo", null)
                    selectedRepo = found.firstOrNull { it.fullName == remembered } ?: found.singleOrNull()
                }
            }.onFailure { failure ->
                android.util.Log.e("DroidRunner", "repo refresh failed", failure)
                if (failure.message?.contains("GitHub API 401") == true) {
                    disconnect()
                    status = "GitHub session expired, connect again"
                } else {
                    status = "failed: ${failure.message}"
                }
            }
            loadingRepos = false
        }
    }

    fun startPolling(auth: DeviceAuthorization) {
        authJob = scope.launch {
            runCatching {
                val token = withContext(Dispatchers.IO) { GitHubAuth(clientId).awaitToken(auth) }
                secretStore.clearPendingAuth()
                secretStore.putUserToken(token.accessToken)
                userToken = token.accessToken
                status = null
            }.onFailure {
                deviceAuth = null
                if (it is kotlinx.coroutines.CancellationException) throw it
                android.util.Log.e("DroidRunner", "device flow failed", it)
                secretStore.clearPendingAuth()
                status = "failed: ${it.message}"
            }
            deviceAuth = null
        }
    }

    fun manifestSource(): String? = manifestUrl.ifBlank { resolvedManifest.orEmpty() }.ifBlank { null }

    fun registerRunner(target: RunnerTarget, credential: String) {
        if (RunnerRegistration.load(runtime.runtimeDir)?.target == target) {
            status = null
            return
        }
        busy = true
        val deviceId = android.provider.Settings.Secure.getString(
            context.contentResolver, android.provider.Settings.Secure.ANDROID_ID,
        )?.takeLast(6) ?: "device"
        // Probe-verified NNAPI labels join the SoC-name hints, so jobs can
        // target backends this device actually reports.
        val config = RunnerConfig(
            target,
            "android-${android.os.Build.MODEL}-$deviceId",
            capabilities.labels() + NpuLabels.refresh(context),
        )
        progress = SetupProgress("preparing")
        setupJob = scope.launch {
            status = runCatching {
                config.validate()?.let { error(it) }
                if (!runtime.installed) {
                    val manifest = manifestSource()
                        ?: error("No runtime release found — set a manifest URL under advanced")
                    // runInterruptible so Cancel actually breaks the blocking
                    // download and extraction, rather than leaving them running.
                    runInterruptible(Dispatchers.IO) {
                        runtime.install(manifest) { phase, fraction ->
                            progress = SetupProgress(phase, fraction)
                        }
                    }
                }
                progress = SetupProgress("registering ${target.displayName}")
                runInterruptible(Dispatchers.IO) {
                    // Stream config.sh output into the runner panel's log tail
                    // so the slow proot/.NET startup is visible.
                    RunnerRegistration.register(
                        context, runtime.runtimeDir, config,
                        ephemeral = RunnerRegistration.ephemeralEnabled(context),
                    ) { line ->
                        RunnerStatus.onRunnerLine(line)
                        progress = SetupProgress("registering ${target.displayName}", detail = line)
                    }
                }
                null
            }.getOrElse { failure ->
                if (failure is kotlinx.coroutines.CancellationException) "setup cancelled"
                else "failed: ${failure.message}"
            }
            progress = null
            busy = false
        }
    }

    LaunchedEffect(userToken) { if (userToken != null) refreshRepos() }

    // Resume a device authorization that was pending when the OS killed the app
    // (e.g. while the user approved the code in the browser).
    LaunchedEffect(Unit) {
        if (userToken == null && deviceAuth == null) {
            val stored = secretStore.getPendingAuth()
            android.util.Log.d("DroidRunner", "resume check: pending=${stored != null}")
            stored?.let {
                val auth = storedDeviceAuthorization(it)
                if (auth == null) {
                    secretStore.clearPendingAuth()
                } else {
                    deviceAuth = auth
                    startPolling(auth)
                }
            }
        }
    }

    progress?.let { current ->
        SetupProgressDialog(current) {
            setupJob?.cancel()
            setupJob = null
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(BtopColors.Background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "‹ back",
                color = BtopColors.Cyan,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clickable { onClose() }.padding(vertical = 4.dp, horizontal = 2.dp),
            )
            Spacer(Modifier.weight(1f))
            Text("setup", color = BtopColors.Yellow, style = MaterialTheme.typography.titleMedium)
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val isError = statusText.startsWith("failed")
            Text(
                statusText,
                color = if (isError) BtopColors.Red else BtopColors.Dim,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (isError) {
                Text(
                    "✕",
                    color = BtopColors.Dim,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable { status = null }.padding(horizontal = 6.dp),
                )
            }
        }

        Panel("github", titleColor = BtopColors.Cyan) {
            when {
                clientId.isBlank() -> Text(
                    "This build has no GitHub App client id (droidrunner.githubAppClientId). " +
                        "Use the manual PAT setup below.",
                    color = BtopColors.Dim,
                    style = MaterialTheme.typography.labelSmall,
                )

                userToken == null && deviceAuth == null -> Button(
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = BtopColors.Cyan, contentColor = BtopColors.Background),
                    onClick = {
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) { GitHubAuth(clientId).requestDeviceCode() }
                            }.onSuccess { auth ->
                                deviceAuth = auth
                                secretStore.putPendingAuth(auth.toStoredJson())
                                clipboard.setText(AnnotatedString(auth.userCode))
                                startPolling(auth)
                            }.onFailure { status = "failed: ${it.message}" }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Connect GitHub") }

                deviceAuth != null -> DeviceCodePrompt(
                    auth = deviceAuth!!,
                    onOpenBrowser = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(deviceAuth!!.verificationUri)))
                    },
                    onCancel = {
                        authJob?.cancel()
                        secretStore.clearPendingAuth()
                        deviceAuth = null
                    },
                )

                else -> ScopePicker(
                    organizationScope = organizationScope,
                    onScopeChange = {
                        organizationScope = it
                        prefs.edit().putBoolean("org_scope", it).apply()
                    },
                    organizations = organizations,
                    selectedOrg = selectedOrg,
                    onSelectOrg = {
                        selectedOrg = it
                        prefs.edit().putString("selected_org", it.org).apply()
                    },
                ) { RepositoryPicker(
                    repos = repos,
                    reposLoaded = reposLoaded,
                    loading = loadingRepos,
                    selected = selectedRepo,
                    onSelect = {
                        selectedRepo = it
                        prefs.edit().putString("selected_repo", it.fullName).apply()
                    },
                    onInstallApp = {
                        val slug = BuildConfig.GITHUB_APP_SLUG
                            .ifBlank { prefs.getString("app_slug", "").orEmpty() }
                        val url = if (slug.isNotBlank()) "https://github.com/apps/$slug/installations/new"
                        else "https://github.com/settings/installations"
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    },
                    onRefresh = { refreshRepos() },
                    onDisconnect = { disconnect() },
                ) }
            }
        }

        Panel("runtime", titleColor = BtopColors.Cyan) {
            when {
                runtime.installed -> {
                    val installedVersion = runtime.installedVersion
                    val outOfDate = latestRuntimeVersion != null &&
                        latestRuntimeVersion != installedVersion
                    Text(
                        "installed: $installedVersion",
                        color = if (outOfDate) BtopColors.Yellow else BtopColors.Green,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    if (outOfDate) {
                        Spacer(Modifier.padding(top = 6.dp))
                        Text(
                            "update available: $latestRuntimeVersion",
                            color = BtopColors.Yellow,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Spacer(Modifier.padding(top = 6.dp))
                        Button(
                            enabled = !busy && runner.state == RunnerState.STOPPED,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BtopColors.Yellow,
                                contentColor = BtopColors.Background,
                            ),
                            onClick = {
                                val manifest = manifestSource() ?: return@Button
                                busy = true
                                progress = SetupProgress("preparing")
                                setupJob = scope.launch {
                                    status = runCatching {
                                        runInterruptible(Dispatchers.IO) {
                                            runtime.install(manifest) { phase, fraction ->
                                                progress = SetupProgress(phase, fraction)
                                            }
                                        }
                                        null
                                    }.getOrElse { failure ->
                                        if (failure is kotlinx.coroutines.CancellationException) "update cancelled"
                                        else "failed: ${failure.message}"
                                    }
                                    progress = null
                                    busy = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Update runtime") }
                        if (runner.state != RunnerState.STOPPED) {
                            Text(
                                "Stop the runner first — updating swaps the directory it runs from.",
                                color = BtopColors.Dim,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }

                resolvingManifest -> Text(
                    "checking runtime releases…",
                    color = BtopColors.Dim,
                    style = MaterialTheme.typography.labelMedium,
                )

                manifestSource() != null -> Text(
                    "latest release found — downloads automatically when you register (~200MB)",
                    color = BtopColors.Dim,
                    style = MaterialTheme.typography.labelMedium,
                )

                else -> Text(
                    "no runtime release found — set a manifest URL under advanced",
                    color = BtopColors.Yellow,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }

        Panel("job policy", titleColor = BtopColors.Cyan) {
            var thresholds by remember { mutableStateOf(AdmissionThresholds.load(context)) }
            fun update(next: AdmissionThresholds) {
                thresholds = next
                next.save(context)
            }

            Text(
                "New jobs are held while the device is unfit; a running job is only " +
                    "interrupted at critical heat.",
                color = BtopColors.Dim,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.padding(top = 8.dp))

            Toggle("require charging", thresholds.requireCharging) {
                update(thresholds.copy(requireCharging = it))
            }
            var ephemeral by remember { mutableStateOf(RunnerRegistration.ephemeralEnabled(context)) }
            Toggle("ephemeral (re-register and wipe the work dir per job)", ephemeral) {
                ephemeral = it
                RunnerRegistration.setEphemeralEnabled(context, it)
            }
            Spacer(Modifier.padding(top = 6.dp))
            Choice(
                "min battery",
                listOf(0, 20, 30, 50, 80),
                thresholds.minimumBatteryPercent,
                { "$it%" },
            ) { update(thresholds.copy(minimumBatteryPercent = it)) }
            Spacer(Modifier.padding(top = 6.dp))
            Choice(
                "max thermal",
                listOf(ThermalStatus.NONE, ThermalStatus.LIGHT, ThermalStatus.MODERATE, ThermalStatus.SEVERE),
                thresholds.maximumThermalStatus,
                { ThermalStatus.label(it) },
            ) { update(thresholds.copy(maximumThermalStatus = it)) }
            Spacer(Modifier.padding(top = 6.dp))
            Choice(
                "min free",
                listOf(512, 1024, 2048, 5120, 10240),
                thresholds.minimumFreeStorageMb,
                { if (it >= 1024) "${it / 1024}GB" else "${it}MB" },
            ) { update(thresholds.copy(minimumFreeStorageMb = it)) }
        }

        Panel("power", titleColor = BtopColors.Cyan) {
            var bootAutostart by remember {
                mutableStateOf(prefs.getBoolean("boot_autostart", true))
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        bootAutostart = !bootAutostart
                        prefs.edit().putBoolean("boot_autostart", bootAutostart).apply()
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (bootAutostart) "[✓]" else "[ ]",
                    color = if (bootAutostart) BtopColors.Green else BtopColors.Dim,
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "start runner on device boot",
                    color = BtopColors.Text,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            // Said here because the toggle otherwise promises more than Android
            // lets it deliver: the boot broadcast is held back until first
            // unlock, and until then nothing of ours can run.
            Text(
                "boot start waits for the first unlock — a device dedicated to CI " +
                    "should have no secure lock screen",
                color = BtopColors.Dim,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.padding(top = 6.dp))
            if (batteryExempt) {
                Text(
                    "battery optimization: exempt — safe for long-running operation",
                    color = BtopColors.Green,
                    style = MaterialTheme.typography.labelMedium,
                )
            } else {
                Text(
                    "battery optimization is active — Android may throttle the runner " +
                        "while the device is idle and unplugged",
                    color = BtopColors.Yellow,
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.padding(top = 8.dp))
                Button(
                    onClick = {
                        context.startActivity(
                            Intent(
                                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BtopColors.Yellow, contentColor = BtopColors.Background),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Disable battery optimization") }
                Spacer(Modifier.padding(top = 4.dp))
                Text(
                    "Xiaomi/HyperOS: also set Battery saver to \"No restrictions\" and enable Autostart.",
                    color = BtopColors.Dim,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        val selectedTarget: RunnerTarget? = when {
            organizationScope -> selectedOrg
            selectedRepo != null -> RunnerTarget.Repository(selectedRepo!!.owner, selectedRepo!!.name)
            else -> null
        }
        if (userToken != null && selectedTarget != null) {
            val storedTarget = remember(configured, status, busy) {
                RunnerRegistration.load(runtime.runtimeDir)?.target
            }
            val alreadyRegistered = storedTarget == selectedTarget
            // Re-registering swaps the runner's identity, so the listener has
            // to be down first — the same reason the runtime update waits.
            val runnerStopped = runner.state == RunnerState.STOPPED
            Button(
                enabled = !busy && !alreadyRegistered &&
                    (runtime.installed || manifestSource() != null) &&
                    (storedTarget == null || runnerStopped),
                colors = ButtonDefaults.buttonColors(containerColor = BtopColors.Green, contentColor = BtopColors.Background),
                onClick = { registerRunner(selectedTarget, userToken!!) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        alreadyRegistered -> "Registered: ${selectedTarget.displayName}"
                        storedTarget != null -> "Re-register as ${selectedTarget.displayName}"
                        else -> "Register ${selectedTarget.displayName}"
                    },
                )
            }
            if (!alreadyRegistered && storedTarget != null && !runnerStopped) {
                Text(
                    "Stop the runner first — re-registering replaces its identity " +
                        "(currently ${storedTarget.displayName}).",
                    color = BtopColors.Dim,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().clickable { advanced = !advanced },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (advanced) "▼" else "▶", color = BtopColors.Dim, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(8.dp))
            Text("advanced: manual PAT setup", color = BtopColors.Dim, style = MaterialTheme.typography.labelMedium)
        }
        if (advanced) {
            SetupField(
                manifestUrl,
                {
                    manifestUrl = it
                    prefs.edit().putString("manifest", it).apply()
                },
                "Runtime manifest URL (override)",
            )
            SetupField(owner, { owner = it }, "GitHub owner")
            SetupField(repo, { repo = it }, "Repository")
            SetupField(pat, { pat = it }, "Fine-grained PAT")
            Button(
                enabled = !busy && owner.isNotBlank() && repo.isNotBlank() && pat.isNotBlank() &&
                    (runtime.installed || manifestSource() != null),
                colors = ButtonDefaults.buttonColors(containerColor = BtopColors.Green, contentColor = BtopColors.Background),
                onClick = {
                    prefs.edit().putString("owner", owner).putString("repo", repo).apply()
                    secretStore.putPat(pat)
                    registerRunner(RunnerTarget.Repository(owner, repo), pat)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Register with PAT") }
        }

        AboutPanel(capabilities, runtime)

        Text(
            "⚠ Security note: jobs run arbitrary workflow code on this device. " +
                "Never let untrusted fork PRs run on this runner.",
            color = BtopColors.Yellow,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, BtopColors.Yellow.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                .padding(8.dp),
        )
        Spacer(Modifier.padding(bottom = 8.dp))
    }
}

@Composable
private fun DeviceCodePrompt(
    auth: DeviceAuthorization,
    onOpenBrowser: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Enter this code on GitHub (copied to clipboard):",
            color = BtopColors.Dim,
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            auth.userCode,
            color = BtopColors.Cyan,
            fontSize = 28.sp,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier
                .fillMaxWidth()
                .background(BtopColors.Background, RoundedCornerShape(6.dp))
                .padding(vertical = 10.dp),
        )
        Button(
            onClick = onOpenBrowser,
            colors = ButtonDefaults.buttonColors(containerColor = BtopColors.Cyan, contentColor = BtopColors.Background),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Open ${auth.verificationUri.removePrefix("https://")}") }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("waiting for authorization…", color = BtopColors.Yellow, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun RepositoryPicker(
    repos: List<RepositoryRef>,
    reposLoaded: Boolean,
    loading: Boolean,
    selected: RepositoryRef?,
    onSelect: (RepositoryRef) -> Unit,
    onInstallApp: () -> Unit,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("GitHub connected", color = BtopColors.Green, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Text(
                if (loading) "loading…" else "refresh",
                color = BtopColors.Cyan,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.clickable(enabled = !loading) { onRefresh() }.padding(4.dp),
            )
            Text(
                "disconnect",
                color = BtopColors.Dim,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.clickable { onDisconnect() }.padding(4.dp),
            )
        }
        when {
            !reposLoaded && loading -> Text(
                "loading repositories…",
                color = BtopColors.Dim,
                style = MaterialTheme.typography.labelMedium,
            )

            reposLoaded && repos.isEmpty() -> {
                Text(
                    "The DroidRunner GitHub App is not installed on any repository you can manage.",
                    color = BtopColors.Yellow,
                    style = MaterialTheme.typography.labelMedium,
                )
                Button(
                    onClick = onInstallApp,
                    colors = ButtonDefaults.buttonColors(containerColor = BtopColors.Yellow, contentColor = BtopColors.Background),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Install GitHub App") }
                Text(
                    "Install it on the repository this runner should serve, then refresh.",
                    color = BtopColors.Dim,
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            else -> Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .border(1.dp, BtopColors.Border, RoundedCornerShape(6.dp))
                    .verticalScroll(rememberScrollState()),
            ) {
                repos.forEach { candidate ->
                    val isSelected = candidate == selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(candidate) }
                            .background(if (isSelected) BtopColors.Border.copy(alpha = 0.4f) else BtopColors.Panel)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (isSelected) "●" else "○",
                            color = if (isSelected) BtopColors.Green else BtopColors.Dim,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            candidate.fullName,
                            color = if (isSelected) BtopColors.Text else BtopColors.Dim,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SetupField(value: String, onChange: (String) -> Unit, label: String) {
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

/** Checkbox-style row, matching the terminal aesthetic. */
@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (checked) "[✓]" else "[ ]",
            color = if (checked) BtopColors.Green else BtopColors.Dim,
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(Modifier.width(8.dp))
        Text(label, color = BtopColors.Text, style = MaterialTheme.typography.labelMedium)
    }
}

/** Inline option row: tapping a value selects it. */
@Composable
private fun <T> Choice(
    label: String,
    options: List<T>,
    selected: T,
    render: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            color = BtopColors.Dim,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(96.dp),
        )
        options.forEach { option ->
            val isSelected = option == selected
            Text(
                render(option),
                color = if (isSelected) BtopColors.Background else BtopColors.Dim,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .clickable { onSelect(option) }
                    .background(
                        if (isSelected) BtopColors.Cyan else BtopColors.Panel,
                        RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
    }
}

/**
 * Chooses what the runner registers against. Repository scope is the default
 * because an organization runner accepts jobs from every repository in the
 * org unless it is placed in a runner group with an allow-list.
 */
@Composable
private fun ScopePicker(
    organizationScope: Boolean,
    onScopeChange: (Boolean) -> Unit,
    organizations: List<RunnerTarget.Organization>,
    selectedOrg: RunnerTarget.Organization?,
    onSelectOrg: (RunnerTarget.Organization) -> Unit,
    repositoryPicker: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "scope",
                color = BtopColors.Dim,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.width(60.dp),
            )
            listOf(false to "repository", true to "organization").forEach { (isOrg, label) ->
                val selected = organizationScope == isOrg
                Text(
                    label,
                    color = if (selected) BtopColors.Background else BtopColors.Dim,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .clickable { onScopeChange(isOrg) }
                        .background(
                            if (selected) BtopColors.Cyan else BtopColors.Panel,
                            RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
        }

        if (!organizationScope) {
            repositoryPicker()
            return@Column
        }

        if (organizations.isEmpty()) {
            Text(
                "The app is not installed on any organization you can manage. " +
                    "Install it on the organization, then refresh.",
                color = BtopColors.Yellow,
                style = MaterialTheme.typography.labelMedium,
            )
            return@Column
        }

        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp)
                .border(1.dp, BtopColors.Border, RoundedCornerShape(6.dp))
                .verticalScroll(rememberScrollState()),
        ) {
            organizations.forEach { org ->
                val isSelected = org == selectedOrg
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelectOrg(org) }
                        .background(if (isSelected) BtopColors.Border.copy(alpha = 0.4f) else BtopColors.Panel)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (isSelected) "●" else "○",
                        color = if (isSelected) BtopColors.Green else BtopColors.Dim,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        org.org,
                        color = if (isSelected) BtopColors.Text else BtopColors.Dim,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        Text(
            "⚠ An organization runner accepts jobs from every repository in the org. " +
                "Restrict it with a runner group unless you trust them all.",
            color = BtopColors.Yellow,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, BtopColors.Yellow.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                .padding(8.dp),
        )
    }
}
