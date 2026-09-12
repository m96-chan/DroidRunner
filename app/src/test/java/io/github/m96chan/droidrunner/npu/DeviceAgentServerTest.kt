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
            // A row can be told to outlast the batch budget, to throw, or to
            // answer in the shape a hand-built failure used to have, so each
            // path has something to happen to it. The list is what proves
            // which rows were executed and which were not.
            qnnModel = { model, _, _, _, _, _ ->
                ran += model.name
                when {
                    // Longer than the smallest budget the contract allows:
                    // `budgetMs` is coerced to at least 1000ms, so a shorter
                    // sleep never reaches the timeout path at all (#174).
                    model.name.startsWith("slow") -> {
                        Thread.sleep(1_500)
                        """{"ok":true}"""
                    }
                    // An OutOfMemoryError on a large graph is the case from
                    // #192; this is the same escape, deliberately raised.
                    model.name.startsWith("boom") ->
                        throw IllegalStateException("the delegate walked off with the arena")
                    // What the not-installed path returned before #200: built
                    // by hand, with neither a schema nor a code.
                    model.name.startsWith("bare") ->
                        """{"ok":false,"error":"the Qualcomm NPU runtime is not installed"}"""
                    else -> """{"ok":true}"""
                }
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
        // A row that really did outlast the budget is the one case that may
        // say so (#192): the clock, and nothing else.
        assertTrue(parsed.getBoolean("budgetExhausted"))
        // And the row after it was never handed to a model.
        assertEquals(listOf("slow.tflite"), ran)
    }

    @Test fun aRowThatThrowsSaysWhatThrewAndLeavesTheBudgetAlone() {
        // Any throwable escaping a row used to arrive as "took longer than the
        // batch had left" and to set `stoppedAt`, which stamped
        // `budgetExhausted` on a sweep that finished well inside its budget.
        // A caller reading that as "the fleet is behind, resubmit" resubmits a
        // sweep that already completed (#192).
        File(runtimeDir, "home/runner/boom.tflite").writeText("x")
        File(runtimeDir, "home/runner/after.tflite").writeText("x")

        val (status, body) = post(
            "/v1/tests/models",
            """{"models":[
                 {"id":"boom","path":"/home/runner/boom.tflite","device":"qnn-htp"},
                 {"id":"after","path":"/home/runner/after.tflite","device":"qnn-htp"}
               ]}""",
        )

        assertEquals(200, status)
        val parsed = JSONObject(body)
        assertFalse("the budget was never approached: $body", parsed.has("budgetExhausted"))
        assertFalse(parsed.has("stoppedAt"))

        val threw = parsed.getJSONArray("results").getJSONObject(0)
        assertFalse(threw.getBoolean("ok"))
        assertEquals("failed", threw.getString("code"))
        // The thrown thing's own words, which are the half that says what
        // actually happened.
        assertTrue("the row must name its cause: $threw", threw.getString("message").contains("arena"))
        assertFalse(
            "a memory problem must not be reported as a timing one: $threw",
            threw.getString("error").contains("longer than the batch"),
        )

        // And the sweep carried on, because the next model is a different one.
        assertTrue(parsed.getJSONArray("results").getJSONObject(1).getBoolean("ok"))
        assertEquals(listOf("boom.tflite", "after.tflite"), ran)
    }

    @Test fun everyNestedRowCarriesTheSchemaTheEnvelopeDoes() {
        // The confusing case #200 describes: the response validates at the top
        // level and its contents do not.
        File(runtimeDir, "home/runner/fine.tflite").writeText("x")

        val (_, body) = post(
            "/v1/tests/models",
            """{"models":[{"id":"fine","path":"/home/runner/fine.tflite","device":"qnn-htp"}]}""",
        )

        val row = JSONObject(body).getJSONArray("results").getJSONObject(0)
        assertTrue(row.getBoolean("ok"))
        assertEquals(ResultContract.SCHEMA, row.getInt("schema"))
    }

    @Test fun aNestedFailureWithoutACodeIsGivenOneOnTheWayOut() {
        // `code` is what a sweep branches on to decide whether to abort, and
        // the contract says it is present on every failure. A row built by
        // hand somewhere below must not be able to take it away (#200).
        File(runtimeDir, "home/runner/bare.tflite").writeText("x")

        val (_, body) = post(
            "/v1/tests/models",
            """{"models":[{"id":"bare","path":"/home/runner/bare.tflite","device":"qnn-htp"}]}""",
        )

        val row = JSONObject(body).getJSONArray("results").getJSONObject(0)
        assertFalse(row.getBoolean("ok"))
        assertEquals(ResultContract.SCHEMA, row.getInt("schema"))
        assertEquals(ResultContract.Code.FAILED, row.getString("code"))
    }

    @Test fun anEmptyManifestIsARequestError() {
        val (status, body) = post("/v1/tests/models", """{"models":[]}""")

        assertEquals(400, status)
        assertEquals("invalid-request", JSONObject(body).getString("code"))
    }
}

