package io.github.m96chan.droidrunner.npu

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import io.github.m96chan.droidrunner.qnn.QnnService
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Asks the isolated process about Qualcomm's runtime (issue #82, stage 4).
 *
 * This half runs in the main process, beside PRoot, and is ordinary
 * GPL-2.0-only code: it never loads a Qualcomm library, it sends a message to
 * something that does. That is the boundary — an IPC one, not a function call.
 *
 * Every failure ends the same way, with a [QnnProbeResult] saying what went
 * wrong. A vendor library that brings its process down is indistinguishable
 * from one that never answers, and neither should be able to take the runner
 * with it: the caller gets "no" and the listener keeps working.
 */
internal class QnnClient(private val context: Context) {

    /**
     * Loads the runtime for [htpVersion] out of [installDir] and reports what
     * the delegate said.
     *
     * Blocking, with a timeout, because the caller is already off the main
     * thread and the alternative — a callback that may never arrive — is the
     * thing being guarded against.
     */
    fun probe(
        installDir: File,
        htpVersion: Int,
        timeoutMs: Long = TIMEOUT_MS,
    ): QnnProbeResult {
        val libraries = QnnLibraries.loadOrder(htpVersion)
            ?: return QnnProbeResult.unavailable("no QNN runtime for Hexagon v$htpVersion")
        if (!installDir.isDirectory) {
            return QnnProbeResult.unavailable("the QNN runtime is not installed")
        }
        val answer = ask(QnnService.WHAT_PROBE, timeoutMs) { extras ->
            extras.putString(QnnService.KEY_DIRECTORY, installDir.absolutePath)
            extras.putStringArray(QnnService.KEY_LIBRARIES, libraries.toTypedArray())
        }
        return when (answer) {
            null -> QnnProbeResult.unavailable(
                "the QNN process did not answer within ${timeoutMs}ms",
            )
            DIED -> QnnProbeResult.unavailable("the QNN process died while loading")
            else -> QnnProbeResult.parse(answer)
        }
    }

    /**
     * Runs [model] on the accelerator and returns the runner's JSON verbatim,
     * refusals included — the caller reports what happened, it does not decide
     * what happened.
     */
    fun runModel(
        installDir: File,
        htpVersion: Int,
        model: File,
        backend: String,
        iterations: Int,
        inputs: List<File> = emptyList(),
        outputTarget: TensorIo.Target? = null,
        /** Ask for every iteration in run order (#98). */
        keepTimings: Boolean = false,
        timeoutMs: Long = RUN_TIMEOUT_MS,
    ): String {
        val libraries = QnnLibraries.loadOrder(htpVersion)
            ?: return refusal("no QNN runtime for Hexagon v$htpVersion")
        if (!installDir.isDirectory) return refusal("the QNN runtime is not installed")

        return ask(QnnService.WHAT_RUN, timeoutMs) { extras ->
            extras.putString(QnnService.KEY_DIRECTORY, installDir.absolutePath)
            extras.putStringArray(QnnService.KEY_LIBRARIES, libraries.toTypedArray())
            extras.putString(QnnService.KEY_MODEL, model.absolutePath)
            extras.putString(QnnService.KEY_BACKEND, backend)
            extras.putInt(QnnService.KEY_ITERATIONS, iterations)
            extras.putBoolean(QnnService.KEY_TIMINGS, keepTimings)
            extras.putStringArray(
                QnnService.KEY_INPUTS,
                inputs.map { it.absolutePath }.toTypedArray(),
            )
            outputTarget?.let {
                extras.putString(QnnService.KEY_OUTPUT_DIR, it.directory.absolutePath)
                extras.putString(QnnService.KEY_OUTPUT_AS_SEEN, it.asJobSeesIt)
            }
        }.let { answer ->
            when (answer) {
                null -> refusal(
                    "the QNN process did not answer within ${timeoutMs}ms",
                )
                DIED -> refusal(
                    "the QNN process died while loading or running the model; " +
                        "see qnn-last-run.txt for how far it got",
                )
                else -> answer
            }
        }
    }

    private fun refusal(reason: String) =
        org.json.JSONObject().put("ok", false).put("error", reason).toString()

    /**
     * Binds, sends one message, waits for the answer and unbinds.
     *
     * Every failure returns null rather than throwing: a process holding vendor
     * code that dies is not an exceptional case here, it is the case this
     * boundary exists for.
     */
    private fun ask(what: Int, timeoutMs: Long, fill: (Bundle) -> Unit): String? {

        val answers = ArrayBlockingQueue<String>(1)
        val replyThread = HandlerThread("qnn-reply").apply { start() }
        val replyTo = Messenger(
            Handler(replyThread.looper) { message ->
                answers.offer(message.data?.getString(QnnService.KEY_RESULT).orEmpty())
                true
            },
        )

        var connection: ServiceConnection? = null
        try {
            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val request = Message.obtain(null, what).apply {
                        data = Bundle().apply(fill)
                        this.replyTo = replyTo
                    }
                    // A send that throws means the far side has already gone;
                    // the timeout below turns that into an answer.
                    runCatching { Messenger(binder).send(request) }
                }

                /**
                 * The far side died. Vendor code segfaults, and when it does
                 * there is no reply coming — waiting out the timeout would
                 * turn a two-second answer into a five-minute one, and the
                 * job would learn nothing extra by waiting.
                 */
                override fun onServiceDisconnected(name: ComponentName?) {
                    answers.offer(DIED)
                }
            }

            val bound = context.bindService(
                Intent(context, QnnService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
            if (!bound) return null

            return answers.poll(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            connection?.let { runCatching { context.unbindService(it) } }
            replyThread.quitSafely()
        }
    }

    private companion object {
        /**
         * Generous: the first load of a 79MB preparer on a cold page cache is
         * not fast, and a timeout here reads as "this device cannot do it".
         */
        const val TIMEOUT_MS = 30_000L

        /**
         * A model of any size, compiled for the Hexagon and run many times.
         * Graph preparation alone can take tens of seconds the first time.
         */
        const val RUN_TIMEOUT_MS = 300_000L

        /** Not a reply: the marker that the far side went away. */
        const val DIED = "\u0000died"
    }
}
