package dev.devenus.droidrunner.npu

import android.content.Context
import android.os.Build
import android.os.PowerManager
import dev.devenus.droidrunner.device.DeviceCapabilities
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Device Agent PoC (issue #4): a loopback-only HTTP server that lets CI jobs
 * reach Android-side hardware (NNAPI) they cannot touch from inside PRoot.
 * The runner process receives DROIDRUNNER_DEVICE_URL and _TOKEN in its
 * environment, which self-hosted jobs inherit.
 *
 * Final design uses per-job capability tokens; this PoC issues one token per
 * runner session.
 */
class DeviceAgentServer(private val context: Context) {
    val token: String = SecureRandom().let { rng ->
        ByteArray(24).also(rng::nextBytes).joinToString("") { "%02x".format(it) }
    }
    var port: Int = 0
        private set
    val url: String get() = "http://127.0.0.1:$port"

    private var serverSocket: ServerSocket? = null

    fun start() {
        val socket = ServerSocket()
        socket.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        port = socket.localPort
        serverSocket = socket
        thread(name = "device-agent", isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                runCatching { client.use(::handle) }
                    .onFailure { android.util.Log.e("DroidRunner", "device agent request failed", it) }
            }
        }
        android.util.Log.d("DroidRunner", "device agent listening on $url")
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun handle(client: Socket) {
        val input = BufferedReader(InputStreamReader(client.getInputStream()))
        val requestLine = input.readLine() ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val (method, path) = parts

        var authorized = false
        var contentLength = 0
        while (true) {
            val header = input.readLine() ?: break
            if (header.isEmpty()) break
            val lower = header.lowercase()
            if (lower.startsWith("authorization:") && header.substringAfter(':').trim() == "Bearer $token") {
                authorized = true
            }
            if (lower.startsWith("content-length:")) {
                contentLength = header.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }
        val body = if (contentLength > 0) {
            val buffer = CharArray(contentLength.coerceAtMost(64 * 1024))
            var read = 0
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            String(buffer, 0, read)
        } else ""

        val response: Pair<Int, String> = when {
            !authorized -> 401 to """{"error":"missing or invalid capability token"}"""
            method == "GET" && path == "/v1/capabilities" -> 200 to capabilities()
            method == "POST" && path == "/v1/tests/nnapi" -> nnapiTest(body)
            else -> 404 to """{"error":"unknown endpoint"}"""
        }
        writeResponse(client, response.first, response.second)
    }

    private fun capabilities(): String {
        val capabilities = DeviceCapabilities.detect()
        val thermal = if (Build.VERSION.SDK_INT >= 29) {
            context.getSystemService(PowerManager::class.java).currentThermalStatus
        } else -1
        return JSONObject()
            .put("agent", "droidrunner/0.1")
            .put(
                "device",
                JSONObject()
                    .put("manufacturer", capabilities.manufacturer)
                    .put("model", capabilities.model)
                    .put("labels", org.json.JSONArray(capabilities.labels().sorted())),
            )
            .put("android", JSONObject().put("sdk", Build.VERSION.SDK_INT))
            .put("thermalStatus", thermal)
            .put("nnapi", JSONObject(NnapiProbe.devices()))
            .toString()
    }

    private fun nnapiTest(body: String): Pair<Int, String> {
        val request = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrElse {
            return 400 to """{"error":"invalid JSON body"}"""
        }
        val device = request.optString("device").takeIf { it.isNotBlank() }
        val iterations = request.optInt("iterations", 100)
        val result = NnapiProbe.benchmark(device, iterations)
        return 200 to result
    }

    private fun writeResponse(client: Socket, status: Int, json: String) {
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            else -> "Not Found"
        }
        val payload = json.toByteArray()
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