/**
 * What an unauthenticated neighbour app may spend before the token is looked
 * at (issue #190).
 *
 * Loopback is shared with every app on the phone, and the class doc says so:
 * the token is the boundary, and everything ahead of it — the request line,
 * the headers, the clock — is reachable by anything installed. Each of these
 * used to be unbounded in one dimension.
 */
class AgentHeadLimitsTest {

    @Rule @JvmField val temp = TemporaryFolder()

    private lateinit var runtimeDir: File
    private lateinit var server: DeviceAgentServer

    @Before fun start() {
        runtimeDir = temp.newFolder("runtime")
        server = DeviceAgentServer(
            runtimeDir,
            requestedPort = 0,
            // The real deadline is twenty seconds, which is a long time to
            // watch a test drip bytes. The cap being *some* finite number is
            // the property under test, not which one.
            requestDeadlineMs = 1_500,
        ) { """{"stub":true}""" }
        server.start()
        server.onJobActive(true)
    }

    @After fun stop() = server.stop()

    private val token: String
        get() = File(runtimeDir, "home/runner/${DeviceAgentServer.TOKEN_FILE_NAME}").readText()

    private fun connect(): java.net.Socket =
        java.net.Socket("127.0.0.1", server.port).apply { soTimeout = 10_000 }

    /** The status line, or "" if the agent said nothing at all. */
    private fun statusOf(socket: java.net.Socket): String =
        socket.getInputStream().bufferedReader().readLine().orEmpty()

    /** Writes what it can; a refusal mid-request may close under our feet. */
    private fun sendQuietly(socket: java.net.Socket, vararg chunks: String) {
        runCatching {
            val out = socket.getOutputStream()
            chunks.forEach { out.write(it.toByteArray()) }
            out.flush()
        }
    }

    /** The agent is still answering, which is the other half of every case. */
    private fun stillServes() {
        connect().use { socket ->
            sendQuietly(
                socket,
                "GET /v1/capabilities HTTP/1.1\r\n",
                "Authorization: Bearer $token\r\n\r\n",
            )
            assertTrue("the worker must be free again", statusOf(socket).contains("200"))
        }
    }

    @Test fun aRequestLineThatNeverEndsIsRefusedRatherThanAccumulated() {
        // No credentials needed: the line is read before the token is looked
        // at, so this is what any app on the phone could do to the runner.
        connect().use { socket ->
            sendQuietly(socket, "GET /", "a".repeat(16 * 1024), " HTTP/1.1\r\n\r\n")
            assertTrue("expected a status, not silence", statusOf(socket).contains("431"))
        }
        stillServes()
    }

    @Test fun aSingleHeaderValueThatNeverEndsIsRefused() {
        connect().use { socket ->
            sendQuietly(
                socket,
                "GET /v1/capabilities HTTP/1.1\r\n",
                "X-Pad: ", "a".repeat(16 * 1024), "\r\n\r\n",
            )
            assertTrue(statusOf(socket).contains("431"))
        }
        stillServes()
    }

    @Test fun aHeaderBlockThatIsLargeWithoutAnyOneLineBeingLargeIsRefused() {
        // Forty headers are allowed and each of these is well under the line
        // cap, so only the cap on the block as a whole catches this one.
        connect().use { socket ->
            sendQuietly(socket, "GET /v1/capabilities HTTP/1.1\r\n")
            repeat(12) { sendQuietly(socket, "X-Pad-$it: ", "a".repeat(4_000), "\r\n") }
            sendQuietly(socket, "\r\n")
            assertTrue(statusOf(socket).contains("431"))
        }
        stillServes()
    }

