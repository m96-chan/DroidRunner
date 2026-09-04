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
                    val request = Message.obtain(null, QnnService.WHAT_PROBE).apply {
                        data = Bundle().apply {
                            putString(QnnService.KEY_DIRECTORY, installDir.absolutePath)
                            putStringArray(QnnService.KEY_LIBRARIES, libraries.toTypedArray())
                        }
                        this.replyTo = replyTo
                    }
                    // A send that throws means the far side has already gone;
                    // the timeout below turns that into an answer.
                    runCatching { Messenger(binder).send(request) }
                }

                override fun onServiceDisconnected(name: ComponentName?) = Unit
            }

            val bound = context.bindService(
                Intent(context, QnnService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
            if (!bound) return QnnProbeResult.unavailable("the QNN process would not start")

            val answer = answers.poll(timeoutMs, TimeUnit.MILLISECONDS)
                ?: return QnnProbeResult.unavailable(
                    "the QNN process did not answer within ${timeoutMs}ms",
                )
            return QnnProbeResult.parse(answer)
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
    }
}
