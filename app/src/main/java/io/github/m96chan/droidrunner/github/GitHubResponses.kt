package io.github.m96chan.droidrunner.github

import io.github.m96chan.droidrunner.model.RunnerTarget
import org.json.JSONArray
import org.json.JSONObject

/** One page of repositories, plus whether GitHub still has more to hand out. */
internal data class RepositoryPage(val repositories: List<RepositoryRef>, val hasMore: Boolean)

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
    fun installations(body: String): List<Installation> {
        val installations = JSONObject(body).getJSONArray("installations")
        return (0 until installations.length()).map { index ->
            val installation = installations.getJSONObject(index)
            Installation(
                id = installation.getLong("id"),
                account = installation.optJSONObject("account")?.optString("login").orEmpty(),
                appSlug = installation.optString("app_slug"),
                accountType = installation.optJSONObject("account")?.optString("type").orEmpty(),
            )
        }
    }

    /** The subset of [installations] that can host organization runners. */
    fun organizations(installations: List<Installation>): List<RunnerTarget.Organization> =
        installations
            .filter { it.accountType == "Organization" && it.account.isNotBlank() }
            .map { RunnerTarget.Organization(it.account) }

    /** The repositories on one page of the installation-repositories endpoint. */
    fun repositoryPage(body: String): RepositoryPage {
        val batch = JSONObject(body).getJSONArray("repositories")
        val repositories = (0 until batch.length()).map { index ->
            val fullName = batch.getJSONObject(index).getString("full_name")
            RepositoryRef(fullName.substringBefore('/'), fullName.substringAfter('/'))
        }
        return RepositoryPage(repositories, hasMore = batch.length() >= PAGE_SIZE)
    }

    /**
     * URL of runtime-manifest.json from the newest `runtime-*` release that
     * carries one, or null when no listed release does.
     */
    fun runtimeManifestUrl(body: String): String? {
        val releases = JSONArray(body)
        for (index in 0 until releases.length()) {
            val release = releases.getJSONObject(index)
            if (!release.getString("tag_name").startsWith("runtime-")) continue
            val assets = release.getJSONArray("assets")
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.getJSONObject(assetIndex)
                if (asset.getString("name") == MANIFEST_ASSET) {
                    return asset.getString("browser_download_url")
                }
            }
        }
        return null
    }

    /**
     * What the user is told when GitHub refuses. GitHub explains itself in a
     * `message` field; a body that is not JSON at all (a proxy's HTML error
     * page, an empty response) is passed through as-is, because that raw text
     * is the only clue left when the refusal did not come from GitHub itself.
     */
    fun errorMessage(status: Int, body: String): String {
        val message = runCatching { JSONObject(body).optString("message") }.getOrNull() ?: body
        return "GitHub API $status: $message"
    }
}
