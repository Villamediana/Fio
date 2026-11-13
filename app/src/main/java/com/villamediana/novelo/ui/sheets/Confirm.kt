package com.villamediana.novelo.ui.sheets

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.villamediana.novelo.R
import com.villamediana.novelo.settings.t
import com.villamediana.novelo.ui.theme.NoveloColors

/**
 * Once more before something goes for good, the phone's own way (a dialog in the middle): what
 * goes, a line on what goes with it, and the two ways out — the one that takes it away in red.
 */
@Composable
fun ConfirmDialog(title: String, line: String?, yes: String, onYes: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.headlineSmall, color = NoveloColors.Text) },
        text = line?.let { { Text(it, style = MaterialTheme.typography.bodyMedium, color = NoveloColors.TextMuted) } },
        confirmButton = {
            TextButton(onClick = onYes, modifier = Modifier.testTag("confirm-yes")) {
                Text(yes, style = MaterialTheme.typography.labelLarge, color = NoveloColors.Danger)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(t(R.string.cancel), style = MaterialTheme.typography.labelLarge, color = NoveloColors.TextMuted)
            }
        },
        containerColor = NoveloColors.Surface,
        shape = RoundedCornerShape(26.dp),
        tonalElevation = 0.dp,
    )
}
