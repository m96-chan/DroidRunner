package io.github.m96chan.droidrunner.npu

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
 */
internal class DeviceAgentServer(
    private val runtimeDir: File,
    private val requestedPort: Int = PORT,
    /**
     * Runs a model on Qualcomm's accelerator, or null on a device with none.
     * A lambda rather than a dependency so this server keeps no Android types
     * and stays testable on the JVM (issue #82). Ahead of [capabilitiesJson]
     * so that one stays the trailing lambda callers already write.
     */
    private val qnnModel: ((File, String, Int, List<File>, TensorIo.Target?) -> String)? = null,
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
    private val workers: ThreadPoolExecutor = ThreadPoolExecutor(
        1, MAX_WORKERS, 30, TimeUnit.SECONDS, LinkedBlockingQueue(MAX_QUEUED),
        Executors.defaultThreadFactory(), ThreadPoolExecutor.DiscardPolicy(),
    )

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
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                client.soTimeout = SOCKET_TIMEOUT_MS
                workers.execute {
                    runCatching { client.use(::handle) }
                        .onFailure { logError("device agent request failed", it) }
                }
            }
        }
        logInfo("device agent listening on $url")
    }

    fun stop() {
        clearToken()
        runCatching { serverSocket?.close() }
        serverSocket = null
        workers.shutdownNow()
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

    private fun handle(client: Socket) {
        val input = BufferedReader(InputStreamReader(client.getInputStream()))
        val requestLine = input.readLine() ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val (method, path) = parts

        var presentedToken: String? = null
        var contentLength = 0
        var headerCount = 0
        while (headerCount++ < MAX_HEADERS) {
            val header = input.readLine() ?: break
            if (header.isEmpty()) break
            val lower = header.lowercase()
            if (lower.startsWith("authorization:")) {
                presentedToken = header.substringAfter(':').trim().removePrefix("Bearer ").trim()
            }
            if (lower.startsWith("content-length:")) {
                contentLength = header.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }
        if (contentLength > MAX_BODY_BYTES) {
            writeResponse(
                client,
                413,
                ResultContract.error(ResultContract.Code.INVALID_REQUEST, "request body too large"),
            )
            return
        }
        val body = if (contentLength > 0) {
            val buffer = CharArray(contentLength)
            var read = 0
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            String(buffer, 0, read)
        } else ""

        val expected = currentToken
        val response: Pair<Int, String> = when {
            expected == null -> 403 to ResultContract.error(
                ResultContract.Code.INVALID_REQUEST,
                "device agent is only available while a job is running",
            )
            presentedToken == null || !constantTimeEquals(presentedToken, expected) ->
                401 to ResultContract.error(
                    ResultContract.Code.INVALID_REQUEST,
                    "missing or invalid capability token",
                )
            method == "GET" && path == "/v1/capabilities" -> 200 to capabilitiesJson()
            method == "POST" && path == "/v1/tests/nnapi" -> nnapiTest(body)
            method == "POST" && path == "/v1/tests/conv" -> convTest(body)
            method == "POST" && path == "/v1/tests/model" -> modelTest(body)
            else -> 404 to ResultContract.error(
                ResultContract.Code.INVALID_REQUEST,
                "unknown endpoint",
            )
        }
        writeResponse(client, response.first, response.second)
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

    private fun nnapiTest(body: String): Pair<Int, String> {
        val request = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrElse {
            return 400 to """{"error":"invalid JSON body"}"""
        }
        return 200 to NnapiProbe.benchmark(
            request.optString("device").takeIf { it.isNotBlank() },
            request.optInt("iterations", 100),
        )
    }

    private fun convTest(body: String): Pair<Int, String> {
        val request = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrElse {
            return 400 to """{"error":"invalid JSON body"}"""
        }
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
        val iterations = request.optInt("iterations", 50)

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
        // Qualcomm delegate in its own process, which NNAPI cannot reach.
        val backend = runCatching { QnnBackend.of(device) }.getOrElse { unknown ->
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
            return 200 to run(model, it, iterations, inputs, outputTarget)
        }
        return 200 to ModelRunner.run(
            model = model,
            deviceName = device,
            iterations = iterations,
            inputs = inputs,
            outputTarget = outputTarget,
            baseline = request.optBoolean("baseline"),
            // App-private and outside the guest's home: a diagnostic scratch
            // file is not something a job should find, or be able to write.
            diagnosticsDir = runtimeDir.parentFile,
        )
    }

    private fun writeResponse(client: Socket, status: Int, json: String) {
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            413 -> "Payload Too Large"
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
    }
}
