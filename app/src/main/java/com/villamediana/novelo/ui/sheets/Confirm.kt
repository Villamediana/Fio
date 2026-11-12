package com.villamediana.novelo.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.villamediana.novelo.R
import com.villamediana.novelo.settings.t
import com.villamediana.novelo.ui.theme.NoveloColors

/** Once more before something goes for good: what goes, a line on what goes with it, and the two ways out. */
@Composable
fun ConfirmSheet(title: String, line: String?, yes: String, onYes: () -> Unit, onDismiss: () -> Unit) {
    NoveloSheet(onDismissRequest = onDismiss) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = NoveloColors.Text, modifier = Modifier.padding(horizontal = 24.dp))
        if (line != null) {
            Spacer(Modifier.height(6.dp))
            Text(line, style = MaterialTheme.typography.bodyMedium, color = NoveloColors.TextMuted, modifier = Modifier.padding(horizontal = 24.dp))
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            Box(Modifier.clip(CircleShape).clickable(onClick = onDismiss).padding(horizontal = 18.dp, vertical = 12.dp)) {
                Text(t(R.string.cancel), style = MaterialTheme.typography.labelLarge, color = NoveloColors.TextMuted)
            }
            Box(Modifier.clip(CircleShape).background(NoveloColors.Danger).clickable(onClick = onYes).padding(horizontal = 18.dp, vertical = 12.dp).testTag("confirm-yes")) {
                Text(yes, style = MaterialTheme.typography.labelLarge, color = NoveloColors.Background)
            }
        }
    }
}
