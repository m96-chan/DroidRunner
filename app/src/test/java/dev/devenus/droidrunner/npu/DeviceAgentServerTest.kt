package dev.devenus.droidrunner.npu

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Exercises the agent's access control, which is the security boundary between
 * untrusted job code and the device's hardware APIs.
 */
class DeviceAgentServerTest {
    @get:Rule val temp = TemporaryFolder()

    private lateinit var runtimeDir: File
    private lateinit var server: DeviceAgentServer

    private val tokenFile: File
        get() = File(runtimeDir, "home/runner/${DeviceAgentServer.TOKEN_FILE_NAME}")

    @Before fun startServer() {
        runtimeDir = temp.newFolder("runtime")
        // Port 0 keeps parallel test runs from colliding on the fixed port.
        server = DeviceAgentServer(runtimeDir, requestedPort = 0) { """{"stub":true}""" }
        server.start()
    }

    @After fun stopServer() {
        server.stop()
    }

    private fun request(
        path: String,
        token: String? = null,
        method: String = "GET",
        body: String? = null,
        declaredLength: Int? = null,
    ): Pair<Int, String> {
        val connection = (URL("${server.url}$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = 5_000
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (body != null || declaredLength != null) {
                doOutput = true
                setRequestProperty("Content-Length", (declaredLength ?: body!!.length).toString())
            }
        }
        body?.let { connection.outputStream.use { out -> out.write(it.toByteArray()) } }
        val status = connection.responseCode
        val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        return status to text
    }

    @Test fun refusesEverythingWhileNoJobIsRunning() {
        val (status, body) = request("/v1/capabilities")
        assertEquals(403, status)
        assertTrue(body.contains("only available while a job is running"))
        assertFalse("no token should exist while idle", tokenFile.exists())
    }

    @Test fun issuesTokenForTheJobAndRevokesItAfterwards() {
        server.onJobActive(true)
        assertTrue(tokenFile.isFile)
        val token = tokenFile.readText()
        assertEquals(48, token.length)

        assertEquals(200, request("/v1/capabilities", token).first)

        server.onJobActive(false)
        assertFalse("token must be revoked when the job ends", tokenFile.exists())
        assertEquals(403, request("/v1/capabilities", token).first)
    }

    @Test fun rotatesTheTokenBetweenJobs() {
        server.onJobActive(true)
        val first = tokenFile.readText()
        server.onJobActive(false)
        server.onJobActive(true)
        val second = tokenFile.readText()

        assertNotEquals("each job must get a fresh token", first, second)
        assertEquals(401, request("/v1/capabilities", first).first)
        assertEquals(200, request("/v1/capabilities", second).first)
    }

    @Test fun rejectsWrongOrMissingToken() {
        server.onJobActive(true)
        val valid = tokenFile.readText()

        assertEquals(401, request("/v1/capabilities").first)
        assertEquals(401, request("/v1/capabilities", "wrong").first)
        // Same length as a real token, so the constant-time compare is exercised.
        assertEquals(401, request("/v1/capabilities", "f".repeat(valid.length)).first)
    }

    @Test fun rejectsUnknownEndpointsEvenWhenAuthorized() {
        server.onJobActive(true)
        val token = tokenFile.readText()
        assertEquals(404, request("/v1/../secrets", token).first)
        assertEquals(404, request("/v1/tests/nnapi", token, method = "GET").first)
    }

    @Test fun rejectsOversizedBodies() {
        server.onJobActive(true)
        val token = tokenFile.readText()
        val (status, body) = request(
            "/v1/tests/conv", token, method = "POST",
            body = "x".repeat(32 * 1024),
        )
        assertEquals(413, status)
        assertTrue(body.contains("too large"))
    }

    @Test fun malformedJsonBodyIsRejected() {
        server.onJobActive(true)
        val token = tokenFile.readText()
        val (status, _) = request("/v1/tests/conv", token, method = "POST", body = "{nope")
        assertEquals(400, status)
    }

    @Test fun survivesConcurrentRequests() {
        server.onJobActive(true)
        val token = tokenFile.readText()
        val results = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val threads = (1..12).map {
            Thread { results += runCatching { request("/v1/capabilities", token).first }.getOrDefault(-1) }
        }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)

        // The pool is bounded, so some connections may be dropped, but the
        // agent must stay alive and keep serving.
        assertTrue("expected some successful responses", results.count { it == 200 } > 0)
        assertEquals(200, request("/v1/capabilities", token).first)
    }
}
