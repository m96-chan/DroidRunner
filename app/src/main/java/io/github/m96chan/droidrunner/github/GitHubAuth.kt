package io.github.m96chan.droidrunner.github

import kotlinx.coroutines.delay
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class DeviceAuthorization(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val intervalSeconds: Int,
    val expiresInSeconds: Int,
)

/**
 * A sign-in. [expiresInSeconds] is null when the GitHub App opted out of user
 * access token expiration, in which case there is nothing to renew ahead of.
 */
data class UserToken(
    val accessToken: String,
    val refreshToken: String?,
    val expiresInSeconds: Int? = null,
)

/**
 * GitHub App device flow (no client secret required). The app only needs its
 * public client id; permissions come from the GitHub App configuration and the
 * repositories the user installed it on.
 */
class GitHubAuth(private val clientId: String) {

    fun requestDeviceCode(): DeviceAuthorization {
        val json = postForm(
            "https://github.com/login/device/code",
            mapOf("client_id" to clientId),
        )
        json.optString("error").takeIf { it.isNotEmpty() }?.let { error("Device code request failed: $it") }
        return DeviceAuthorization(
            deviceCode = json.getString("device_code"),
            userCode = json.getString("user_code"),
            verificationUri = json.getString("verification_uri"),
            intervalSeconds = json.optInt("interval", 5),
            expiresInSeconds = json.optInt("expires_in", 900),
        )
    }

    suspend fun awaitToken(authorization: DeviceAuthorization): UserToken {
        var interval = authorization.intervalSeconds.coerceAtLeast(5)
        val deadline = System.currentTimeMillis() + authorization.expiresInSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000L)
            val json = try {
                postForm(
                    TOKEN_URL,
                    mapOf(
                        "client_id" to clientId,
                        "device_code" to authorization.deviceCode,
                        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    ),
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (transient: Exception) {
                // The OS cuts background network while the user approves the code
                // in the browser; keep polling until the code expires.
                android.util.Log.d("DroidRunner", "device flow poll retry: ${transient.message}")
                interval = (interval + 5).coerceAtMost(30)
                continue
            }
            val error = json.optString("error").takeIf { it.isNotEmpty() }
            android.util.Log.d("DroidRunner", "device flow poll: ${error ?: "token received"}")
            if (error == null) return json.toUserToken()
            interval = nextDelaySeconds(error, interval)
                ?: error("Authorization failed: ${json.optString("error_description").ifEmpty { error }}")
        }
        error("Device authorization expired before it was approved")
    }

    /**
     * Trades a refresh token for a new sign-in (issue #42). Same endpoint and
     * reply shape as the device flow; only the grant differs.
     *
     * GitHub rotates the refresh token on every renewal, so the returned pair
     * has to be stored as a whole — keeping the old refresh token would end
     * the chain after one renewal.
     */
    fun refresh(refreshToken: String): UserToken {
        val json = postForm(
            TOKEN_URL,
            mapOf(
                "client_id" to clientId,
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
            ),
        )
        json.optString("error").takeIf { it.isNotEmpty() }?.let { error ->
            error("Token refresh failed: ${json.optString("error_description").ifEmpty { error }}")
        }
        return json.toUserToken()
    }

    private fun JSONObject.toUserToken() = UserToken(
        accessToken = getString("access_token"),
        refreshToken = optString("refresh_token").takeIf { it.isNotEmpty() },
        expiresInSeconds = optInt("expires_in").takeIf { it > 0 },
    )

    private fun postForm(url: String, fields: Map<String, String>): JSONObject {
        val body = fields.entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, "UTF-8")}"
        }.toByteArray()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", "DroidRunner/0.1")
            setFixedLengthStreamingMode(body.size)
        }
        connection.outputStream.use { it.write(body) }
        val text = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
            .bufferedReader().use { it.readText() }
        check(connection.responseCode in 200..299) { "GitHub ${connection.responseCode}: ${text.take(300)}" }
        return JSONObject(text)
    }

    companion object {
        private const val TOKEN_URL = "https://github.com/login/oauth/access_token"

        /**
         * Device-flow poll backoff: keep waiting while authorization is pending,
         * add 5s on slow_down, stop (null) on any terminal error.
         */
        fun nextDelaySeconds(error: String, currentInterval: Int): Int? = when (error) {
            "authorization_pending" -> currentInterval
            "slow_down" -> currentInterval + 5
            else -> null
        }
    }
}

/**
 * Persistable form of a pending device authorization, so polling can resume
 * after the OS kills the app while the user is approving in the browser.
 */
fun DeviceAuthorization.toStoredJson(nowMillis: Long = System.currentTimeMillis()): String =
    JSONObject()
        .put("device_code", deviceCode)
        .put("user_code", userCode)
        .put("verification_uri", verificationUri)
        .put("interval", intervalSeconds)
        .put("expires_at", nowMillis + expiresInSeconds * 1000L)
        .toString()

fun storedDeviceAuthorization(
    json: String,
    nowMillis: Long = System.currentTimeMillis(),
): DeviceAuthorization? = runCatching {
    val stored = JSONObject(json)
    val remainingSeconds = ((stored.getLong("expires_at") - nowMillis) / 1000).toInt()
    if (remainingSeconds < 10) null
    else DeviceAuthorization(
        deviceCode = stored.getString("device_code"),
        userCode = stored.getString("user_code"),
        verificationUri = stored.getString("verification_uri"),
        intervalSeconds = stored.getInt("interval"),
        expiresInSeconds = remainingSeconds,
    )
}.getOrNull()
