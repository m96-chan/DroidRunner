package io.github.m96chan.droidrunner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.m96chan.droidrunner.ui.theme.BtopColors

/** What setup is doing right now, for the modal that blocks the screen. */
data class SetupProgress(
    val phase: String,
    /** 0..1 where the work has a measurable length, null otherwise. */
    val fraction: Float? = null,
    /** Latest line of output, when the step produces any. */
    val detail: String? = null,
)

/**
 * Blocks the setup screen while registration runs.
 *
 * Downloading a ~200MB runtime and running `config.sh` take minutes, and the
 * screen behind offers a back arrow — leaving mid-flight used to be one tap
 * away, with nothing afterwards to say whether it had finished or failed.
 * Only Cancel is reachable here, and it actually stops the work.
 */
@Composable
fun SetupProgressDialog(progress: SetupProgress, onCancel: () -> Unit) {
    Dialog(
        onDismissRequest = { /* Only the Cancel button leaves this dialog. */ },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, BtopColors.Border, RoundedCornerShape(8.dp))
                .background(BtopColors.Panel, RoundedCornerShape(8.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("setting up", color = BtopColors.Yellow, style = MaterialTheme.typography.titleMedium)
            Text(progress.phase, color = BtopColors.Text, style = MaterialTheme.typography.bodyMedium)

            if (progress.fraction != null) {
                LinearProgressIndicator(
                    progress = { progress.fraction.coerceIn(0f, 1f) },
                    color = BtopColors.Cyan,
                    trackColor = BtopColors.Border,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${(progress.fraction.coerceIn(0f, 1f) * 100).toInt()}%",
                    color = BtopColors.Dim,
                    style = MaterialTheme.typography.labelMedium,
                )
            } else {
                LinearProgressIndicator(
                    color = BtopColors.Cyan,
                    trackColor = BtopColors.Border,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            progress.detail?.let {
                Text(
                    it,
                    color = BtopColors.Dim,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.padding(top = 2.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}
