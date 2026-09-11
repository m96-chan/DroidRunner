package io.github.m96chan.droidrunner.github

import io.github.m96chan.droidrunner.model.RunnerTarget
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

data class Installation(
    val id: Long,
    val account: String,
    val appSlug: String,
    /** "Organization" or "User". */
    val accountType: String,
)

data class RepositoryRef(
    val owner: String,
    val name: String,
    /**
     * Null when the response did not say. Unknown is not the same as private:
     * a device pointed at a public repository can be handed a fork's pull
     * request, so the two are told apart rather than merged into a default.
     */
    val isPrivate: Boolean? = null,
) {
    val fullName: String get() = "$owner/$name"
}

/**
 * Repositories one installation grants access to, and whether the fetch gave
 * up before GitHub ran out (issue #201).
 *
 * A list that was cut short and does not say so is indistinguishable from an
 * installation the app was never given: the repository the user is looking for
 * is simply absent, and the screen looks like it finished.
 */
internal data class RepositoryList(val repositories: List<RepositoryRef>, val truncated: Boolean)

/**
 * What asking a repository for its newest runtime bundle produced.
 *
 * The three ways this comes back without a manifest used to collapse into one
 * null, and the setup screen read that null as "no runtime release found — set
 * a manifest URL under advanced", blaming the build configuration for a feed
 * that had simply moved on (issue #193). They are told apart here because each
 * sends the reader somewhere different: a build with no runtime repo is for
 * whoever built the APK, a repo with no runtime release is for whoever
 * maintains it, and a refused request is worth trying again.
 */
internal sealed interface RuntimeReleaseResult {
    /** The manifest to install, and the tag it came from. */
    data class Found(val release: RuntimeManifestRelease) : RuntimeReleaseResult

    /**
     * The feed was read and held no runtime release carrying a manifest.
     * [truncated] when the scan stopped at its page cap rather than at the end
     * of the feed, so "none in the newest [scanned]" is not quite "none".
     */
    data class NoneFound(val scanned: Int, val truncated: Boolean) : RuntimeReleaseResult

    /** This build names no runtime repository; nothing was asked of GitHub. */
    data object NotConfigured : RuntimeReleaseResult

    /** The request did not complete. [status] is null when GitHub never answered. */
    data class Failed(val status: Int?, val message: String) : RuntimeReleaseResult
}

/**
 * A refusal from the GitHub API. [status] is carried so callers can tell a
 * rejected credential (401, worth renewing) from a missing permission, rather
 * than matching on the message text.
 */
class GitHubApiException(val status: Int, message: String) : RuntimeException(message)

/**
 * How a request is actually sent. Substituted in tests: the bugs behind #193
 * and #201 were both in how many pages got asked for, which no test of a
 * single hand-written body can see.
 */
internal typealias HttpSend = (method: String, url: String, token: String?, body: String?) -> String

/**
 * Calls to api.github.com. This class only fetches; reading the JSON that comes
 * back lives in [GitHubResponses], which is testable on its own.
 */
class GitHubApi internal constructor(private val send: HttpSend = ::httpRequest) {
    /**
     * Short-lived token `config.sh` exchanges for a runner identity. The
     * endpoint differs by scope: repository runners are issued from the repo,
     * organization runners from the org.
     */
    fun createRegistrationToken(target: RunnerTarget, token: String): String {
        val path = when (target) {
            is RunnerTarget.Repository ->
                "repos/${target.owner}/${target.name}/actions/runners/registration-token"
            is RunnerTarget.Organization ->
                "orgs/${target.org}/actions/runners/registration-token"
        }
        return GitHubResponses.registrationToken(
            request("POST", "https://api.github.com/$path", token),
        )
    }

    /**
     * Short-lived token `config.sh remove` exchanges to deregister (issue #154).
     *
     * The same shape and the same permission as the registration token above,
     * because it is the same operation read backwards.
     */
    fun createRemovalToken(target: RunnerTarget, token: String): String {
        val path = when (target) {
            is RunnerTarget.Repository ->
                "repos/${target.owner}/${target.name}/actions/runners/remove-token"
            is RunnerTarget.Organization ->
                "orgs/${target.org}/actions/runners/remove-token"
        }
        return GitHubResponses.registrationToken(
            request("POST", "https://api.github.com/$path", token),
        )
    }

