package io.github.m96chan.droidrunner.github

import io.github.m96chan.droidrunner.model.RunnerTarget
import org.json.JSONArray
import org.json.JSONObject

/** One page of repositories, plus whether GitHub still has more to hand out. */
internal data class RepositoryPage(val repositories: List<RepositoryRef>, val hasMore: Boolean)

/** One page of installations, plus whether GitHub still has more to hand out. */
internal data class InstallationPage(val installations: List<Installation>, val hasMore: Boolean)

/** The manifest selected from GitHub's newest-first release list. */
internal data class RuntimeManifestRelease(
    val url: String,
    val tag: String,
    val newestRuntimeTag: String,
) {
    val fallbackNotice: String?
        get() = if (tag == newestRuntimeTag) null
        else "using $tag because $newestRuntimeTag is not ready yet"
}

/**
 * One page of the release feed, read for a runtime manifest.
 *
 * The feed mixes `v*` app releases with `runtime-*` bundles and the app ones
 * are far more frequent, so the bundle a device needs can sit pages deep
 * (issue #193). Scanning it therefore spans requests, and everything the next
 * request needs in order to carry on is here: [newestRuntimeTag] so a manifest
 * found later still knows which tag it is standing in for, and [releaseCount]
 * so the caller can tell a full page -- there is more -- from the last one.
 */
internal data class RuntimeManifestPage(
    val release: RuntimeManifestRelease?,
    val newestRuntimeTag: String?,
    val releaseCount: Int,
)

/**
 * Reads GitHub's JSON into the values [GitHubApi] returns.
 *
 * Kept apart from the fetching so the shapes GitHub legitimately sends -- a
 * release whose assets are not uploaded yet, an installation whose account is
 * gone, a last page that is not full -- can be exercised without a network.
 * Everything here runs while a device is being set up: once a runner is
 * registered none of it is called again, so a break here is invisible to every
 * device already in the field and surfaces only for the next person adding one.
 */
internal object GitHubResponses {

    /** Entries GitHub returns per page; a shorter page is therefore the last. */
    const val PAGE_SIZE = 100

    private const val MANIFEST_ASSET = "runtime-manifest.json"

    /** The short-lived token `config.sh` exchanges for a runner identity. */
    fun registrationToken(body: String): String = JSONObject(body).getString("token")

    /** Installations of the app, with the account each one belongs to. */
    fun installations(body: String): List<Installation> =
        installationsIn(JSONObject(body).getJSONArray("installations"))

    /** One page of [installations], and whether another page is waiting. */
    fun installationPage(body: String): InstallationPage {
        val installations = JSONObject(body).getJSONArray("installations")
        // "Is there another page" is a question about what GitHub sent, not
        // about what survived parsing: an installation whose account is gone
        // is dropped below, and counting only the survivors would end the
        // paging one organisation early (issue #201).
        return InstallationPage(
            installationsIn(installations),
            hasMore = installations.length() >= PAGE_SIZE,
        )
    }

    private fun installationsIn(installations: JSONArray): List<Installation> =
        (0 until installations.length()).mapNotNull { index ->
            runCatching {
                val installation = installations.getJSONObject(index)
                Installation(
                    id = installation.getLong("id"),
                    account = installation.optJSONObject("account")?.optString("login").orEmpty(),
                    appSlug = installation.getString("app_slug"),
                    accountType = installation.optJSONObject("account")?.optString("type").orEmpty(),
                )
            }.getOrNull()
        }

    /** The subset of [installations] that can host organization runners. */
    fun organizations(installations: List<Installation>): List<RunnerTarget.Organization> =
        installations
            .filter { it.accountType == "Organization" && it.account.isNotBlank() }
            .map { RunnerTarget.Organization(it.account) }

