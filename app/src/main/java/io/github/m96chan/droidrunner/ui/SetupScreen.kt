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
import io.github.m96chan.droidrunner.github.GitHubApiException
import io.github.m96chan.droidrunner.github.GitHubAuth
import io.github.m96chan.droidrunner.github.RepositoryRef
import io.github.m96chan.droidrunner.github.SignInExpiredException
import io.github.m96chan.droidrunner.github.TokenRefreshPolicy
import io.github.m96chan.droidrunner.github.UserSession
import io.github.m96chan.droidrunner.github.storedDeviceAuthorization
import io.github.m96chan.droidrunner.github.toStoredJson
import io.github.m96chan.droidrunner.model.RunnerConfig
import io.github.m96chan.droidrunner.model.RunnerTarget
import io.github.m96chan.droidrunner.runner.AdmissionThresholds
import io.github.m96chan.droidrunner.runner.RunnerRegistration
import io.github.m96chan.droidrunner.runner.ThermalStatus
import io.github.m96chan.droidrunner.npu.NpuLabels
import io.github.m96chan.droidrunner.npu.QnnClient
import io.github.m96chan.droidrunner.npu.QnnConsent
import io.github.m96chan.droidrunner.npu.QnnInstaller
import io.github.m96chan.droidrunner.npu.QnnLicences
import io.github.m96chan.droidrunner.npu.QnnVerificationStore
import io.github.m96chan.droidrunner.npu.verdictFrom
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