    /**
     * Installations of the DroidRunner GitHub App visible to the signed-in
     * user, all of them.
     *
     * Paged to exhaustion rather than capped: a user in more than a hundred
     * installations was losing whole organisations from the picker with
     * nothing on screen to say so (issue #201). Installations are few and the
     * first page is almost always the last, so the exhaustive loop costs one
     * request in practice.
     */
    fun listInstallations(token: String): List<Installation> {
        val installations = mutableListOf<Installation>()
        var page = 1
        while (page <= RUNAWAY_PAGES) {
            val batch = GitHubResponses.installationPage(
                request(
                    "GET",
                    "https://api.github.com/user/installations" +
                        "?per_page=${GitHubResponses.PAGE_SIZE}&page=$page",
                    token,
                ),
            )
            installations += batch.installations
            if (!batch.hasMore) break
            page++
        }
        return installations
    }

    /** Organizations this app is installed on, as registration targets. */
    fun listOrganizations(token: String): List<RunnerTarget.Organization> =
        GitHubResponses.organizations(listInstallations(token))

    /**
     * Repositories the given installation grants this user access to, plus
     * whether there were more than [MAX_REPOSITORY_PAGES] pages of them.
     *
     * The cap stays: an org with thousands of repositories would otherwise
     * hold the picker on a spinner for dozens of round trips. What changes is
     * that the caller is told the list was cut, so the screen can say it
     * instead of presenting five hundred repositories as all of them
     * (issue #201).
     */
    internal fun installationRepositories(token: String, installationId: Long): RepositoryList {
        val repos = mutableListOf<RepositoryRef>()
        var page = 1
        var truncated = false
        while (true) {
            val batch = GitHubResponses.repositoryPage(
                request(
                    "GET",
                    "https://api.github.com/user/installations/$installationId/repositories" +
                        "?per_page=${GitHubResponses.PAGE_SIZE}&page=$page",
                    token,
                ),
            )
            repos += batch.repositories
            if (!batch.hasMore) break
            if (page >= MAX_REPOSITORY_PAGES) {
                truncated = true
                break
            }
            page++
        }
        return RepositoryList(repos, truncated)
    }

    /**
     * The repositories alone. Drops the truncation flag on the floor, which is
     * the defect in #201, so callers that put this list in front of a person
     * should use [installationRepositories] and show what it says.
     */
    fun listInstallationRepositories(token: String, installationId: Long): List<RepositoryRef> =
        installationRepositories(token, installationId).repositories

    /**
     * URL of runtime-manifest.json from the newest runtime-* release of
     * [repo], or null when there is none, the build names no repo, or the
     * request failed — [latestRuntimeRelease] tells those three apart. Works
     * without a token on public repos; a token avoids rate limits.
     */
    fun latestRuntimeManifestUrl(repo: String, token: String?): String? =
        latestRuntimeManifest(repo, token)?.url

    internal fun latestRuntimeManifest(repo: String, token: String?): RuntimeManifestRelease? =
        (latestRuntimeRelease(repo, token) as? RuntimeReleaseResult.Found)?.release