    /**
     * The id of the runner registered under [name], or null when this target
     * has no such runner — which is the normal answer after someone removed it
     * from the GitHub side.
     */
    fun runnerId(body: String, name: String): Long? {
        val runners = JSONObject(body).optJSONArray("runners") ?: return null
        for (index in 0 until runners.length()) {
            val runner = runners.optJSONObject(index) ?: continue
            if (runner.optString("name") == name) return runner.optLong("id").takeIf { it != 0L }
        }
        return null
    }

    /** Every label GitHub currently has for a runner, its own included. */
    fun runnerLabels(body: String, name: String): Set<String> {
        val runners = JSONObject(body).optJSONArray("runners") ?: return emptySet()
        for (index in 0 until runners.length()) {
            val runner = runners.optJSONObject(index) ?: continue
            if (runner.optString("name") != name) continue
            val labels = runner.optJSONArray("labels") ?: return emptySet()
            return (0 until labels.length()).mapNotNull { labels.optJSONObject(it)?.optString("name") }.toSet()
        }
        return emptySet()
    }

    /** The repositories on one page of the installation-repositories endpoint. */
    fun repositoryPage(body: String): RepositoryPage {
        val batch = JSONObject(body).getJSONArray("repositories")
        val repositories = (0 until batch.length()).map { index ->
            val repository = batch.getJSONObject(index)
            val fullName = repository.getString("full_name")
            RepositoryRef(
                owner = fullName.substringBefore('/'),
                name = fullName.substringAfter('/'),
                isPrivate = if (repository.has("private")) repository.optBoolean("private") else null,
            )
        }
        return RepositoryPage(repositories, hasMore = batch.length() >= PAGE_SIZE)
    }

    /**
     * Reads one page of the release feed for runtime-manifest.json.
     *
     * [newestRuntimeTagSoFar] is the newest `runtime-*` tag an earlier page
     * already turned up. It is handed back in because the newest runtime
     * release and the newest one carrying a manifest need not be on the same
     * page, and the difference between those two is what the fallback notice
     * is about.
     */
    fun runtimeManifestPage(
        body: String,
        newestRuntimeTagSoFar: String? = null,
    ): RuntimeManifestPage {
        val releases = JSONArray(body)
        var newestRuntimeTag = newestRuntimeTagSoFar
        for (index in 0 until releases.length()) {
            val release = releases.optJSONObject(index) ?: continue
            val tag = release.optString("tag_name").takeIf { it.startsWith("runtime-") } ?: continue
            if (newestRuntimeTag == null) newestRuntimeTag = tag
            val assets = release.optJSONArray("assets") ?: continue
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.optJSONObject(assetIndex) ?: continue
                if (asset.optString("name") != MANIFEST_ASSET) continue
                val url = asset.optString("browser_download_url").takeIf { it.isNotBlank() } ?: continue
                return RuntimeManifestPage(
                    RuntimeManifestRelease(url, tag, newestRuntimeTag),
                    newestRuntimeTag,
                    releases.length(),
                )
            }
        }
        return RuntimeManifestPage(null, newestRuntimeTag, releases.length())
    }

    /**
     * URL of runtime-manifest.json from the newest `runtime-*` release that
     * carries one, or null when nothing on this one page does. Callers holding
     * a repository rather than a page want [GitHubApi.latestRuntimeRelease],
     * which keeps asking until the feed runs out.
     */
    fun runtimeManifest(body: String): RuntimeManifestRelease? = runtimeManifestPage(body).release

    fun runtimeManifestUrl(body: String): String? = runtimeManifest(body)?.url

    /**
     * What the user is told when GitHub refuses. GitHub explains itself in a
     * `message` field; a body that is not JSON at all (a proxy's HTML error
     * page, an empty response) is passed through as-is, because that raw text
     * is the only clue left when the refusal did not come from GitHub itself.
     */
    fun errorMessage(status: Int, body: String): String {
        val message = runCatching { JSONObject(body).optString("message") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: body
        return "GitHub API $status: $message"
    }
}
