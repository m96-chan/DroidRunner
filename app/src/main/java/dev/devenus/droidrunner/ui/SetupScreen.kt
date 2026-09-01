package dev.devenus.droidrunner.ui

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
import dev.devenus.droidrunner.BuildConfig
import dev.devenus.droidrunner.device.DeviceCapabilities
import dev.devenus.droidrunner.github.DeviceAuthorization
import dev.devenus.droidrunner.github.GitHubApi
import dev.devenus.droidrunner.github.GitHubAuth
import dev.devenus.droidrunner.github.RepositoryRef
import dev.devenus.droidrunner.github.storedDeviceAuthorization
import dev.devenus.droidrunner.github.toStoredJson
import dev.devenus.droidrunner.model.RunnerConfig
import dev.devenus.droidrunner.runner.RunnerCommand
import dev.devenus.droidrunner.runner.RunnerState
import dev.devenus.droidrunner.runner.RunnerStatus
import dev.devenus.droidrunner.runtime.RuntimeInstaller
import dev.devenus.droidrunner.security.SecretStore
import dev.devenus.droidrunner.ui.theme.BtopColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
    val configured = remember(runner.state, status, busy) { File(runtime.runtimeDir, ".configured").isFile }

    // Latest runtime-* release of the configured runtime repo; lets Register
    // install the runtime automatically with no manifest URL to paste.
    var resolvedManifest by remember { mutableStateOf<String?>(null) }
    var resolvingManifest by remember { mutableStateOf(BuildConfig.RUNTIME_REPO.isNotBlank()) }
    LaunchedEffect(Unit) {
        if (BuildConfig.RUNTIME_REPO.isNotBlank()) {
            resolvedManifest = runCatching {
                withContext(Dispatchers.IO) {
                    api.latestRuntimeManifestUrl(BuildConfig.RUNTIME_REPO, secretStore.getUserToken())
                }
            }.getOrNull()
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

    fun registerRunner(target: RepositoryRef, credential: String) {
        if (File(runtime.runtimeDir, ".configured").isFile) {
            status = null
            return
        }
        busy = true
        val deviceId = android.provider.Settings.Secure.getString(
            context.contentResolver, android.provider.Settings.Secure.ANDROID_ID,
        )?.takeLast(6) ?: "device"
        val config = RunnerConfig(
            target.owner, target.name,
            "android-${android.os.Build.MODEL}-$deviceId",
            capabilities.labels(),
        )
        scope.launch {
            status = runCatching {
                config.validate()?.let { error(it) }
                if (!runtime.installed) {
                    val manifest = manifestSource()
                        ?: error("No runtime release found — set a manifest URL under advanced")
                    withContext(Dispatchers.IO) { runtime.install(manifest) { status = "runtime: $it" } }
                }
                status = "registering ${target.fullName}…"
                withContext(Dispatchers.IO) {
                    val token = api.createRegistrationToken(target.owner, target.name, credential)
                    val process = RunnerCommand.configure(context, runtime.runtimeDir, config, token)
                        .redirectErrorStream(true).start()
                    // Stream config.sh output into the runner panel's log tail
                    // so the slow proot/.NET startup is visible.
                    val output = StringBuilder()
                    process.inputStream.bufferedReader().forEachLine { line ->
                        output.appendLine(line)
                        RunnerStatus.onLogLine(line)
                    }
                    check(process.waitFor() == 0) { output.toString().takeLast(1000) }
                    File(runtime.runtimeDir, ".configured").writeText(config.repositoryUrl)
                }
                null
            }.getOrElse { "failed: ${it.message}" }
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

                else -> RepositoryPicker(
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
                )
            }
        }

        Panel("runtime", titleColor = BtopColors.Cyan) {
            when {
                runtime.installed -> Text(
                    "installed: ${runtime.installedVersion}",
                    color = BtopColors.Green,
                    style = MaterialTheme.typography.labelMedium,
                )

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

        if (userToken != null && selectedRepo != null) {
            Button(
                enabled = !busy && !configured && (runtime.installed || manifestSource() != null),
                colors = ButtonDefaults.buttonColors(containerColor = BtopColors.Green, contentColor = BtopColors.Background),
                onClick = { registerRunner(selectedRepo!!, userToken!!) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (configured) "Registered" else "Register ${selectedRepo!!.fullName}") }
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
                    registerRunner(RepositoryRef(owner, repo), pat)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Register with PAT") }
        }

        Text(
            "Never route untrusted fork PRs to this runner. Jobs can read data on this device.",
            color = BtopColors.Red,
            style = MaterialTheme.typography.labelSmall,
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
