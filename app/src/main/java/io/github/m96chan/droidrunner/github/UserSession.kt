package io.github.m96chan.droidrunner.github

import io.github.m96chan.droidrunner.security.UserTokenStore
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
 *
 * Instances are cheap and short-lived — the setup screen, `RunnerRegistration`
 * and `RunnerService` each make their own over the one store — so renewal is
 * serialised on a lock shared by all of them (issue #196). GitHub rotates the
 * refresh token on every renewal, so two renewals at once mean one of them
 * presents a spent token and is told the sign-in is invalid, which it is not.
 */
class UserSession(
    private val store: UserTokenStore,
    clientId: String,
    auth: GitHubAuth = GitHubAuth(clientId),
    private val now: () -> Long = System::currentTimeMillis,
    // The one request this class makes, as a value: the race in #196 is between
    // two of them, and a test of it has to count them without a network.
    private val refresh: (String) -> UserToken = auth::refresh,
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
        val refreshToken = store.getUserRefreshToken() ?: return stored
        synchronized(RENEWAL) {
            // Read again now the lock is held (issue #196): whoever held it was
            // most likely renewing this same sign-in, and a token that is no
            // longer due is that renewal having already happened. Renewing it a
            // second time would spend a refresh token to replace a fresh token.
            val renewed = store.getUserToken()
            if (renewed != null && !TokenRefreshPolicy.isDue(store.getUserTokenExpiresAt(), now())) {
                return renewed
            }
            return renewLocked(seenRefreshToken = refreshToken)
        }
    }

    /**
     * Renews the sign-in and stores the result. Callers reach for this directly
     * when GitHub rejected a token the expiry said was fine.
     *
     * @throws SignInExpiredException when GitHub refuses, which is the one case
     *   a human has to act on.
     * @throws IOException when GitHub could not be reached, which is not.
     */
    fun renew(): String {
        // Read before the lock, so what comes back after waiting for it can be
        // compared against what this caller set out to renew.
        val seenRefreshToken = store.getUserRefreshToken()
        synchronized(RENEWAL) {
            return renewLocked(seenRefreshToken)
        }
    }

    /**
     * The renewal itself, with [RENEWAL] held. [seenRefreshToken] is what the
     * caller read before it began: a stored refresh token that differs from it
     * is another caller's completed renewal, since GitHub rotates the refresh
     * token every time and nothing else rewrites one.
     */
    private fun renewLocked(seenRefreshToken: String?): String {
        val refreshToken = store.getUserRefreshToken()
            ?: throw SignInExpiredException("the stored sign-in has no refresh token")
        if (seenRefreshToken != null && refreshToken != seenRefreshToken) {
            store.getUserToken()?.let { return it }
        }
        val renewed = try {
            refresh(refreshToken)
        } catch (offline: IOException) {
            throw offline
        } catch (refused: Exception) {
            // A refusal of a refresh token the store has since replaced is this
            // caller having lost a race, not an expired sign-in (issue #196) —
            // a sign-in completing in another thread rewrites the store the
            // same way a renewal does. Saying "expired" here is what sends the
            // user back through the device flow on a healthy sign-in, since the
            // screen answers that by clearing the very credentials that worked.
            val latest = store.getUserRefreshToken()
            if (latest != null && latest != refreshToken) {
                store.getUserToken()?.let { return it }
            }
            throw SignInExpiredException("GitHub would not renew the sign-in: ${refused.message}", refused)
        }
        store.putUserToken(
            renewed.accessToken,
            renewed.refreshToken,
            TokenRefreshPolicy.expiresAtMillis(renewed.expiresInSeconds, now()),
        )
        return renewed.accessToken
    }

    private companion object {
        /**
         * Shared by every session in the app process, which is where all of
         * them are: the runner service and the setup screen run together, and
         * the only other process (`:qnn`) holds no sign-in.
         */
        val RENEWAL = Any()
    }
}
