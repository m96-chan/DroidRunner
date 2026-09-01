package dev.devenus.droidrunner.github

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Installation(val id: Long, val account: String, val appSlug: String)

data class RepositoryRef(val owner: String, val name: String) {
    val fullName: String get() = "$owner/$name"
}

class GitHubApi {
    fun createRegistrationToken(owner: String, repo: String, token: String): String =
        request("POST", "https://api.github.com/repos/$owner/$repo/actions/runners/registration-token", token)
            .getString("token")

    /** Installations of the DroidRunner GitHub App visible to the signed-in user. */
    fun listInstallations(token: String): List<Installation> {
        val installations = request("GET", "https://api.github.com/user/installations?per_page=100", token)
            .getJSONArray("installations")
        return (0 until installations.length()).map { index ->
            val installation = installations.getJSONObject(index)
            Installation(
                id = installation.getLong("id"),
                account = installation.optJSONObject("account")?.optString("login").orEmpty(),
                appSlug = installation.optString("app_slug"),
            )
        }
    }

    /** Repositories the given installation grants this user access to. */
    fun listInstallationRepositories(token: String, installationId: Long): List<RepositoryRef> {
        val repos = mutableListOf<RepositoryRef>()
        var page = 1
        while (page <= MAX_PAGES) {
            val batch = request(
                "GET",
                "https://api.github.com/user/installations/$installationId/repositories?per_page=100&page=$page",
                token,
            ).getJSONArray("repositories")
            for (index in 0 until batch.length()) {
                val fullName = batch.getJSONObject(index).getString("full_name")
                repos += RepositoryRef(fullName.substringBefore('/'), fullName.substringAfter('/'))
            }
            if (batch.length() < 100) break
            page++
        }
        return repos
    }

    /**
     * URL of runtime-manifest.json from the newest runtime-* release of
     * [repo], or null when none exists. Works without a token on public
     * repos; a token avoids rate limits.
     */
    fun latestRuntimeManifestUrl(repo: String, token: String?): String? {
        val releases = org.json.JSONArray(
            requestRaw("GET", "https://api.github.com/repos/$repo/releases?per_page=20", token),
        )
        for (index in 0 until releases.length()) {
            val release = releases.getJSONObject(index)
            if (!release.getString("tag_name").startsWith("runtime-")) continue
            val assets = release.getJSONArray("assets")
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.getJSONObject(assetIndex)
                if (asset.getString("name") == "runtime-manifest.json") {
                    return asset.getString("browser_download_url")
                }
            }
        }
        return null
    }

    private fun request(method: String, url: String, token: String): JSONObject =
        JSONObject(requestRaw(method, url, token))

    private fun requestRaw(method: String, url: String, token: String?): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "DroidRunner/0.1")
            if (method == "POST") {
                doOutput = true
                setFixedLengthStreamingMode(0)
            }
        }
        if (method == "POST") connection.outputStream.use { }
        val body = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
            .bufferedReader().use { it.readText() }
        if (connection.responseCode !in 200..299) {
            val message = runCatching { JSONObject(body).optString("message") }.getOrNull() ?: body
            error("GitHub API ${connection.responseCode}: $message")
        }
        return body
    }

    private companion object {
        const val MAX_PAGES = 5
    }
}
