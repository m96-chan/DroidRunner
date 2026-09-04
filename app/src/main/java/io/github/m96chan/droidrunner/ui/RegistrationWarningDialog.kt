package io.github.m96chan.droidrunner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import io.github.m96chan.droidrunner.ui.theme.BtopColors

/**
 * Confirms a registration that widens what can run on this phone (issue #64).
 *
 * Placed at the moment of the decision rather than as a banner on the screen:
 * something always present becomes furniture and is read once, while this is
 * asked exactly when the answer still changes anything. The confirming button
 * says what it does rather than "OK", so tapping it is a choice and not a
 * reflex.
 */
@Composable
internal fun RegistrationWarningDialog(
    warning: RegistrationWarning,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
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
            Text(warning.headline, color = BtopColors.Yellow, style = MaterialTheme.typography.titleMedium)
            Text(warning.detail, color = BtopColors.Text, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BtopColors.Yellow,
                        contentColor = BtopColors.Background,
                    ),
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text("Register anyway") }
            }
        }
    }
}
