package io.github.m96chan.droidrunner.runner

import android.content.Context
import io.github.m96chan.droidrunner.BuildConfig
import io.github.m96chan.droidrunner.github.GitHubApi
import io.github.m96chan.droidrunner.github.GitHubApiException
import io.github.m96chan.droidrunner.github.UserSession
import io.github.m96chan.droidrunner.model.RunnerConfig
import io.github.m96chan.droidrunner.model.RegistrationCredentialSource
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
            val json = JSONObject(file.readText().trimStart('\uFEFF'))
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
                .put("credentialSource", config.credentialSource.name)
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
                credentialSource = RegistrationCredentialSource.valueOf(
                    json.optString("credentialSource", RegistrationCredentialSource.AUTO.name),
                ),
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
        val source: RegistrationCredentialSource,
    )

    /**
     * Which credential to register with, given what the caller asked for and
     * what is stored (issue #194).
     *
     * [supplied] wins outright. The advanced panel exists precisely to reach a
     * repository the signed-in user's token cannot, and this used to be
     * re-derived here as `userToken ?: pat` — so the PAT somebody typed was
     * only ever sent when no sign-in existed. Automatic registrations use the
     * persisted source; only legacy details retain the sign-in-then-PAT order.
     */
    fun credentialFor(
        supplied: String?,
        userToken: String?,
        pat: String?,
        source: RegistrationCredentialSource = RegistrationCredentialSource.AUTO,
    ): RegistrationCredential? {
        supplied?.takeIf { it.isNotBlank() }?.let {
            return RegistrationCredential(it, renewable = false, source = RegistrationCredentialSource.PAT)
        }
        val signIn = userToken?.takeIf { it.isNotBlank() }
        val storedPat = pat?.takeIf { it.isNotBlank() }
        val selected = when (source) {
            RegistrationCredentialSource.AUTO -> if (signIn != null) RegistrationCredentialSource.SIGN_IN
                else RegistrationCredentialSource.PAT
            else -> source
        }
        val token = when (selected) {
            RegistrationCredentialSource.SIGN_IN -> signIn
            else -> storedPat
        } ?: return null
        return RegistrationCredential(token, selected == RegistrationCredentialSource.SIGN_IN, selected)
    }

    /** A PAT must work even when an unrelated App sign-in cannot be renewed. */
    fun resolveCredential(
        supplied: String?,
        source: RegistrationCredentialSource,
        userToken: () -> String?,
        pat: String?,
    ): RegistrationCredential? = credentialFor(
        supplied,
        if (supplied.isNullOrBlank() && source != RegistrationCredentialSource.PAT) userToken() else null,
        pat,
        source,
    )

    /**
     * Whether a refusal of the removal token is worth asking again with the
     * other credential in hand (issue #242).
     *
     * 401, 403 and 404 are the three ways GitHub says "not you": the
     * credential was not accepted at all, it was accepted and does not hold
     * the permission, or the target is invisible to it — a private repository
     * a token cannot see is answered 404 rather than 403, so "no permission"
     * and "no such repository" arrive as the same reply and cannot be told
     * apart from here. A repository that really is gone costs one extra
     * request and then the same message, which is cheaper than leaving an
     * entry behind because the reply was ambiguous.
     *
     * Nothing else is worth a second credential. A 5xx, or a request that
     * never reached GitHub at all, answers the same whoever asks — retrying
     * only delays the move the user actually asked for, and hides a fault
     * that has nothing to do with authorisation behind a message about
     * credentials.
     */
    fun refusedForAuthorisation(status: Int): Boolean =
        status == 401 || status == 403 || status == 404

    /**
     * Asks [request] for the old target's removal token, with the fallback
     * that leaving a repository is a different question from joining one
     * (issue #242).
     *
     * [credential] is what the registration went out with. A PAT typed into
     * the advanced panel is scoped to the repository being *joined*, and the
     * one being left is somewhere else entirely — so when GitHub refuses it
     * for want of authorisation, the stored sign-in gets the second and last
     * try. That is the credential this path used before #194, and the one
     * that usually does reach the repository the device is already in.
     *
     * At most two attempts, and only ever with two distinct credentials: a
     * sign-in that is already the one in hand has nothing new to say. Any
     * refusal that survives both is thrown, so the caller can report which
     * repository still holds an entry.
     */
    fun removalToken(
        credential: String,
        signIn: String?,
        request: (String) -> String,
    ): String = removalToken(credential, { signIn }, request)

    /** Acquire/renew the fallback only after the primary credential is refused. */
    fun removalToken(
        credential: String,
        signIn: () -> String?,
        request: (String) -> String,
    ): String = try {
        request(credential)
    } catch (refused: GitHubApiException) {
        if (!refusedForAuthorisation(refused.status)) throw refused
        val fallback = signIn()
            ?.takeIf { it.isNotBlank() && it != credential }
            ?: throw refused
        request(fallback)
    }

    /**
     * Exchanges a GitHub credential for a registration token and runs
     * `config.sh`. [ephemeral] makes the runner serve one job and deregister.
     *
     * [credential] is the one the caller wants used, whatever else is stored;
     * passing nothing uses the source stored with [config] (issue #249).
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
        val chosen = resolveCredential(credential, config.credentialSource, session::accessToken, store.getPat())
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
        val previousSource = load(runtimeDir)?.credentialSource
        val fallback = {
            if (previousSource == RegistrationCredentialSource.PAT) store.getPat()
            else session.accessToken()
        }
        detachFromPrevious(context, runtimeDir, config, inHand, fallback, api, onLine)
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
        if (chosen.source == RegistrationCredentialSource.PAT) store.putPat(chosen.token)
        save(runtimeDir, config.copy(credentialSource = chosen.source))
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
     * [credential] is the one the registration itself used: the old repository
     * is being left on the authority of whoever asked for the move (issue
     * #194). [fallbackCredential] lazily retrieves the previous target's stored
     * source when that one is refused. Legacy details fall back to sign-in
     * (issue #242).
     */
    private fun detachFromPrevious(
        context: Context,
        runtimeDir: File,
        config: RunnerConfig,
        credential: String,
        fallbackCredential: () -> String?,
        api: GitHubApi,
        onLine: (String) -> Unit,
    ) {
        val previous = targetToDetachFrom(load(runtimeDir), config) ?: return
        onLine("leaving ${previous.displayName}")
        runCatching {
            val token = removalToken(credential, fallbackCredential) { api.createRemovalToken(previous, it) }
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
