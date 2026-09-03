package io.github.m96chan.droidrunner.github

import io.github.m96chan.droidrunner.security.SecretStore
import java.io.IOException

/**
 * The sign-in is gone and only a person can restore it: GitHub refused to renew
 * it, so retrying with the same credential will never work.
 */
class SignInExpiredException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Keeps the stored GitHub sign-in usable (issue #42).
 *
 * The device flow issues an access token that lasts eight hours and a refresh
 * token that outlives it. Renewal happens ahead of the stated expiry, and again
 * when GitHub rejects a token anyway — an expiry is advisory, since the device's
 * clock and GitHub's need not agree, and a token can be revoked early.
 *
 * The stored sign-in is never cleared here. From the inside, losing network
 * looks much like losing a sign-in, and discarding a refresh token that was
 * merely unreachable would turn a flaky connection into a trip to the setup
 * screen.
 */
class UserSession(
    private val store: SecretStore,
    clientId: String,
    private val auth: GitHubAuth = GitHubAuth(clientId),
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * The user access token to use right now, renewed first if it is close to
     * lapsing. Null when this device has no user sign-in at all — a manual PAT
     * setup, or one that was never connected.
     */
    fun accessToken(): String? {
        val stored = store.getUserToken() ?: return null
        if (!TokenRefreshPolicy.isDue(store.getUserTokenExpiresAt(), now())) return stored
        // Nothing to renew with: a sign-in stored before refresh tokens were
        // kept still works until it lapses, and failing here would end it early.
        if (store.getUserRefreshToken() == null) return stored
        return renew()
    }

    /**
     * Renews the sign-in and stores the result.
     *
     * @throws SignInExpiredException when GitHub refuses, which is the one case
     *   a human has to act on.
     * @throws IOException when GitHub could not be reached, which is not.
     */
    fun renew(): String {
        val refreshToken = store.getUserRefreshToken()
            ?: throw SignInExpiredException("the stored sign-in has no refresh token")
        val renewed = try {
            auth.refresh(refreshToken)
        } catch (offline: IOException) {
            throw offline
        } catch (refused: Exception) {
            throw SignInExpiredException("GitHub would not renew the sign-in: ${refused.message}", refused)
        }
        store.putUserToken(
            renewed.accessToken,
            renewed.refreshToken,
            TokenRefreshPolicy.expiresAtMillis(renewed.expiresInSeconds, now()),
        )
        return renewed.accessToken
    }
}
