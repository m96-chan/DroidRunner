package io.github.m96chan.droidrunner.github

import io.github.m96chan.droidrunner.security.UserTokenStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Issue #196: what two callers renewing the same sign-in at once must not do. */
class UserSessionTest {

    private val now = 1_700_000_000_000L

    /** The store as the app sees it — one map, several sessions over it. */
    private class InMemoryStore(
        @Volatile var token: String? = null,
        @Volatile var refreshToken: String? = null,
        @Volatile var expiresAt: Long? = null,
    ) : UserTokenStore {
        /** Threads that have read a refresh token, so a test can wait until
         *  both callers are past the read the race turns on. */
        val readers: MutableSet<Long> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        override fun getUserToken() = token
        override fun getUserRefreshToken(): String? {
            readers.add(Thread.currentThread().id)
            return refreshToken
        }
        override fun getUserTokenExpiresAt() = expiresAt

        @Synchronized
        override fun putUserToken(value: String, refreshToken: String?, expiresAtMillis: Long?) {
            token = value
            // Matches SecretStore: a renewal that returned no new refresh token
            // leaves the stored one in place.
            refreshToken?.let { this.refreshToken = it }
            expiresAt = expiresAtMillis
        }
    }

    /** Due for renewal: inside the margin, with something to renew with. */
    private fun dueStore() = InMemoryStore(
        token = "old-access",
        refreshToken = "refresh-1",
        expiresAt = now + TokenRefreshPolicy.REFRESH_MARGIN_MS - 1,
    )

    private fun session(
        store: UserTokenStore,
        refresh: (String) -> UserToken,
    ) = UserSession(store, "client-id", now = { now }, refresh = refresh)

    @Test fun twoCallersAtOnceRenewOnceAndBothGetTheNewToken() {
        // The reported trigger: RunnerService autostart and a ⚙ tap in the same
        // second, over one store. The loser must not spend the rotated refresh
        // token on a second request GitHub would refuse.
        val store = dueStore()
        val requests = AtomicInteger()
        // Holds the winner inside its request until the other caller has read
        // the refresh token too, so the race is run rather than hoped for: the
        // window is between that read and the renewal landing.
        val refresh: (String) -> UserToken = {
            requests.incrementAndGet()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (store.readers.size < 2 && System.nanoTime() < deadline) Thread.sleep(1)
            UserToken("new-access", "refresh-2", expiresInSeconds = 28_800)
        }

        val results = arrayOfNulls<String>(2)
        val bothStarted = CountDownLatch(2)
        val threads = (0..1).map { index ->
            Thread {
                bothStarted.countDown()
                bothStarted.await(5, TimeUnit.SECONDS)
                results[index] = session(store, refresh).accessToken()
            }
        }
        threads.forEach(Thread::start)
        threads.forEach { it.join(30_000) }

        assertEquals(1, requests.get())
        assertEquals("new-access", results[0])
        assertEquals("new-access", results[1])
        assertEquals("refresh-2", store.refreshToken)
    }

    @Test fun aCallerThatLostTheRaceIsNotToldTheSignInExpired() {
        // The loser reached GitHub with the refresh token the winner had
        // already spent, so GitHub says invalid_grant. The stored refresh token
        // has moved on since this caller read it, which is what tells the two
        // cases apart from in here — the screen turns a SignInExpiredException
        // into a disconnect, so getting this wrong wipes a working sign-in.
        val store = dueStore()
        val winner = session(store) { UserToken("new-access", "refresh-2", 28_800) }
        val loser = UserSession(
            store,
            "client-id",
            now = { now },
            refresh = {
                winner.renew()
                throw IllegalStateException("Token refresh failed: invalid_grant")
            },
        )

        assertEquals("new-access", loser.renew())
        assertEquals("new-access", store.token)
        assertEquals("refresh-2", store.refreshToken)
    }

    @Test fun aRefusalWithNothingElseHavingChangedStillEndsTheSignIn() {
        // The other half of the distinction: nobody else renewed, so the
        // refusal is the real thing and a person has to sign in again.
        val store = dueStore()
        val session = session(store) { throw IllegalStateException("Token refresh failed: invalid_grant") }

        assertThrows(SignInExpiredException::class.java) { session.renew() }
        // Even then this class stores nothing and removes nothing; clearing is
        // the screen's decision, and the runner service must not make it.
        assertEquals("old-access", store.token)
        assertEquals("refresh-1", store.refreshToken)
    }

    @Test fun beingUnreachableIsNotAnExpiredSignIn() {
        val store = dueStore()
        val session = session(store) { throw java.io.IOException("network is unreachable") }

        assertThrows(java.io.IOException::class.java) { session.renew() }
        assertEquals("refresh-1", store.refreshToken)
    }

    @Test fun aTokenThatIsNotDueYetIsHandedBackUntouched() {
        val store = InMemoryStore("access", "refresh-1", now + 8 * 60 * 60 * 1000L)
        val session = session(store) { error("renewing a token that is not due") }

        assertEquals("access", session.accessToken())
    }
}