/** Full-screen setup flow: GitHub login, repository pick, runtime, register. */
@Composable
fun SetupScreen(
    capabilities: DeviceCapabilities,
    runtime: RuntimeInstaller,
    secretStore: SecretStore,
    onClose: () -> Unit,
    /** Brings the listener up once a repair leaves nothing else to decide. */
    onStartRunner: () -> Unit = {},
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

    // Qualcomm's NPU runtime is fetched, not shipped, and its terms bind the
    // person running the device — so consent is stored separately from the
    // install and outlives it (issue #82).
    val qnn = remember { QnnInstaller(context) }
    val qnnConsent = remember { QnnConsent(context) }
    var qnnInstalled by remember { mutableStateOf(qnn.installed) }
    var qnnAccepted by remember { mutableStateOf(qnnConsent.granted) }
    var showLicences by remember { mutableStateOf(false) }
    var licenceBusy by remember { mutableStateOf(false) }
    var licenceFailure by remember { mutableStateOf<String?>(null) }
    // What the isolated process said last time it was asked (issue #82).
    var qnnProbe by remember {
        mutableStateOf(QnnVerificationStore(context).read()?.detail)
    }
    // Long-running setup runs behind a modal, so nobody wanders off mid-flight.
    var progress by remember { mutableStateOf<SetupProgress?>(null) }
    var setupJob by remember { mutableStateOf<Job?>(null) }
    val configured = remember(runner.state, status, busy) {
        RunnerRegistration.isConfigured(runtime.runtimeDir)
    }

    // Latest runtime-* release of the configured runtime repo; lets Register
    // install the runtime automatically with no manifest URL to paste.
    var manifest by remember { mutableStateOf(ManifestResolution.initial(BuildConfig.RUNTIME_REPO)) }
    // A dropped connection deserves another try, and re-entering the screen
    // was the only way to ask for one — so the effect is keyed on a counter a
    // button can bump (issue #202).
    var manifestAttempt by remember { mutableStateOf(0) }
    LaunchedEffect(manifestAttempt) {
        if (BuildConfig.RUNTIME_REPO.isBlank()) return@LaunchedEffect
        manifest = ManifestResolution.Resolving
        manifest = withContext(Dispatchers.IO) {
            resolveRuntimeManifest(api, BuildConfig.RUNTIME_REPO, secretStore.getUserToken())
        }
    }
    val resolved = manifest as? ManifestResolution.Resolved
    val resolvedManifest = resolved?.url

    val statusText = status ?: when {
        runner.state == RunnerState.PAUSED ->
            "held" + (runner.pausedReason?.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty())
        runner.state != RunnerState.STOPPED -> "runner active"
        // A registered device with no runtime is broken, not ready: say so
        // here rather than promising a runner that cannot start (issue #46).
        configured && !runtime.installed -> "runtime missing — install it to bring this runner back"
        configured -> "registered — runner starts automatically"
        runtime.installed -> "runtime installed — pick a repository"
        resolvedManifest != null -> "pick a repository — runtime installs on register"
        else -> "runtime not installed"
    }

    var userToken by remember { mutableStateOf(secretStore.getUserToken()) }
    val session = remember { UserSession(secretStore, clientId) }
    var deviceAuth by remember { mutableStateOf<DeviceAuthorization?>(null) }
    var authJob by remember { mutableStateOf<Job?>(null) }
    var repos by remember { mutableStateOf<List<RepositoryRef>>(emptyList()) }
    var reposLoaded by remember { mutableStateOf(false) }
    var loadingRepos by remember { mutableStateOf(false) }
    var selectedRepo by remember { mutableStateOf<RepositoryRef?>(null) }
    var confirming by remember { mutableStateOf<RegistrationWarning?>(null) }
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
        // Renewal can hand back a different access token part-way through, and
        // the rest of the screen should go on with whichever one worked.
        var token = userToken ?: return
        loadingRepos = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    // Renew before the sign-in lapses rather than after it
                    // fails, and again on a 401, since the expiry is advisory.
                    token = session.accessToken() ?: token
                    val installations = try {
                        api.listInstallations(token)
                    } catch (rejected: GitHubApiException) {
                        if (rejected.status != 401) throw rejected
                        token = session.renew()
                        api.listInstallations(token)
                    }
                    // Remember the app slug so the install button can deep-link
                    // without a build-time GITHUB_APP_SLUG.
                    installations.firstOrNull { it.appSlug.isNotBlank() }?.let {
                        prefs.edit().putString("app_slug", it.appSlug).apply()
                    }
                    installations.flatMap { api.listInstallationRepositories(token, it.id) }
                }
            }.onSuccess { found ->
                userToken = token
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
                // Only a refused renewal, or a rejection there was nothing to
                // renew with, really ends the sign-in; a token that merely
                // lapsed has already been replaced above.
                if (failure is SignInExpiredException ||
                    (failure as? GitHubApiException)?.status == 401
                ) {
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
                // The refresh token and the expiry are stored with the access
                // token: without them this sign-in could never be renewed.
                secretStore.putUserToken(
                    token.accessToken,
                    token.refreshToken,
                    TokenRefreshPolicy.expiresAtMillis(
                        token.expiresInSeconds,
                        System.currentTimeMillis(),
                    ),
                )
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

    /**
     * Installs the runtime and nothing else.
     *
     * Reinstalling a runtime and registering a runner are different
     * operations — only one of them touches GitHub — so a device that lost its
     * runtime does not have to re-register to get one back (issue #46). The
     * registration details survive the install, so a device that was already a
     * runner is one again as soon as the listener comes up, which it does here
     * rather than sending the user to the dashboard to press Start.
     */
    fun installRuntime() {
        val manifest = manifestSource() ?: return
        busy = true
        progress = SetupProgress("preparing")
        setupJob = scope.launch {
            status = runCatching {
                // runInterruptible so Cancel actually breaks the blocking
                // download and extraction, rather than leaving them running.
                runInterruptible(Dispatchers.IO) {
                    runtime.install(manifest) { phase, fraction ->
                        progress = SetupProgress(phase, fraction)
                    }
                }
                null
            }.getOrElse { failure ->
                if (failure is kotlinx.coroutines.CancellationException) "install cancelled"
                else "failed: ${failure.message}"
            }
            progress = null
            busy = false
            // Read both back from disk rather than trusting what the screen
            // believed before the install: the install is what decides whether
            // the registration made it across.
            val startRunner = RuntimeRecovery.shouldStartRunnerAfterSetup(
                registered = RunnerRegistration.isConfigured(runtime.runtimeDir),
                runnerState = RunnerStatus.snapshot.value.state,
            )
            if (status == null && startRunner) onStartRunner()
        }
    }

    /**
     * Fetches the licence text if it is not already here and hands it to a
     * reader. This is the only Qualcomm download that happens before consent,
     * and it is what makes consent mean anything.
     */
    fun openLicence(licence: QnnLicences.Licence) {
        licenceBusy = true
        licenceFailure = null
        scope.launch {
            licenceFailure = runCatching {
                val file = withContext(Dispatchers.IO) {
                    qnn.fetchLicences().first { it.name == licence.fileName }
                }
                context.startActivity(LicenceViewer.intentFor(context, file))
                null
            }.getOrElse { failure ->
                if (failure is android.content.ActivityNotFoundException) {
                    "no PDF reader on this device — install one to read the terms"
                } else {
                    "could not open the licence: ${failure.message}"
                }
            }
            licenceBusy = false
        }
    }

    /**
     * Runs a model on the Hexagon and records what happened.
     *
     * Installing proves the files are on disk; every failure on the way to
     * making this work had the files on disk. Only a model that demonstrably
     * executed on the accelerator earns the label, so that is what this does —
     * and it is one button press, because a check nobody runs is a check that
     * does not exist.
     */
    fun checkQnn(htpVersion: Int) {
        qnnProbe = "checking the NPU…"
        scope.launch {
            val verdict = withContext(Dispatchers.IO) {
                runCatching {
                    val client = QnnClient(context)
                    val loaded = client.probe(qnn.installDir, htpVersion)
                    if (!loaded.htpUsable) return@runCatching QnnVerdictOf.stopped(loaded.summary())
                    val model = qnn.verificationModel { phase, fraction ->
                        progress = SetupProgress(phase, fraction)
                    }
                    QnnVerdictOf(
                        client.runModel(qnn.installDir, htpVersion, model, "htp", 20),
                    )
                }.getOrElse { QnnVerdictOf.failed(it.message.orEmpty()) }
            }
            progress = null
            QnnVerificationStore(context).write(verdict.value)
            qnnProbe = verdict.value.detail
        }
    }

    fun installQnn(htpVersion: Int) {
        busy = true
        progress = SetupProgress("preparing")
        setupJob = scope.launch {
            status = runCatching {
                runInterruptible(Dispatchers.IO) {
                    qnn.install(htpVersion, qnnConsent.record) { phase, fraction ->
                        progress = SetupProgress(phase, fraction)
                    }
                }
                null
            }.getOrElse { failure ->
                if (failure is kotlinx.coroutines.CancellationException) "install cancelled"
                else "failed: ${failure.message}"
            }
            qnnInstalled = qnn.installed
            progress = null
            busy = false
            // The install is only half the answer; ask the device the rest.
            if (status == null) checkQnn(htpVersion)
        }
    }

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
            capabilities.labels() + NpuLabels.refresh(context) +
                QnnVerificationStore(context).labels(),
        )
        progress = SetupProgress("preparing")
        setupJob = scope.launch {
            status = runCatching {
                config.validate()?.let { error(it) }
                if (!runtime.installed) {
                    val source = manifestSource()
                        // Says which of the three it was, so a phone that
                        // could not reach GitHub is not told its build is
                        // misconfigured (issue #202).
                        ?: error(runtimeUnavailableMessage(manifest))
                    // runInterruptible so Cancel actually breaks the blocking
                    // download and extraction, rather than leaving them running.
                    runInterruptible(Dispatchers.IO) {
                        runtime.install(source) { phase, fraction ->
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
                        // The credential this screen was pressed with, not
                        // whichever one happens to be stored: the advanced
                        // panel's PAT used to lose to a live sign-in (#194).
                        credential = credential,
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
            // Registering is the other way a device becomes a runner, and it
            // used to end here without starting one. Switching repositories
            // then looked like it had failed: the runner went away and did not
            // come back, while everything the screen could show said it had
            // worked (#150).
            val startRunner = RuntimeRecovery.shouldStartRunnerAfterSetup(
                registered = RunnerRegistration.isConfigured(runtime.runtimeDir),
                runnerState = RunnerStatus.snapshot.value.state,
            )
            if (status == null && startRunner) onStartRunner()
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

    confirming?.let { warning ->
        val target = if (organizationScope) {
            selectedOrg
        } else {
            selectedRepo?.let { RunnerTarget.Repository(it.owner, it.name) }
        }
        RegistrationWarningDialog(
            warning = warning,
            onConfirm = {
                confirming = null
                val credential = userToken
                if (target != null && credential != null) registerRunner(target, credential)
            },
            onCancel = { confirming = null },
        )
    }

    if (showLicences) {
        QnnLicenceDialog(
            onOpen = ::openLicence,
            onAccept = {
                qnnConsent.accept()
                qnnAccepted = qnnConsent.granted
                showLicences = false
                licenceFailure = null
            },
            onCancel = {
                showLicences = false
                licenceFailure = null
            },
            fetching = licenceBusy,
            failure = licenceFailure,
        )
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
            resolved?.fallbackNotice?.let { notice ->
                Text(
                    notice,
                    color = BtopColors.Yellow,
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.padding(top = 6.dp))
            }
            when {
                runtime.installed -> {
                    val installedVersion = runtime.installedVersion
                    val latestRuntimeVersion = resolved?.version
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
                            enabled = RuntimeRecovery.canInstallNow(busy, runner.state),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BtopColors.Yellow,
                                contentColor = BtopColors.Background,
                            ),
                            onClick = { installRuntime() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Update runtime") }
                        blockedUntilStopped(
                            runner.state, runner.pausedReason, "updating the runtime",
                        )?.let {
                            Text(
                                it,
                                color = BtopColors.Dim,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }

                // Ahead of the "checking…" line, because a manifest URL set
                // under advanced is an answer already and should not wait on a
                // round-trip it does not depend on (issue #202).
                RuntimeRecovery.shouldOfferInstall(runtime.installed, manifestSource() != null) -> {
                    Text(
                        if (configured) {
                            "no runtime installed — this device is registered but cannot run " +
                                "anything until one is back (~200MB)"
                        } else {
                            "latest release found — downloads automatically when you register (~200MB)"
                        },
                        color = if (configured) BtopColors.Yellow else BtopColors.Dim,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.padding(top = 6.dp))
                    // Offered whatever the registration says: a device that
                    // already registered is exactly the one that could not get
                    // a runtime back any other way (issue #46).
                    Button(
                        enabled = RuntimeRecovery.canInstallNow(busy, runner.state),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BtopColors.Cyan,
                            contentColor = BtopColors.Background,
                        ),
                        onClick = { installRuntime() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(RuntimeRecovery.installLabel(configured)) }
                    blockedUntilStopped(
                        runner.state, runner.pausedReason, "installing a runtime",
                    )?.let {
                        Text(
                            it,
                            color = BtopColors.Dim,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }

                manifest is ManifestResolution.Resolving -> Text(
                    "checking runtime releases…",
                    color = BtopColors.Dim,
                    style = MaterialTheme.typography.labelMedium,
                )

                else -> {
                    Text(
                        runtimeUnavailableMessage(manifest),
                        color = BtopColors.Yellow,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    // Only the unreachable case has anything to try again. The
                    // other two are answers, and a Retry under them would just
                    // invite the user to keep pressing it (issue #202).
                    if (manifest is ManifestResolution.Unreachable) {
                        Spacer(Modifier.padding(top = 6.dp))
                        OutlinedButton(
                            onClick = { manifestAttempt++ },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Retry") }
                    }
                }
            }
        }

        // Every other device already reaches what it has through NNAPI, so a
        // panel about Qualcomm's terms would be a question nobody should answer.
        val npu = npuAcceleration(capabilities.soc, qnnAccepted, qnnInstalled)
        if (npu !is NpuAcceleration.Irrelevant) {
            Panel("npu runtime", titleColor = BtopColors.Cyan) {
                when (npu) {
                    is NpuAcceleration.Unsupported -> Text(
                        npu.reason,
                        color = BtopColors.Yellow,
                        style = MaterialTheme.typography.labelMedium,
                    )

                    is NpuAcceleration.Installed -> {
                        Text(
                            "installed: ${npu.stamp}",
                            color = BtopColors.Green,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        qnnProbe?.let {
                            Spacer(Modifier.padding(top = 6.dp))
                            Text(
                                it,
                                color = if (it.startsWith("verified:")) {
                                    BtopColors.Green
                                } else {
                                    BtopColors.Yellow
                                },
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Spacer(Modifier.padding(top = 6.dp))
                        OutlinedButton(
                            onClick = { checkQnn(npu.htpVersion) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Check the NPU") }
                    }

                    is NpuAcceleration.NeedsAcceptance -> {
                        Text(
                            "Hexagon v${npu.htpVersion} — Qualcomm's runtime can drive this " +
                                "NPU, but its terms are yours to accept",
                            color = BtopColors.Text,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Spacer(Modifier.padding(top = 6.dp))
                        Text(
                            downloadSummary(npu.downloadBytes, npu.installBytes),
                            color = BtopColors.Dim,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Spacer(Modifier.padding(top = 6.dp))
                        Button(
                            enabled = !busy,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BtopColors.Yellow,
                                contentColor = BtopColors.Background,
                            ),
                            onClick = { showLicences = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Review Qualcomm's terms") }
                    }

                    is NpuAcceleration.Installable -> {
                        Text(
                            "terms accepted — Hexagon v${npu.htpVersion} runtime not installed",
                            color = BtopColors.Text,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Spacer(Modifier.padding(top = 6.dp))
                        Text(
                            downloadSummary(npu.downloadBytes, npu.installBytes),
                            color = BtopColors.Dim,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Spacer(Modifier.padding(top = 6.dp))
                        Button(
                            enabled = !busy,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BtopColors.Cyan,
                                contentColor = BtopColors.Background,
                            ),
                            onClick = { installQnn(npu.htpVersion) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Install NPU runtime") }
                    }

                    NpuAcceleration.Irrelevant -> Unit
                }
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

            Toggle("run only on mains power", thresholds.requireMains) {
                update(thresholds.copy(requireMains = it))
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
        // Read once for both register buttons: the advanced one registers
        // through exactly the same code and needs the same guard (#194).
        val storedTarget = remember(configured, status, busy) {
            RunnerRegistration.load(runtime.runtimeDir)?.target
        }
        // Re-registering swaps the runner's identity, so the listener has
        // to be down first — the same reason the runtime update waits.
        val runnerStopped = runner.state == RunnerState.STOPPED
        if (userToken != null && selectedTarget != null) {
            val alreadyRegistered = storedTarget == selectedTarget
            Button(
                enabled = !busy && !alreadyRegistered &&
                    (runtime.installed || manifestSource() != null) &&
                    (storedTarget == null || runnerStopped),
                colors = ButtonDefaults.buttonColors(containerColor = BtopColors.Green, contentColor = BtopColors.Background),
                onClick = {
                    // The warning is asked here, not on the screen behind: a
                    // banner that is always there stops being read, and this
                    // is the moment the answer still changes anything (#64).
                    val warning = registrationWarning(selectedTarget, selectedRepo?.isPrivate)
                    if (warning == null) registerRunner(selectedTarget, userToken!!) else confirming = warning
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    registerButtonLabel(
                        target = selectedTarget.displayName,
                        alreadyRegistered = alreadyRegistered,
                        firstRegistration = storedTarget == null,
                        runnerStopped = runnerStopped,
                    ),
                )
            }
            if (!alreadyRegistered && storedTarget != null) {
                blockedUntilStopped(runner.state, runner.pausedReason, "re-registering")?.let {
                    Text(
                        "$it Currently ${storedTarget.displayName}.",
                        color = BtopColors.Dim,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
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
                enabled = canRegisterWithPat(
                    busy = busy,
                    owner = owner,
                    repository = repo,
                    pat = pat,
                    runtimeAvailable = runtime.installed || manifestSource() != null,
                    runnerStopped = runnerStopped,
                ),
                colors = ButtonDefaults.buttonColors(containerColor = BtopColors.Green, contentColor = BtopColors.Background),
                onClick = {
                    prefs.edit().putString("owner", owner).putString("repo", repo).apply()
                    secretStore.putPat(pat)
                    registerRunner(RunnerTarget.Repository(owner, repo), pat)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (runnerStopped) "Register with PAT" else "Stop the runner to register with a PAT",
                )
            }
            blockedUntilStopped(runner.state, runner.pausedReason, "re-registering")?.let {
                Text(
                    it + storedTarget?.let { target -> " Currently ${target.displayName}." }.orEmpty(),
                    color = BtopColors.Dim,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
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

/**
 * What asking GitHub for this build's newest runtime release came back with
 * (issue #202).
 *
 * It used to come back as one nullable URL, and null had to stand for four
 * different situations at once — still asking, no such release, no runtime
 * repository in this build, and a phone that could not reach GitHub. The
 * screen picked the third reading and told a user on flaky Wi-Fi to go and set
 * a manifest URL, which would not have helped them.
 */
internal sealed interface ManifestResolution {
    /** Still asking. The runtime panel waits rather than guessing. */
    data object Resolving : ManifestResolution

    /** GitHub answered, and named a manifest. */
    data class Resolved(
        val url: String,
        /** Set when the newest runtime tag is not the one being used. */
        val fallbackNotice: String?,
        /** Bundle version from the manifest, so an old install can be spotted. */
        val version: String?,
    ) : ManifestResolution

    /** GitHub answered, and this repository publishes no runtime release. */
    data object NoRelease : ManifestResolution

    /** This build names no runtime repository, so there is nothing to ask. */
    data object NotConfigured : ManifestResolution

    /**
     * The request did not get an answer. [reason] is whatever the failure
     * said, which is usually a timeout or a DNS name — worth showing, because
     * it is the difference between "GitHub is down" and "this Wi-Fi is not".
     */
    data class Unreachable(val reason: String?) : ManifestResolution

    companion object {
        fun initial(runtimeRepo: String): ManifestResolution =
            if (runtimeRepo.isBlank()) NotConfigured else Resolving
    }
}

/**
 * Asks the runtime repository for its newest runtime-* release.
 *
 * A failure here is a failure, not an absence: it is reported as one so the
 * screen can offer a retry instead of an explanation that does not apply
 * (issue #202).
 */
internal fun resolveRuntimeManifest(
    api: GitHubApi,
    runtimeRepo: String,
    token: String?,
): ManifestResolution = runCatching { api.latestRuntimeManifest(runtimeRepo, token) }.fold(
    onSuccess = { release ->
        if (release == null) {
            ManifestResolution.NoRelease
        } else {
            ManifestResolution.Resolved(
                url = release.url,
                fallbackNotice = release.fallbackNotice,
                // The manifest names the bundle version, so an installed
                // runtime that has fallen behind can be reported. Only the
                // "update available" line depends on it, so a failure to read
                // it costs that line and not the install button.
                version = runCatching { manifestVersion(release.url) }.getOrNull(),
            )
        }
    },
    onFailure = { ManifestResolution.Unreachable(it.message) },
)

/**
 * Reads the `version` out of a runtime manifest.
 *
 * The timeouts are the point. This was `URL(url).readText()`, which inherits
 * `HttpURLConnection`'s defaults — no timeout at all — so a captive portal
 * that accepts the connection and never answers left the runtime panel on
 * "checking runtime releases…" forever, and the Install runtime button never
 * appeared on a device that had no runtime (issue #202). Fifteen seconds is
 * what every other request in this app waits.
 */
private fun manifestVersion(url: String): String? {
    val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 15_000
        setRequestProperty("User-Agent", "DroidRunner/0.1")
    }
    val body = try {
        connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        connection.disconnect()
    }
    return org.json.JSONObject(body).optString("version").takeIf { it.isNotBlank() }
}

/**
 * Why there is no runtime to install, in words that name the actual cause.
 *
 * The unreachable case is a bad minute and says so; the other two are the
 * build's configuration, and only those are worth sending someone to the
 * advanced panel over (issue #202).
 */
internal fun runtimeUnavailableMessage(resolution: ManifestResolution): String = when (resolution) {
    is ManifestResolution.Unreachable -> {
        val because = resolution.reason?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        "could not reach GitHub to look for a runtime release$because — " +
            "retry, or set a manifest URL under advanced"
    }

    ManifestResolution.NoRelease ->
        "this runtime repository publishes no runtime release — set a manifest URL under advanced"

    ManifestResolution.NotConfigured ->
        "this build names no runtime repository — set a manifest URL under advanced"

    ManifestResolution.Resolving -> "checking runtime releases…"

    is ManifestResolution.Resolved -> "runtime release found"
}

/**
 * Whether the advanced panel's **Register with PAT** button can be pressed
 * (issue #194).
 *
 * It registers through the same code as the button above it, which means
 * `clearLocalRegistration` and then `config.sh` over the identity files the
 * listener is holding open — and it had no `runnerStopped` term at all, so it
 * walked straight past the guard the other button applies (#154, #150).
 *
 * Stricter than the OAuth button, which lets a first registration through
 * without a stop because nothing can be listening yet. Down here there is no
 * stored target on screen to reason from, and `load()` returns null for a
 * device configured before `runner-config.json` existed — one that may well be
 * running. So "nothing stored" is not evidence that nothing is running, and
 * this waits for the listener either way.
 */
internal fun canRegisterWithPat(
    busy: Boolean,
    owner: String,
    repository: String,
    pat: String,
    runtimeAvailable: Boolean,
    runnerStopped: Boolean,
): Boolean =
    !busy && owner.isNotBlank() && repository.isNotBlank() && pat.isNotBlank() &&
        runtimeAvailable && runnerStopped

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