    @Test fun aClientThatDripsIsDroppedRatherThanHoldingAWorkerForever() {
        // The variant that needs no memory at all: one byte at a time keeps a
        // worker inside the header read for as long as the client cares to
        // keep going, because the per-read timeout restarts on every byte.
        val drip = java.util.concurrent.atomic.AtomicBoolean(true)
        connect().use { socket ->
            sendQuietly(socket, "GET /v1/capabilities HTTP/1.1\r\n", "X-Pad: ")
            val dripping = Thread {
                while (drip.get()) {
                    if (runCatching {
                            socket.getOutputStream().apply { write('a'.code); flush() }
                        }.isFailure
                    ) {
                        return@Thread
                    }
                    Thread.sleep(100)
                }
            }
            dripping.start()
            val started = System.currentTimeMillis()
            val status = statusOf(socket)
            val waited = System.currentTimeMillis() - started
            drip.set(false)
            dripping.join(5_000)

            assertTrue("expected a status, not silence: '$status'", status.contains("408"))
            assertTrue("dropped on the deadline, not on the per-read timeout: $waited ms", waited < 8_000)
        }
        stillServes()
    }
}

/**
 * What a full agent says instead of nothing (issue #199).
 *
 * Two workers and eight queued slots, and a sweep may legitimately hold a
 * worker for an hour. Past that the pool used to discard the task — and with
 * it the only reference to the socket, so the caller waited out its own
 * timeout against a connection that had been accepted and then went quiet,
 * while the descriptor stayed open.
 */
class AgentUnderLoadTest {

    @Rule @JvmField val temp = TemporaryFolder()

    private lateinit var runtimeDir: File
    private lateinit var server: DeviceAgentServer

    /** Holds whichever requests reach a worker, so the queue fills up. */
    private val held = java.util.concurrent.CountDownLatch(1)

    @Before fun start() {
        runtimeDir = temp.newFolder("runtime")
        server = DeviceAgentServer(runtimeDir, requestedPort = 0) {
            held.await(30, java.util.concurrent.TimeUnit.SECONDS)
            """{"stub":true}"""
        }
        server.start()
        server.onJobActive(true)
    }

    @After fun stop() {
        held.countDown()
        server.stop()
    }

    private val token: String
        get() = File(runtimeDir, "home/runner/${DeviceAgentServer.TOKEN_FILE_NAME}").readText()

    /** A connection that has asked for something and is waiting for an answer. */
    private fun asking(): java.net.Socket =
        java.net.Socket("127.0.0.1", server.port).apply {
            soTimeout = 10_000
            getOutputStream().apply {
                write(
                    ("GET /v1/capabilities HTTP/1.1\r\n" +
                        "Authorization: Bearer $token\r\n\r\n").toByteArray(),
                )
                flush()
            }
        }

    private fun statusOf(socket: java.net.Socket): String =
        runCatching { socket.getInputStream().bufferedReader().readLine().orEmpty() }.getOrDefault("")

    /** Two workers plus eight queued slots; the next one has nowhere to go. */
    private fun fillEveryWorkerAndSlot(): List<java.net.Socket> {
        val busy = (1..10).map { asking() }
        // The accept loop has to have taken all ten before the eleventh
        // arrives, or the eleventh is simply the tenth.
        Thread.sleep(500)
        return busy
    }

