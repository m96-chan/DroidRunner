package io.github.m96chan.droidrunner.github

/**
 * When a stored sign-in is due for renewal (issue #42).
 *
 * Pure, because the interesting part is the arithmetic around the boundary and
 * that is far easier to pin down here than by waiting eight hours on a device.
 */
object TokenRefreshPolicy {

    /**
     * How long before the stated expiry a token is treated as spent.
     *
     * A registration token is exchanged at the start of a job and the reply has
     * to still be accepted a moment later; renewing half an hour early costs one
     * extra request and removes the whole class of "expired in flight".
     */
    const val REFRESH_MARGIN_MS = 30 * 60 * 1000L

    /**
     * Wall-clock expiry of a token GitHub said lives [expiresInSeconds]
     * seconds, or null when it did not say — a GitHub App that opted out of
     * user access token expiration returns no lifetime, and inventing one would
     * keep renewing a token that never lapses.
     */
    fun expiresAtMillis(expiresInSeconds: Int?, nowMillis: Long): Long? =
        expiresInSeconds?.takeIf { it > 0 }?.let { nowMillis + it * 1000L }

    /**
     * Whether to renew now. An unknown expiry is never due: either the token
     * does not expire, or it was stored before expiries were kept, and in both
     * cases a rejected request is the only honest signal left.
     */
    fun isDue(expiresAtMillis: Long?, nowMillis: Long): Boolean =
        expiresAtMillis != null && nowMillis >= expiresAtMillis - REFRESH_MARGIN_MS
}