    /**
     * The newest `runtime-*` release of [repo] carrying a manifest.
     *
     * The release feed mixes app and runtime tags and is ordered by date, so
     * how deep the runtime bundle sits depends on how many app releases came
     * after it — one page was never a place to stop looking (issue #193). It
     * is paged until the feed runs out, or until a manifest turns up, which on
     * this repository is the first page.
     *
     * Asking for the runtime tag directly would be one request instead of one
     * or two, but nothing in the app knows the newest runtime version: there
     * is no "latest runtime" endpoint (`releases/latest` answers with whatever
     * release is newest, app ones included), and a version baked into the APK
     * would freeze at build time, which is the bug again with a longer fuse.
     * Scanning also keeps the fallback to the previous runtime release while a
     * new one's assets are still uploading, which a direct tag lookup loses.
     */
    internal fun latestRuntimeRelease(repo: String, token: String?): RuntimeReleaseResult {
        if (repo.isBlank()) return RuntimeReleaseResult.NotConfigured
        var newestRuntimeTag: String? = null
        var scanned = 0
        var page = 1
        while (true) {
            val batch = try {
                GitHubResponses.runtimeManifestPage(
                    request(
                        "GET",
                        "https://api.github.com/repos/$repo/releases" +
                            "?per_page=${GitHubResponses.PAGE_SIZE}&page=$page",
                        token,
                    ),
                    newestRuntimeTag,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // No network, a proxy's HTML, a rate limit, a body that is not
                // the array expected: all of it means the question was never
                // answered, which is not the same as an answer of "none".
                val status = (failure as? GitHubApiException)?.status
                return RuntimeReleaseResult.Failed(
                    status,
                    failure.message ?: failure.toString(),
                )
            }
            newestRuntimeTag = batch.newestRuntimeTag
            scanned += batch.releaseCount
            batch.release?.let { return RuntimeReleaseResult.Found(it) }
            if (batch.releaseCount < GitHubResponses.PAGE_SIZE) {
                return RuntimeReleaseResult.NoneFound(scanned, truncated = false)
            }
            if (page >= MAX_RELEASE_PAGES) {
                return RuntimeReleaseResult.NoneFound(scanned, truncated = true)
            }
            page++
        }
    }

    /** Where a target's runners live; the two scopes use different endpoints. */
    private fun runnersPath(target: RunnerTarget): String = when (target) {
        is RunnerTarget.Repository -> "repos/${target.owner}/${target.name}/actions/runners"
        is RunnerTarget.Organization -> "orgs/${target.org}/actions/runners"
    }

    /** Labels GitHub currently holds for this runner, its own platform ones included. */
    fun runnerLabels(target: RunnerTarget, name: String, token: String): Set<String> =
        GitHubResponses.runnerLabels(
            request("GET", "https://api.github.com/${runnersPath(target)}?per_page=100", token),
            name,
        )

    /** The id GitHub knows this runner by, or null when it is not registered there. */
    fun runnerId(target: RunnerTarget, name: String, token: String): Long? =
        GitHubResponses.runnerId(
            request("GET", "https://api.github.com/${runnersPath(target)}?per_page=100", token),
            name,
        )

    /**
     * Replaces a runner's custom labels without re-registering it.
     *
     * Re-registering would mean a new runner identity and a stopped listener;
     * this is the endpoint that exists so a device can correct what it says
     * about itself while it keeps working (issue #80).
     */
    fun replaceLabels(target: RunnerTarget, runnerId: Long, labels: List<String>, token: String) {
        request(
            "PUT",
            "https://api.github.com/${runnersPath(target)}/$runnerId/labels",
            token,
            JSONObject().put("labels", JSONArray(labels)).toString(),
        )
    }

    private fun request(method: String, url: String, token: String?, body: String? = null): String =
        send(method, url, token, body)

    private companion object {
        /**
         * Repository pages the picker will wait through: 500 repositories,
         * beyond which the list is handed over marked truncated.
         */
        const val MAX_REPOSITORY_PAGES = 5

        /**
         * Release pages scanned for a runtime bundle: 500 releases. A runtime
         * release older than the last five hundred app releases is not one
         * anybody should be installing.
         */
        const val MAX_RELEASE_PAGES = 5

        /**
         * A stop for loops meant to run to exhaustion. Nothing but a server
         * answering every page full reaches it — no account is in 5,000 app
         * installations — and without it that server spins the setup screen
         * forever.
         */
        const val RUNAWAY_PAGES = 50
    }
}

/**
 * The real request. Split out from [GitHubApi] so tests can hand the class
 * something else to send with.
 */
private fun httpRequest(method: String, url: String, token: String?, body: String?): String {
    val payload = body?.toByteArray()
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 15_000
        readTimeout = 15_000
        setRequestProperty("Accept", "application/vnd.github+json")
        token?.let { setRequestProperty("Authorization", "Bearer $it") }
        setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        setRequestProperty("User-Agent", "DroidRunner/0.1")
        if (payload != null) setRequestProperty("Content-Type", "application/json")
        if (method == "POST" || method == "PUT") {
            doOutput = true
            setFixedLengthStreamingMode(payload?.size ?: 0)
        }
    }
    if (method == "POST" || method == "PUT") {
        connection.outputStream.use { out -> payload?.let(out::write) }
    }
    val responseBody =
        (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
            .bufferedReader().use { it.readText() }
    if (connection.responseCode !in 200..299) {
        throw GitHubApiException(
            connection.responseCode,
            GitHubResponses.errorMessage(connection.responseCode, responseBody),
        )
    }
    return responseBody
}