    @Test fun anAgentWithNowhereToPutARequestSaysSoAndCloses() {
        val busy = fillEveryWorkerAndSlot()
        try {
            java.net.Socket("127.0.0.1", server.port).use { over ->
                over.soTimeout = 10_000
                over.getOutputStream().apply {
                    write(
                        ("GET /v1/capabilities HTTP/1.1\r\n" +
                            "Authorization: Bearer $token\r\n\r\n").toByteArray(),
                    )
                    flush()
                }
                val reader = over.getInputStream().bufferedReader()
                val status = reader.readLine().orEmpty()

                // A status is what `droidrunner-device` can retry on, and what
                // it could not do against silence.
                assertTrue("expected a status, not silence: '$status'", status.contains("503"))
                // And the socket is closed rather than left open: reading on
                // reaches EOF instead of blocking until this test's timeout,
                // which is the leaked descriptor the accept loop died of.
                val rest = runCatching { generateSequence { reader.readLine() }.toList() }
                assertTrue("the agent must close what it refuses", rest.isSuccess)
                assertTrue(
                    "the refusal should say why: ${rest.getOrNull()}",
                    rest.getOrNull().orEmpty().any { it.contains("busy") },
                )
            }
        } finally {
            held.countDown()
            busy.forEach { runCatching { it.close() } }
        }
    }

    @Test fun aRefusedCallerIsToldToRetryRatherThanToFixItsRequest() {
        // #199 gave an overloaded agent a `503` instead of silence. It carried
        // `code: failed`, which the wrapper's exit table maps to 1 — the one
        // status a caller must never retry on, because it means the request
        // was wrong. Nothing was wrong with it, and the phone would have
        // answered a minute later (#233).
        val busy = fillEveryWorkerAndSlot()
        try {
            java.net.Socket("127.0.0.1", server.port).use { over ->
                over.soTimeout = 10_000
                over.getOutputStream().apply {
                    write(
                        ("GET /v1/capabilities HTTP/1.1\r\n" +
                            "Authorization: Bearer $token\r\n\r\n").toByteArray(),
                    )
                    flush()
                }
                val lines = over.getInputStream().bufferedReader()
                    .let { r -> generateSequence { r.readLine() }.toList() }
                val whole = lines.joinToString("\n")

                assertTrue("expected 503, got: ${lines.firstOrNull()}", whole.contains("503"))
                assertTrue(
                    "a busy phone must not be reported as a bad request: $whole",
                    whole.contains("\"code\":\"busy\""),
                )
                // The agent knows how long its queue is; the caller does not.
                assertTrue(
                    "the refusal should say when to come back: $whole",
                    whole.contains("Retry-After: ${DeviceAgentServer.RETRY_AFTER_SECONDS}"),
                )
                // And in the body too, because the wrapper reads the body and
                // not the headers.
                assertTrue(
                    "the body should carry the wait as well: $whole",
                    whole.contains("${DeviceAgentServer.RETRY_AFTER_SECONDS} seconds"),
                )
            }
        } finally {
            held.countDown()
            busy.forEach { runCatching { it.close() } }
        }
    }

    @Test fun aStoppingAgentDoesNotPromiseAWaitThatWillNotHelp() {
        // `stop()` shares the refusal with the overload path, so adding
        // `Retry-After` for #233 told a caller of a *shutting down* agent to
        // come back in thirty seconds — to a closed port. The request really
        // was not attempted and that is not the caller's fault, so the code
        // stays `busy`; what goes is the wait nothing keeps.
        val busy = fillEveryWorkerAndSlot()
        try {
            server.stop()
            val answers = busy.map { socket ->
                runCatching {
                    socket.getInputStream().bufferedReader()
                        .let { r -> generateSequence { r.readLine() }.toList() }
                        .joinToString("\n")
                }.getOrDefault("")
            }.filter { it.contains("503") }

            assertTrue("expected the queue to be answered, got ${answers.size}", answers.isNotEmpty())
            answers.forEach {
                assertTrue("a stopping agent must not name a wait: $it", !it.contains("Retry-After"))
                assertTrue("and should say it is stopping: $it", it.contains("stopping"))
            }
        } finally {
            held.countDown()
            busy.forEach { runCatching { it.close() } }
        }
    }

    @Test fun stoppingAnswersWhatWasStillQueuedRatherThanDroppingIt() {
        val busy = fillEveryWorkerAndSlot()
        try {
            server.stop()

            // Two of the ten reached a worker and were interrupted; the other
            // eight never ran at all, and used to be dropped with their
            // sockets still open.
            val answered = busy.count { statusOf(it).contains("503") }
            assertTrue("expected the queue to be drained, got $answered of 8", answered >= 8)
        } finally {
            held.countDown()
            busy.forEach { runCatching { it.close() } }
        }
    }
}
