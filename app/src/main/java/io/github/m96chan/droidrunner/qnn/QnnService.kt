/*
 * Part of DroidRunner. GPL-2.0-only, with the additional permission below.
 *
 * Additional permission under GNU GPL version 2, as a special exception:
 *
 * The copyright holders of this file give you permission to combine it with
 * Qualcomm's QNN runtime and LiteRT delegate libraries, and to convey the
 * resulting work. This permission covers this file only; it does not extend to
 * any other part of DroidRunner, which remains GPL-2.0-only.
 */
package io.github.m96chan.droidrunner.qnn

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger

/**
 * Holds Qualcomm's runtime, in a process of its own (issue #82, stage 4).
 *
 * Declared with `android:process=":qnn"`, which is the whole point: the FSF's
 * line for "one program" is the shared address space, and PRoot — someone
 * else's GPL-2.0 code, which this project cannot grant exceptions for — runs
 * in the main process. Nothing of ours reaches this process except this file
 * and [QnnNative], both of which carry an additional permission.
 *
 * The isolation pays off twice. A vendor library that segfaults takes down a
 * process whose only job is to answer questions about it; the runner, its
 * listener and the job it is serving carry on in the main process and never
 * hear about it.
 *
 * The reply carries whatever the loader produced, including its failures. A
 * caller that gets no answer at all learns that from the binder dying, which
 * is a different thing from a library that failed to open and said so.
 */
class QnnService : Service() {

    private val messenger = Messenger(
        Handler(Looper.getMainLooper()) { message ->
            when (message.what) {
                WHAT_PROBE -> reply(message, probe(message.data))
                else -> return@Handler false
            }
            true
        },
    )

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun probe(request: Bundle): String {
        val directory = request.getString(KEY_DIRECTORY)
        val libraries = request.getStringArray(KEY_LIBRARIES)
        if (directory == null || libraries == null) {
            return """{"ok":false,"error":"probe request named no libraries"}"""
        }
        return QnnNative.load(directory, libraries.toList())
    }

    private fun reply(message: Message, result: String) {
        val answer = Message.obtain(null, WHAT_PROBE).apply {
            data = Bundle().apply { putString(KEY_RESULT, result) }
        }
        // The caller going away between asking and being answered is ordinary:
        // it is a bound service and the activity may have been dismissed.
        runCatching { message.replyTo?.send(answer) }
    }

    companion object {
        const val WHAT_PROBE = 1
        const val KEY_DIRECTORY = "directory"
        const val KEY_LIBRARIES = "libraries"
        const val KEY_RESULT = "result"
    }
}
