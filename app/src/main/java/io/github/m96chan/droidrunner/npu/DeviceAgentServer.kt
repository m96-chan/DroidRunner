package io.github.m96chan.droidrunner.npu

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Device Agent (issue #4): a loopback-only HTTP API that lets CI jobs reach
 * Android-side hardware (NNAPI) they cannot touch from inside PRoot.
 *
 * Security posture: loopback is shared with every app on the device, so the
 * socket alone is not a boundary. Access is therefore gated twice — the agent
 * only answers while a job is actually running, and each job gets a fresh
 * capability token delivered through the app-private runtime directory (which
 * other apps cannot read), not through the environment.
 *
 * That gate is checked only after the request has been read, which is why the
 * reading itself is bounded in every dimension — bytes per line, bytes per
 * head, and wall-clock time (issue #190). An unauthenticated neighbour app
 * gets to spend those and nothing else.
 */
internal class DeviceAgentServer(
    private val runtimeDir: File,
    private val requestedPort: Int = PORT,
    /**
     * How long a whole request has to arrive, measured from the moment the
     * connection was accepted.
     *
     * Separate from [SOCKET_TIMEOUT_MS], which restarts on every byte read and
     * so bounds nothing at all against a client that drips: one byte every
     * fourteen seconds held a worker indefinitely, and two such connections
     * held every worker there is (#190). A parameter because a test that
     * proves it should not have to wait out the real one.
     */
    private val requestDeadlineMs: Long = REQUEST_DEADLINE_MS,
    /**
     * Runs a model on Qualcomm's accelerator, or null on a device with none.
     * A lambda rather than a dependency so this server keeps no Android types
     * and stays testable on the JVM (issue #82). Ahead of [capabilitiesJson]
     * so that one stays the trailing lambda callers already write.
     */
    private val qnnModel: ((File, String, Int, List<File>, TensorIo.Target?, Boolean) -> String)? = null,
    /**
     * What the phone was doing, sampled at each end of a timing loop (#98).
     * A lambda for the same reason as [qnnModel]: no Android types here.
     */
    private val conditions: (() -> DeviceConditions)? = null,
    private val capabilitiesJson: () -> String,
) {
    var port: Int = requestedPort
        private set
    val url: String get() = "http://127.0.0.1:$port"

    /** Token file as seen from inside the guest. */
    private val tokenFile = File(runtimeDir, "home/runner/$TOKEN_FILE_NAME")

    @Volatile
    private var currentToken: String? = null

    private var serverSocket: ServerSocket? = null

    /**
     * Never longer than the deadline for the whole request: a client that goes
     * silent should be answered when [requestDeadlineMs] is up rather than sit
     * in a blocking read until the per-read timeout decides otherwise.
     */
    private val readTimeoutMs: Int =
        minOf(SOCKET_TIMEOUT_MS.toLong(), requestDeadlineMs).coerceAtLeast(1L).toInt()

    private val workers: ThreadPoolExecutor = ThreadPoolExecutor(
        1, MAX_WORKERS, 30, TimeUnit.SECONDS, LinkedBlockingQueue(MAX_QUEUED),
        Executors.defaultThreadFactory(),
        // Not DiscardPolicy (#199). Discarding the task discarded the only
        // reference to the socket, so an overloaded agent answered nothing and
        // closed nothing: the caller waited out its own timeout with no status
        // to branch on, and we leaked a descriptor per drop until accept()
        // failed and the agent stopped listening for the life of the process.
        RejectedExecutionHandler { task, _ ->
            (task as? Connection)?.refuse(
                "the device agent is busy: every worker and every queued slot is taken",
            )
        },
    )

    /**
     * One accepted connection, as something the pool can hand back (#199).
     *
     * The socket used to be captured by a lambda that closed it with `use`,
     * which meant only the task that *ran* ever closed anything. Carrying it
     * on the task instead means whoever ends up holding it — a worker, the
     * rejection handler, or [stop] — can answer on it and close it.
     */
    private inner class Connection(private val client: Socket) : Runnable {
        override fun run() {
            runCatching { client.use(::handle) }
                .onFailure { logError("device agent request failed", it) }
        }

        /** Says that nobody is going to run this, and closes. */
        fun refuse(why: String) {
            runCatching {
                client.use {
                    writeResponse(it, 503, ResultContract.error(ResultContract.Code.FAILED, why))
                    drainArrived(it)
                }
            }.onFailure { logError("device agent could not refuse a connection", it) }
        }
    }

    fun start() {
        val socket = ServerSocket()
        socket.reuseAddress = true
        // Explicit IPv4 loopback: getLoopbackAddress() resolves to ::1 on some
        // devices, unreachable from the guest's http://127.0.0.1 URL.
        socket.bind(
            InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), requestedPort),
            BACKLOG,
        )
        port = socket.localPort
        serverSocket = socket
        clearToken()
        thread(name = "device-agent", isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }
                    // Leaving this loop means the agent is gone until the
                    // service restarts, and it used to go without a word —
                    // usually because our own leaked descriptors had run the
                    // process out of them (#199).
                    .onFailure {
                        if (!socket.isClosed) {
                            logError("device agent stopped accepting connections", it)
                        }
                    }
                    .getOrNull() ?: break
                client.soTimeout = readTimeoutMs
                workers.execute(Connection(client))
            }
        }
        logInfo("device agent listening on $url")
    }

    fun stop() {
        clearToken()
        runCatching { serverSocket?.close() }
        serverSocket = null
        // shutdownNow hands back the tasks it dropped, and each one still owns
        // a socket with a client waiting on the other end of it (#199).
        workers.shutdownNow().forEach {
            (it as? Connection)?.refuse("the device agent is stopping")
        }
    }

    /**
     * Issues a fresh capability token for a starting job and writes it to the
     * app-private runtime dir; revokes it when the job finishes.
     */
    fun onJobActive(active: Boolean) {
        if (!active) {
            clearToken()
            return
        }
        val token = SecureRandom().let { rng ->
            ByteArray(24).also(rng::nextBytes).joinToString("") { "%02x".format(it) }
        }
        currentToken = token
        runCatching {
            tokenFile.parentFile?.mkdirs()
            tokenFile.writeText(token)
            tokenFile.setReadable(true, false)
        }.onFailure { logError("cannot publish agent token", it) }
    }

    private fun clearToken() {
        currentToken = null
        runCatching { if (tokenFile.exists()) tokenFile.delete() }
    }

    /**
     * A request this agent stopped reading, and the status that says why.
     *
     * Thrown rather than returned because it can happen several frames down,
     * in the middle of a header line, and every one of those places has the
     * same answer: say so, drain, close (#190).
     */
    private class Refused(val status: Int, val why: String) : Exception(why)

    /**
     * What one request may spend on being read: head bytes, and wall time.
     *
     * Every dimension was bounded except the one an attacker picks. The body
     * was capped by `Content-Length` and the headers by their count, so the
     * way in was a single header line that never ends — from any app on the
     * phone, before the token is so much as looked at (#190).
     */
    private class Budget(private val until: Long) {
        private var head = 0

        fun spendHead(bytes: Int) {
            head += bytes
            if (head > MAX_HEAD_BYTES) {
                throw Refused(431, "the request head is larger than $MAX_HEAD_BYTES bytes")
            }
        }

        fun checkTime() {
            if (System.currentTimeMillis() > until) {
                throw Refused(408, "the request did not finish arriving in time")
            }
        }
    }

    /** A request that was read whole, with only the parts this agent uses. */
    private class Request(
        val method: String,
        val path: String,
        val presentedToken: String?,
        val body: String,
    )

    /**
     * One header line, read as bytes (issue #173) and bounded (issue #190).
     *
     * A `BufferedReader` over the socket decodes as it goes and reads ahead, so
     * body bytes end up inside the reader's char buffer where a byte-counted
     * read cannot reach them. Headers and body therefore share one byte stream.
     */
    private fun readHeaderLine(input: java.io.InputStream, budget: Budget): String? {
        val line = java.io.ByteArrayOutputStream()
        while (true) {
            budget.checkTime()
            val c = input.read()
            if (c < 0) return if (line.size() == 0) null else line.toString("UTF-8")
            budget.spendHead(1)
            if (c == '\n'.code) break
            if (c != '\r'.code) {
                if (line.size() >= MAX_LINE_BYTES) {
                    throw Refused(431, "a header line ran past $MAX_LINE_BYTES bytes")
                }
                line.write(c)
            }
        }
        return line.toString("UTF-8")
    }

    /** Null when the peer closed before saying anything this can act on. */
    private fun readRequest(input: java.io.InputStream, budget: Budget): Request? {
        val requestLine = readHeaderLine(input, budget) ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null

        var presentedToken: String? = null
        var contentLength = 0
        var headerCount = 0
        while (headerCount++ < MAX_HEADERS) {
            val header = readHeaderLine(input, budget) ?: break
            if (header.isEmpty()) break
            val lower = header.lowercase()
            if (lower.startsWith("authorization:")) {
                presentedToken = header.substringAfter(':').trim().removePrefix("Bearer ").trim()
            }
            if (lower.startsWith("content-length:")) {
                contentLength = header.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }
        if (contentLength > MAX_BODY_BYTES) throw Refused(413, "request body too large")

        val body = if (contentLength > 0) {
            // Content-Length counts bytes. Reading that many *characters* meant
            // a UTF-8 body with any multibyte character — a Japanese model
            // filename, say — waited for data the client had already finished
            // sending, until the socket timed out and the connection closed
            // without a reply (#173).
            val bytes = ByteArray(contentLength)
            var read = 0
            while (read < bytes.size) {
                // The clock covers the body too: it is capped at 16 KB, which
                // says nothing about how slowly those 16 KB may arrive.
                budget.checkTime()
                val n = input.read(bytes, read, bytes.size - read)
                if (n < 0) break
                read += n
            }
            String(bytes, 0, read, Charsets.UTF_8)
        } else ""

        return Request(parts[0], parts[1], presentedToken, body)
    }

    private fun handle(client: Socket) {
        val input = java.io.BufferedInputStream(client.getInputStream())
        val budget = Budget(System.currentTimeMillis() + requestDeadlineMs)
        val request = try {
            readRequest(input, budget) ?: return
        } catch (refused: Refused) {
            writeResponse(
                client,
                refused.status,
                ResultContract.error(ResultContract.Code.INVALID_REQUEST, refused.why),
            )
            drainQuietly(client, input)
            return
        } catch (silent: java.net.SocketTimeoutException) {
            // The same deadline, expressed as a read that never returned:
            // nothing arrived for as long as the whole request was allowed.
            writeResponse(
                client,
                408,
                ResultContract.error(
                    ResultContract.Code.INVALID_REQUEST,
                    "the request did not finish arriving in time",
                ),
            )
            return
        }

        val expected = currentToken
        val response: Pair<Int, String> = when {
            expected == null -> 403 to ResultContract.error(
                ResultContract.Code.INVALID_REQUEST,
                "device agent is only available while a job is running",
            )
            request.presentedToken == null ||
                !constantTimeEquals(request.presentedToken, expected) ->
                401 to ResultContract.error(
                    ResultContract.Code.INVALID_REQUEST,
                    "missing or invalid capability token",
                )
            request.method == "GET" && request.path == "/v1/capabilities" ->
                200 to capabilitiesJson()
            request.method == "POST" && request.path == "/v1/tests/nnapi" -> nnapiTest(request.body)
            request.method == "POST" && request.path == "/v1/tests/conv" -> convTest(request.body)
            request.method == "POST" && request.path == "/v1/tests/model" -> modelTest(request.body)
            request.method == "POST" && request.path == "/v1/tests/models" -> batchTest(request.body)
            else -> 404 to ResultContract.error(
                ResultContract.Code.INVALID_REQUEST,
                "unknown endpoint",
            )
        }
        writeResponse(client, response.first, response.second)
    }

    /**
     * Reads and throws away whatever is still coming, briefly.
     *
     * Closing a socket that still has unread bytes in its receive queue is an
     * abortive close: the kernel sends RST, and on the other side that throws
     * away the reply we just wrote along with it. So a refusal issued halfway
     * through a request is followed by a bounded read — otherwise the status
     * we went to the trouble of producing is the one thing the client never
     * gets to see.
     */
    private fun drainQuietly(client: Socket, input: java.io.InputStream) {
        runCatching {
            client.soTimeout = DRAIN_MS
            val scratch = ByteArray(4096)
            var left = DRAIN_BYTES
            while (left > 0) {
                val n = input.read(scratch, 0, minOf(scratch.size, left))
                if (n < 0) break
                left -= n
            }
        }
    }

    /**
     * The same courtesy for a connection nobody ever read: the request is
     * already sitting in the receive queue, so closing on top of it resets the
     * connection and the 503 goes with it.
     *
     * Only what has already arrived, and never a wait — this runs on the
     * accept thread when the pool is full, and on [stop], and neither can
     * afford to sit out a straggler.
     */
    private fun drainArrived(client: Socket) {
        runCatching {
            val input = client.getInputStream()
            val scratch = ByteArray(4096)
            var left = DRAIN_BYTES
            while (left > 0 && input.available() > 0) {
                val n = input.read(scratch, 0, minOf(scratch.size, minOf(left, input.available())))
                if (n < 0) break
                left -= n
            }
        }
    }

    /** Logging that degrades to stderr off-device (JVM unit tests). */
    private fun logError(message: String, error: Throwable) {
        runCatching { android.util.Log.e("DroidRunner", message, error) }
            .onFailure { System.err.println("$message: $error") }
    }

    private fun logInfo(message: String) {
        runCatching { android.util.Log.d("DroidRunner", message) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    /**
     * These two probes call NNAPI directly rather than through TFLite, so
     * there is no interpreter to attach a second delegate to (#159). Said here
     * rather than letting the name fall through to NNAPI and come back
     * `device_not_found`, which is true and explains nothing.
     */
    private fun refuseMultiDevice(request: JSONObject): Pair<Int, String>? {
        val device = request.optString("device").takeIf { it.isNotBlank() } ?: return null
        if (!device.contains(DeviceRequest.SEPARATOR)) return null
        return 400 to ResultContract.error(
            ResultContract.Code.UNKNOWN_DEVICE,
            "'$device' names two accelerators, and these built-in probes run NNAPI " +
                "directly rather than through TFLite — there is no interpreter here to " +
                "attach two delegates to. Use `test model` for a partitioned run.",
        )
    }

    private fun nnapiTest(body: String): Pair<Int, String> {
        val request = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrElse {
            return 400 to """{"error":"invalid JSON body"}"""
        }
        refuseMultiDevice(request)?.let { return it }
        return 200 to NnapiProbe.benchmark(
            request.optString("device").takeIf { it.isNotBlank() },
            request.optInt("iterations", 100),
        )
    }

    private fun convTest(body: String): Pair<Int, String> {
        val request = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrElse {
            return 400 to """{"error":"invalid JSON body"}"""
        }
        refuseMultiDevice(request)?.let { return it }
        return 200 to NnapiProbe.conv(
            request.optString("device").takeIf { it.isNotBlank() },
            request.optInt("iterations", 50),
            request.optInt("size", 64),
            request.optInt("channels", 16),
            request.optInt("filters", 16),
        )
    }

    /**
     * Runs a model the job already has on disk. The path is the job's own view
     * of it; [GuestPath] proves it stays inside the runner home before the file
     * is opened, since the path comes from untrusted workflow code.
     */
    private fun modelTest(body: String): Pair<Int, String> {
        val request = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrElse {
            return 400 to ResultContract.error(ResultContract.Code.INVALID_REQUEST, "invalid JSON body")
        }
        val path = request.optString("path").takeIf { it.isNotBlank() }
            ?: return 400 to ResultContract.error(
                ResultContract.Code.INVALID_REQUEST,
                "path is required",
            )
        val model = GuestPath.resolve(runtimeDir, path)
            ?: return 400 to ResultContract.error(
                ResultContract.Code.INVALID_REQUEST,
                "model must be a file under /home/runner (the job workspace)",
            )
        val device = request.optString("device").takeIf { it.isNotBlank() }
        // Experiments a caller has to name before they happen. Absent, every
        // path below is the one it has always been (#159).
        val features = request.optJSONArray("features")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
        }?.toSet().orEmpty()
        val deviceRequest = DeviceRequest.parse(device, features)
        if (deviceRequest is DeviceRequest.Parsed.Refused) {
            return 400 to ResultContract.error(deviceRequest.code, deviceRequest.reason)
        }
        val multiDevices = (deviceRequest as? DeviceRequest.Parsed.Several)?.devices.orEmpty()
        val iterations = request.optInt("iterations", 50)
        // Off by default: 500 iterations is 500 numbers, and most callers want
        // the percentiles rather than the loop (#98).
        val keepTimings = request.optBoolean("timings")
        // The delegate's own words, for a caller who would rather read them
        // than trust our reading of them (#128).
        val keepDelegateLog = request.optBoolean("delegateLog")

        // Job code chose these paths, so each one is proven to stay inside the
        // runner's home before anything is opened or written (issue #92).
        val requested = request.optJSONArray("inputs")
        val inputs = mutableListOf<File>()
        for (index in 0 until (requested?.length() ?: 0)) {
            val path = requested!!.optString(index)
            inputs += GuestPath.resolve(runtimeDir, path)
                ?: return 400 to ResultContract.error(
                    ResultContract.Code.INVALID_REQUEST,
                    "input $index is not a readable file under /home/runner: $path",
                )
        }
        val outputTarget = request.optString("outputDir").takeIf { it.isNotBlank() }?.let { path ->
            val directory = GuestPath.resolveDirectory(runtimeDir, path)
                ?: return 400 to ResultContract.error(
                    ResultContract.Code.INVALID_REQUEST,
                    "outputDir must be a directory under /home/runner: $path",
                )
            // Both frames: this process writes to the first, the job reads the
            // second, and only the second belongs in a reply.
            TensorIo.Target(directory, path)
        }

        // "qnn-htp" and friends are not NNAPI device names; they mean the
        // Qualcomm delegate in its own process, which NNAPI cannot reach. A
        // list form never reaches here: DeviceRequest has already refused it
        // for naming a backend that cannot share an address space.
        val backend = runCatching { QnnBackend.of(device.takeIf { multiDevices.isEmpty() }) }.getOrElse { unknown ->
            return 400 to ResultContract.error(
                ResultContract.Code.UNKNOWN_DEVICE,
                unknown.message ?: "unknown QNN backend",
            )
        }
        backend?.let {
            val run = qnnModel
                ?: return 400 to ResultContract.error(
                    ResultContract.Code.NOT_INSTALLED,
                    "this device has no Qualcomm accelerator runtime",
                )
            return 200 to run(model, it, iterations, inputs, outputTarget, keepTimings)
        }
        return 200 to ModelRunner.run(
            model = model,
            deviceName = device,
            multiDevices = multiDevices,
            iterations = iterations,
            inputs = inputs,
            outputTarget = outputTarget,
            baseline = request.optBoolean("baseline"),
            conditions = conditions,
            keepTimings = keepTimings,
            keepDelegateLog = keepDelegateLog,
            // App-private and outside the guest's home: a diagnostic scratch
            // file is not something a job should find, or be able to write.
            diagnosticsDir = runtimeDir.parentFile,
        )
    }

    /**
     * A whole sweep in one request (issue #94).
     *
     * Every row is attempted and every row comes back, in the order it was
     * sent: a sweep is largely made of rejections and each one is the data,
     * so a failing entry must never end the batch. What does end it is the
     * clock — a driver that will not return would otherwise cost the caller
     * everything collected before it, so the budget is checked between rows
     * and each row is awaited with what is left of it.
     */
    private fun batchTest(body: String): Pair<Int, String> {
        val request = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrElse {
            return 400 to ResultContract.error(
                ResultContract.Code.INVALID_REQUEST,
                "invalid JSON body",
            )
        }
        val entries = BatchRequest.entries(request)
        if (entries.isEmpty()) {
            return 400 to ResultContract.error(
                ResultContract.Code.INVALID_REQUEST,
                "models must be a non-empty array",
            )
        }

        val deadline = System.currentTimeMillis() + BatchRequest.budgetMs(request)
        val results = mutableListOf<String>()
        var stoppedAt: String? = null

        // One row at a time, on a thread this can walk away from. A hung
        // vendor call cannot be interrupted, so the thread is abandoned rather
        // than waited on; leaking one is the price of answering at all.
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            for (entry in entries) {
                if (entry.rejection != null) {
                    results += BatchRequest.skipped(entry, entry.rejection)
                    continue
                }
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) {
                    stoppedAt = stoppedAt ?: entry.id
                    results += BatchRequest.skipped(entry, "the batch ran out of time before this")
                    continue
                }
                val task = worker.submit<String> { runOne(entry) }
                val answer = try {
                    task.get(remaining, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (ranLong: java.util.concurrent.TimeoutException) {
                    // The only exception here that means the clock. It used to
                    // share a `runCatching` with every other one, so a row that
                    // threw was reported as a timing problem and stamped
                    // `budgetExhausted` on a sweep that finished early (#192).
                    task.cancel(true)
                    stoppedAt = stoppedAt ?: entry.id
                    BatchRequest.skipped(entry, "took longer than the batch had left")
                } catch (thrown: Throwable) {
                    // This row's own failure, reported in this row. The sweep
                    // carries on because the budget is untouched and the next
                    // model is a different model — an OutOfMemoryError on a
                    // large graph says nothing about the small one after it.
                    task.cancel(true)
                    BatchRequest.threw(
                        entry,
                        (thrown as? java.util.concurrent.ExecutionException)?.cause ?: thrown,
                    )
                }
                results += BatchRequest.identify(answer, entry)
                // `continue`, not `break`: breaking dropped every remaining row
                // from a response that promises one entry back per entry sent,
                // in order (#174). Carrying on costs nothing — the deadline has
                // passed, so the guard above turns each of the rest into a
                // skipped row without running a model.
                if (stoppedAt != null) continue
            }
        } finally {
            worker.shutdownNow()
        }
        return 200 to BatchRequest.response(results, stoppedAt)
    }

    /** One manifest row, reusing the single-model path so the shapes match. */
    private fun runOne(entry: BatchRequest.Entry): String {
        val request = JSONObject()
            .put("path", entry.path)
            .put("iterations", entry.iterations)
            .apply {
                entry.device?.let { put("device", it) }
                entry.outputDir?.let { put("outputDir", it) }
                if (entry.keepTimings) put("timings", true)
                if (entry.keepDelegateLog) put("delegateLog", true)
                if (entry.inputs.isNotEmpty()) put("inputs", JSONArray(entry.inputs))
            }
        return modelTest(request.toString()).second
    }

    private fun writeResponse(client: Socket, status: Int, json: String) {
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            408 -> "Request Timeout"
            413 -> "Payload Too Large"
            431 -> "Request Header Fields Too Large"
            503 -> "Service Unavailable"
            else -> "Not Found"
        }
        val payload = ResultContract.stamp(json).toByteArray()
        runCatching {
            client.getOutputStream().apply {
                write(
                    ("HTTP/1.1 $status $reason\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${payload.size}\r\n" +
                        "Connection: close\r\n\r\n").toByteArray(),
                )
                write(payload)
                flush()
            }
        }
    }

    companion object {
        const val TOKEN_FILE_NAME = ".droidrunner-agent-token"
        private const val PORT = 41999
        private const val BACKLOG = 8
        private const val MAX_WORKERS = 2
        private const val MAX_QUEUED = 8
        private const val MAX_HEADERS = 40
        private const val MAX_BODY_BYTES = 16 * 1024
        private const val SOCKET_TIMEOUT_MS = 15_000

        /**
         * A few KB is more than any header this API has a use for, and the
         * block cap is what actually bounds the head: forty headers of eight
         * KB each would be 320 KB of memory bought with no token at all (#190).
         *
         * One status for all of them — 431 rather than 414 for the request
         * line — because nothing branches on the difference and a caller that
         * sent a head this size has the same thing to fix either way.
         */
        private const val MAX_LINE_BYTES = 8 * 1024
        private const val MAX_HEAD_BYTES = 32 * 1024

        /** Generous for loopback, and finite, which is the point (#190). */
        private const val REQUEST_DEADLINE_MS = 20_000L

        /** How far a refusal will read on, rather than close abortively. */
        private const val DRAIN_MS = 200
        private const val DRAIN_BYTES = 64 * 1024
    }
}
