package dev.devenus.droidrunner.github

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class GitHubApi {
    fun createRegistrationToken(owner: String, repo: String, pat: String): String {
        val url = URL("https://api.github.com/repos/$owner/$repo/actions/runners/registration-token")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $pat")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "DroidRunner/0.1")
            setFixedLengthStreamingMode(0)
        }
        connection.outputStream.use { }
        val body = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
            .bufferedReader().use { it.readText() }
        if (connection.responseCode != 201) {
            val message = runCatching { JSONObject(body).optString("message") }.getOrNull() ?: body
            error("GitHub API ${connection.responseCode}: $message")
        }
        return JSONObject(body).getString("token")
    }
}
