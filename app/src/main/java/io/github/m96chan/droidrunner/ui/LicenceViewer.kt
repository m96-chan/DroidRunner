package io.github.m96chan.droidrunner.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Opens a licence in whatever the user reads PDFs with (issue #82, stage 3).
 *
 * Rendering it inside the app was the alternative, and a two-column legal PDF
 * on a phone screen is not something anyone would read. Handing it to a real
 * reader also keeps the terms in a place the user can scroll back to after
 * this dialog is gone.
 */
internal object LicenceViewer {

    fun intentFor(context: Context, licence: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.licences", licence)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
