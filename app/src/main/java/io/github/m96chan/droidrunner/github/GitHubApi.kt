package io.github.m96chan.droidrunner.github

import io.github.m96chan.droidrunner.model.RunnerTarget
import java.net.HttpURLConnection
import java.net.URL
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
 * A refusal from the GitHub API. [status] is carried so callers can tell a
 * rejected credential (401, worth renewing) from a missing permission, rather
 * than matching on the message text.
 */
class GitHubApiException(val status: Int, message: String) : RuntimeException(message)

/**
 * Calls to api.github.com. This class only fetches; reading the JSON that comes
 * back lives in [GitHubResponses], which is testable on its own.
 */
class GitHubApi {
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

    /** Installations of the DroidRunner GitHub App visible to the signed-in user. */
    fun listInstallations(token: String): List<Installation> =
        GitHubResponses.installations(
            request(
                "GET",
                "https://api.github.com/user/installations?per_page=${GitHubResponses.PAGE_SIZE}",
                token,
            ),
        )

    /** Organizations this app is installed on, as registration targets. */
    fun listOrganizations(token: String): List<RunnerTarget.Organization> =
        GitHubResponses.organizations(listInstallations(token))

    /** Repositories the given installation grants this user access to. */
    fun listInstallationRepositories(token: String, installationId: Long): List<RepositoryRef> {
        val repos = mutableListOf<RepositoryRef>()
        var page = 1
        while (page <= MAX_PAGES) {
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
            page++
        }
        return repos
    }

    /**
     * URL of runtime-manifest.json from the newest runtime-* release of
     * [repo], or null when none exists. Works without a token on public
     * repos; a token avoids rate limits.
     */
    fun latestRuntimeManifestUrl(repo: String, token: String?): String? =
        latestRuntimeManifest(repo, token)?.url

    internal fun latestRuntimeManifest(repo: String, token: String?): RuntimeManifestRelease? =
        GitHubResponses.runtimeManifest(
            request("GET", "https://api.github.com/repos/$repo/releases?per_page=20", token),
        )

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

    private fun request(method: String, url: String, token: String?, body: String? = null): String {
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
        val body = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
            .bufferedReader().use { it.readText() }
        if (connection.responseCode !in 200..299) {
            throw GitHubApiException(
                connection.responseCode,
                GitHubResponses.errorMessage(connection.responseCode, body),
            )
        }
        return body
    }

    private companion object {
        const val MAX_PAGES = 5
    }
}
