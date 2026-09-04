package io.github.m96chan.droidrunner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.m96chan.droidrunner.npu.QnnLicences
import io.github.m96chan.droidrunner.ui.theme.BtopColors

/**
 * Asks whether to accept Qualcomm's terms (issue #82, stage 3).
 *
 * The terms restrict what the software may be used for, and those restrictions
 * bind whoever runs this device — so the answer is the user's and the question
 * is asked before any runtime is fetched, not after.
 *
 * Every document is offered for reading rather than summarised into a
 * checkbox: this project has no business paraphrasing someone else's legal
 * text, and a user who acted on our paraphrase would be acting on the wrong
 * one. [onOpen] hands the file to a real reader, which is also why the accept
 * button is not the only way out of this dialog.
 */
@Composable
internal fun QnnLicenceDialog(
    onOpen: (QnnLicences.Licence) -> Unit,
    onAccept: () -> Unit,
    onCancel: () -> Unit,
    fetching: Boolean = false,
    failure: String? = null,
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, BtopColors.Yellow, RoundedCornerShape(8.dp))
                .background(BtopColors.Panel, RoundedCornerShape(8.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Qualcomm's terms apply to you, not to this app",
                color = BtopColors.Yellow,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "The NPU runtime is Qualcomm's, fetched from Maven Central rather than " +
                    "shipped in DroidRunner. Its licences restrict what the software may " +
                    "be used for, and those restrictions bind whoever runs this device.",
                color = BtopColors.Text,
                style = MaterialTheme.typography.bodyMedium,
            )

            Column(
                Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                QnnLicences.obligations.forEach { obligation ->
                    Text(
                        "• $obligation",
                        color = BtopColors.Dim,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            if (fetching) {
                Text(
                    "fetching the licence text…",
                    color = BtopColors.Dim,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            failure?.let {
                Text(it, color = BtopColors.Red, style = MaterialTheme.typography.labelMedium)
            }

            QnnLicences.required.forEach { licence ->
                OutlinedButton(
                    onClick = { onOpen(licence) },
                    enabled = !fetching,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Read the ${licence.title} (${licence.licensor})")
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = onCancel) { Text("Not now") }
                Button(
                    onClick = onAccept,
                    enabled = !fetching,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BtopColors.Yellow,
                        contentColor = BtopColors.Background,
                    ),
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text("I accept both") }
            }
        }
    }
}
