package io.github.m96chan.droidrunner.npu

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.json.JSONObject
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

    private val qnnCalls = mutableListOf<String>()

    @Before fun startServer() {
        qnnCalls.clear()
        runtimeDir = temp.newFolder("runtime")
        // Port 0 keeps parallel test runs from colliding on the fixed port.
        server = DeviceAgentServer(
            runtimeDir,
            requestedPort = 0,
            qnnModel = { model, backend, iterations, inputs, outputTarget, keepTimings ->
                qnnCalls += "${model.name}/$backend/$iterations" +
                    inputs.joinToString("") { "/in:${it.name}" } +
                    (outputTarget?.let { "/out:${it.asJobSeesIt}" } ?: "") +
                    (if (keepTimings) "/timings" else "")
                """{"ok":true,"backend":"$backend"}"""
            },
        ) { """{"stub":true}""" }
        server.start()
    }

    @After fun stopServer() {
        server.stop()
    }

    private fun startJobWithToken(): String {
        server.onJobActive(true)
        return tokenFile.readText()
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
                // Bytes, not characters — the distinction #173 is about, and
                // this helper had it wrong too, so a Japanese body would have
                // been announced short by the test itself.
                setRequestProperty(
                    "Content-Length",
                    (declaredLength ?: body!!.toByteArray(Charsets.UTF_8).size).toString(),
                )
            }
        }
        body?.let { connection.outputStream.use { out -> out.write(it.toByteArray()) } }
        val status = connection.responseCode
        val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        return status to text
    }

    @Test fun aBodyWithNonAsciiCharactersIsAnsweredRatherThanTimedOut() {
        // Content-Length counts bytes and the reader counted characters, so a
        // UTF-8 body waited for data the client had already finished sending,
        // until the socket timed out and closed with no reply at all (#173).
        // A Japanese model filename is how somebody meets this.
        val token = startJobWithToken()

        // Named on disk as well, so a reply that finds it proves the bytes
        // arrived unchanged rather than merely that something was answered.
        File(runtimeDir, "home/runner").mkdirs()
        File(runtimeDir, "home/runner/モデル.tflite").writeText("x")

        // The characters are literal, not `\u` escapes. A raw string does not
        // process escapes, so `\u30e2` in one travels as seven ASCII bytes and
        // JSON un-escapes it at the far end — which exercises JSON, not the
        // byte counting this is about. The first version of this test did that
        // and passed against the broken reader.
        val (status, body) = request(
            "/v1/tests/model", token, "POST",
            """{"path":"/home/runner/モデル.tflite","device":"qnn-htp"}""",
        )

        assertEquals("answered rather than timed out: $body", 200, status)
        assertTrue("the stub should have seen the file: $qnnCalls", qnnCalls.any { it.contains("モデル") })
    }

    @Test fun aSupplementaryCharacterIsCountedInBytesToo() {
        // Outside the BMP: two chars in a Java string, four bytes in UTF-8.
        val token = startJobWithToken()

        val (status, _) = request(
            "/v1/tests/model", token, "POST",
            """{"path":"/home/runner/🚀.tflite"}""",
        )

        assertEquals(400, status)
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

    @Test fun aQnnDeviceGoesToQualcommsRuntimeAndNotToNnapi() {
        // On these phones NNAPI reaches only nnapi-reference, the CPU. A job
        // asking for the Hexagon and quietly getting a CPU number is the whole
        // failure this route exists to avoid.
        val token = startJobWithToken()
        val model = File(runtimeDir, "home/runner/model.tflite").apply {
            parentFile!!.mkdirs()
            writeText("not really a model")
        }

        val (status, body) = request(
            "/v1/tests/model",
            token,
            method = "POST",
            body = """{"path":"/home/runner/model.tflite","device":"qnn-htp","iterations":7}""",
        )

        assertEquals(200, status)
        assertEquals(listOf("model.tflite/htp/7"), qnnCalls)
        assertTrue(body.contains(""""backend":"htp""""))
    }

    @Test fun aMisspeltQnnDeviceIsRefusedRatherThanSentToNnapi() {
        val token = startJobWithToken()
        val model = File(runtimeDir, "home/runner/model.tflite").apply {
            parentFile!!.mkdirs()
            writeText("not really a model")
        }

        val (status, body) = request(
            "/v1/tests/model",
            token,
            method = "POST",
            body = """{"path":"/home/runner/model.tflite","device":"qnn-hpt"}""",
        )

        assertEquals(400, status)
        assertTrue(body.contains("qnn-htp"))
        assertEquals(emptyList<String>(), qnnCalls)
    }

}

class ResultContractOverHttpTest {

    @Rule @JvmField val temp = TemporaryFolder()

    private lateinit var runtimeDir: File
    private lateinit var server: DeviceAgentServer

    @Before fun start() {
        runtimeDir = temp.newFolder("runtime")
        server = DeviceAgentServer(runtimeDir, requestedPort = 0) { """{"stub":true}""" }
        server.start()
        server.onJobActive(true)
    }

    @After fun stop() = server.stop()

    private fun get(path: String, token: String?): Pair<Int, String> {
        val connection = (URL("${server.url}$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
        val status = connection.responseCode
        val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        return status to text
    }

    @Test fun a200CarriesTheSchemaEvenWhenTheHandlerDidNot() {
        val token = File(runtimeDir, "home/runner/.droidrunner-agent-token").readText()

        val (status, body) = get("/v1/capabilities", token)

        assertEquals(200, status)
        assertEquals(1, JSONObject(body).getInt("schema"))
        assertTrue(JSONObject(body).getBoolean("stub"))
    }

    @Test fun aRefusalCarriesACodeAConsumerCanBranchOn() {
        // Reading English to decide whether to abandon a sweep is exactly what
        // the contract exists to stop.
        val (status, body) = get("/v1/capabilities", "wrong-token")

        assertEquals(401, status)
        val parsed = JSONObject(body)
        assertEquals(1, parsed.getInt("schema"))
        assertFalse(parsed.getBoolean("ok"))
        assertEquals("invalid-request", parsed.getString("code"))
    }
}

class BatchOverHttpTest {

    @Rule @JvmField val temp = TemporaryFolder()

    private lateinit var runtimeDir: File
    private lateinit var server: DeviceAgentServer

    private val ran = mutableListOf<String>()

    @Before fun start() {
        ran.clear()
        runtimeDir = temp.newFolder("runtime")
        File(runtimeDir, "home/runner").mkdirs()
        server = DeviceAgentServer(
            runtimeDir,
            requestedPort = 0,
            // A row can be told to outlast the batch budget, so the timeout
            // path has something to time out on (#174). The list is what proves
            // no later row was executed.
            qnnModel = { model, _, _, _, _, _ ->
                ran += model.name
                // Longer than the smallest budget the contract allows:
                // `budgetMs` is coerced to at least 1000ms, so a shorter
                // sleep never reaches the timeout path at all.
                if (model.name.startsWith("slow")) Thread.sleep(1_500)
                """{"ok":true}"""
            },
        ) { """{"stub":true}""" }
        server.start()
        server.onJobActive(true)
    }

    @After fun stop() = server.stop()

    private fun post(path: String, body: String): Pair<Int, String> {
        val token = File(runtimeDir, "home/runner/.droidrunner-agent-token").readText()
        val connection = (URL("${server.url}$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $token")
            doOutput = true
            setRequestProperty("Content-Length", body.length.toString())
        }
        connection.outputStream.use { it.write(body.toByteArray()) }
        val status = connection.responseCode
        val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        return status to text
    }

    @Test fun aFailingRowNeverEndsTheSweep() {
        // Every entry here fails — there is no TFLite runtime in a JVM test —
        // and that is the point: the array must still be the manifest, in
        // order, with every row present.
        val (status, body) = post(
            "/v1/tests/models",
            """{"models":[
                 {"id":"a","path":"/home/runner/missing-a.tflite"},
                 {"id":"b"},
                 {"id":"c","path":"/home/runner/missing-c.tflite"}
               ]}""",
        )

        assertEquals(200, status)
        val parsed = JSONObject(body)
        assertEquals(1, parsed.getInt("schema"))
        val results = parsed.getJSONArray("results")
        assertEquals(3, results.length())
        assertEquals(listOf("a", "b", "c"), (0 until 3).map { results.getJSONObject(it).getString("id") })
        (0 until 3).forEach { assertFalse(results.getJSONObject(it).getBoolean("ok")) }
    }

    @Test fun aRowThatRanOutOfTimeDoesNotTakeTheRestOfTheManifestWithIt() {
        // The response promises one entry back per entry sent, in order,
        // including rows that were never attempted. The timeout path broke out
        // of the loop instead, dropping every row after the one that ran long
        // (#174) — while the branch directly above it, for a deadline that had
        // already passed, got this right.
        File(runtimeDir, "home/runner/slow.tflite").writeText("x")
        File(runtimeDir, "home/runner/after.tflite").writeText("x")

        val (status, body) = post(
            "/v1/tests/models",
            """{"budgetMs":1000,"models":[
                 {"id":"slow","path":"/home/runner/slow.tflite","device":"qnn-htp"},
                 {"id":"after","path":"/home/runner/after.tflite","device":"qnn-htp"},
                 {"id":"malformed"}
               ]}""",
        )

        assertEquals(200, status)
        val parsed = JSONObject(body)
        val results = parsed.getJSONArray("results")
        assertEquals(
            listOf("slow", "after", "malformed"),
            (0 until results.length()).map { results.getJSONObject(it).getString("id") },
        )
        assertEquals("slow", parsed.getString("stoppedAt"))
        // And the row after it was never handed to a model.
        assertEquals(listOf("slow.tflite"), ran)
    }

    @Test fun anEmptyManifestIsARequestError() {
        val (status, body) = post("/v1/tests/models", """{"models":[]}""")

        assertEquals(400, status)
        assertEquals("invalid-request", JSONObject(body).getString("code"))
    }
}
