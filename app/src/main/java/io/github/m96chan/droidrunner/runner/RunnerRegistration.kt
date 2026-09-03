package io.github.m96chan.droidrunner.runner

import android.content.Context
import io.github.m96chan.droidrunner.github.GitHubApi
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
     * Exchanges the stored GitHub credential for a registration token and runs
     * `config.sh`. [ephemeral] makes the runner serve one job and deregister.
     */
    fun register(
        context: Context,
        runtimeDir: File,
        config: RunnerConfig,
        ephemeral: Boolean,
        onLine: (String) -> Unit = {},
    ) {
        val store = SecretStore(context)
        val credential = store.getUserToken() ?: store.getPat()
            ?: error("No GitHub credential stored — reconnect on the setup screen")
        val token = GitHubApi().createRegistrationToken(config.target, credential)
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
