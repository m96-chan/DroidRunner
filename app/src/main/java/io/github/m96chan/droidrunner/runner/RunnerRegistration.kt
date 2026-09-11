package io.github.m96chan.droidrunner.runner

import android.content.Context
import io.github.m96chan.droidrunner.BuildConfig
import io.github.m96chan.droidrunner.github.GitHubApi
import io.github.m96chan.droidrunner.github.GitHubApiException
import io.github.m96chan.droidrunner.github.UserSession
import io.github.m96chan.droidrunner.model.RunnerConfig
import io.github.m96chan.droidrunner.model.RunnerTarget
import io.github.m96chan.droidrunner.security.SecretStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists what the device registered as, so the service can register again
 * on its own — which ephemeral runners need after every job, since the
 * listener deregisters itself when it exits (issue #3).
 */
object RunnerRegistration {
    private const val CONFIG_FILE = "runner-config.json"
    private const val LEGACY_MARKER = ".configured"

    /** The official runner writes this once configured, and removes it when ephemeral. */
    fun isRegistered(runtimeDir: File): Boolean =
        File(runtimeDir, "home/runner/.runner").isFile

    /**
     * Whether the current registration is ephemeral. Toggling the setting has
     * to re-register, since an existing persistent registration would
     * otherwise keep serving jobs forever.
     */
    fun registeredAsEphemeral(runtimeDir: File): Boolean {
        val file = File(runtimeDir, "home/runner/.runner")
        if (!file.isFile) return false
        return runCatching {
            // The file is written with a BOM by the .NET runner.
            val json = JSONObject(file.readText().trimStart('﻿'))
            json.optBoolean("ephemeral", false) || json.optBoolean("isEphemeral", false)
        }.getOrDefault(false)
    }

    /** True once this device has been set up, even if a job just consumed the registration. */
    fun isConfigured(runtimeDir: File): Boolean =
        File(runtimeDir, CONFIG_FILE).isFile || File(runtimeDir, LEGACY_MARKER).isFile

    fun save(runtimeDir: File, config: RunnerConfig) {
        File(runtimeDir, CONFIG_FILE).writeText(
            JSONObject()
                .apply {
                    when (val target = config.target) {
                        is RunnerTarget.Repository -> {
                            put("owner", target.owner)
                            put("repository", target.name)
                        }
                        is RunnerTarget.Organization -> put("organization", target.org)
                    }
                }
                .put("runnerName", config.runnerName)
                .put("labels", JSONArray(config.labels.sorted()))
                .toString(),
        )
        // Kept for the dashboard, and for devices configured before this file existed.
        File(runtimeDir, LEGACY_MARKER).writeText(config.repositoryUrl)
    }

    /**
     * Copies the stored registration details from one runtime tree into
     * another, so replacing the runtime does not forget what this device
     * registered as (issue #46).
     *
     * Only the details are carried, never the runner's own identity files:
     * those belong to the runtime being replaced. RunnerService registers
     * again from these details when it finds no identity, which is the path
     * ephemeral runners already take after every job.
     */
    fun copyDetails(from: File, to: File) {
        listOf(CONFIG_FILE, LEGACY_MARKER).forEach { name ->
            val file = File(from, name)
            if (file.isFile) file.copyTo(File(to, name), overwrite = true)
        }
    }

    fun load(runtimeDir: File): RunnerConfig? {
        val file = File(runtimeDir, CONFIG_FILE)
        if (!file.isFile) return null
        return runCatching {
            val json = JSONObject(file.readText())
            val labels = json.optJSONArray("labels") ?: JSONArray()
            val org = json.optString("organization").takeIf { it.isNotBlank() }
            RunnerConfig(
                target = if (org != null) {
                    RunnerTarget.Organization(org)
                } else {
                    RunnerTarget.Repository(json.getString("owner"), json.getString("repository"))
                },
                runnerName = json.getString("runnerName"),
                labels = (0 until labels.length()).map { labels.getString(it) }.toSet(),
            )
        }.getOrNull()
    }

    /**
     * The credential a registration goes out with, and whether a 401 from it
     * is worth one renewal (issue #194).
     */
    data class RegistrationCredential(
        val token: String,
        /**
         * True only when the token in hand is the stored user sign-in. That
         * is the only kind that can be renewed; renewing a hand-entered PAT
         * means nothing, so a 401 from one is the answer.
         */
        val renewable: Boolean,
    )

    /**
     * Which credential to register with, given what the caller asked for and
     * what is stored (issue #194).
     *
     * [supplied] wins outright. The advanced panel exists precisely to reach a
     * repository the signed-in user's token cannot, and this used to be
     * re-derived here as `userToken ?: pat` — so the PAT somebody typed was
     * only ever sent when no sign-in existed, which is the one case that panel
     * was not built for. Callers that pass nothing — the service registering
     * again after an ephemeral job — still fall back in the old order.
     */
    fun credentialFor(
        supplied: String?,
        userToken: String?,
        pat: String?,
    ): RegistrationCredential? {
        val token = supplied?.takeIf { it.isNotBlank() } ?: userToken ?: pat ?: return null
        return RegistrationCredential(token, renewable = userToken != null && token == userToken)
    }

    /**
     * Exchanges a GitHub credential for a registration token and runs
     * `config.sh`. [ephemeral] makes the runner serve one job and deregister.
     *
     * [credential] is the one the caller wants used, whatever else is stored;
     * passing nothing keeps the old sign-in-then-PAT fallback (issue #194).
     */
    fun register(
        context: Context,
        runtimeDir: File,
        config: RunnerConfig,
        ephemeral: Boolean,
        credential: String? = null,
        onLine: (String) -> Unit = {},
    ) {
        val store = SecretStore(context)
        val session = UserSession(store, BuildConfig.GITHUB_APP_CLIENT_ID)
        // A user sign-in renews itself before it lapses (issue #42); a
        // hand-entered PAT cannot, so it is used as it stands.
        val chosen = credentialFor(credential, session.accessToken(), store.getPat())
            ?: error("No GitHub credential stored — reconnect on the setup screen")
        val api = GitHubApi()
        // Whatever is actually in hand after the renewal below, so the removal
        // token is not asked for with a credential GitHub has just refused.
        var inHand = chosen.token
        val token = try {
            api.createRegistrationToken(config.target, inHand)
        } catch (rejected: GitHubApiException) {
            // The expiry is only advisory — clocks drift and tokens get revoked
            // early — so a 401 earns one renewal before it counts as a failure.
            // Only a sign-in has anything to renew (#194).
            if (rejected.status != 401 || !chosen.renewable) throw rejected
            inHand = session.renew()
            api.createRegistrationToken(config.target, inHand)
        }
        // Leave the previous repository first. `config.sh remove` reads the
        // credentials the next line deletes, so once the device is attached
        // elsewhere there is no way left to deregister properly — only an API
        // delete by name, which never tells the old runner anything (#154).
        detachFromPrevious(context, runtimeDir, config, inHand, api, onLine)
        // config.sh refuses to run while a local configuration exists; --replace
        // only settles the server-side duplicate.
        clearLocalRegistration(runtimeDir)
        val process = RunnerCommand.configure(context, runtimeDir, config, token, ephemeral)
            .redirectErrorStream(true)
            .start()
        val output = StringBuilder()
        try {
            process.inputStream.bufferedReader().forEachLine {
                output.appendLine(it)
                onLine(it)
            }
            check(process.waitFor() == 0) { output.toString().takeLast(500) }
        } catch (interrupted: Throwable) {
            // Cancelling has to stop config.sh too, and leave no half-written
            // identity behind for the next attempt to trip over.
            process.destroy()
            runCatching { process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) }
            clearLocalRegistration(runtimeDir)
            throw interrupted
        }
        save(runtimeDir, config)
    }

    /**
     * The target to leave before registering somewhere else, or null when
     * there is nothing to leave (issue #154).
     *
     * Registering to the target already stored is not a move: `--replace`
     * settles that on its own, and removing first would throw away a working
     * registration in order to rebuild an identical one.
     */
    fun targetToDetachFrom(stored: RunnerConfig?, wanted: RunnerConfig): RunnerTarget? =
        stored?.target?.takeIf { it != wanted.target }

    /**
     * Leaves the previous repository, best effort.
     *
     * Never throws. The switch is what was asked for; the tidying is not, so a
     * deleted repository, a revoked token or no network costs the cleanup
     * alone. What it costs is said out loud instead, because an entry nobody
     * knows about is one somebody has to find later — and an offline entry
     * carrying live labels can be handed a job that then never starts.
     *
     * [credential] is the one the registration itself used, never a freshly
     * re-derived one: the old repository is being left on the authority of
     * whoever asked for the move (issue #194).
     */
    private fun detachFromPrevious(
        context: Context,
        runtimeDir: File,
        config: RunnerConfig,
        credential: String,
        api: GitHubApi,
        onLine: (String) -> Unit,
    ) {
        val previous = targetToDetachFrom(load(runtimeDir), config) ?: return
        onLine("leaving ${previous.displayName}")
        runCatching {
            val token = api.createRemovalToken(previous, credential)
            val process = RunnerCommand.remove(context, runtimeDir, token)
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().forEachLine(onLine)
            check(process.waitFor() == 0) { "config.sh remove exited non-zero" }
        }.onFailure {
            onLine(
                "could not remove this runner from ${previous.displayName}" +
                    " (${it.message}) — it still holds an entry there",
            )
        }
    }

    /**
     * Drops the local runner identity so `config.sh` will configure again.
     * Covers the v2 marker (`.runner_migrated`) too — leaving it behind makes
     * config.sh insist the runner is already configured.
     */
    fun clearLocalRegistration(runtimeDir: File) {
        File(runtimeDir, "home/runner").listFiles()
            ?.filter { it.isFile && IDENTITY_FILES.any(it.name::startsWith) }
            ?.forEach { it.delete() }
    }

    private val IDENTITY_FILES = listOf(".runner", ".credentials", ".service")

    /** Removes the previous job's checkout and outputs. */
    fun cleanWorkDirectory(runtimeDir: File) {
        File(runtimeDir, "home/runner/_work").deleteRecursively()
    }

    /** Whether the device should re-register per job. */
    fun ephemeralEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setEphemeralEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }

    private const val PREFS = "runner"
    private const val KEY = "ephemeral"
}
