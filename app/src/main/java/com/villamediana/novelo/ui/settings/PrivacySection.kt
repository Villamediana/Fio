package com.villamediana.novelo.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.villamediana.novelo.NoveloApplication
import com.villamediana.novelo.R
import com.villamediana.novelo.settings.t
import com.villamediana.novelo.ui.components.Group
import com.villamediana.novelo.ui.lock.Unlock
import com.villamediana.novelo.ui.lock.screen
import com.villamediana.novelo.ui.theme.NoveloColors
import kotlinx.coroutines.launch

/**
 * Ajustes › Privacidade: the phone's own lock (fingerprint, face or its PIN) before something
 * sensitive shows. Turning it on or off asks the lock too (no one turns it off in her place). A phone
 * with no lock at all can't: it says so.
 */
@Composable
fun PrivacySection() {
    val graph = NoveloApplication.graph
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by graph.settings.now.collectAsStateWithLifecycle()
    val can = remember { Unlock.available(context) }

    fun flip() {
        if (!can) return
        val activity = context.screen() ?: return
        scope.launch { if (Unlock.ask(activity)) graph.settings.setLockSensitive(!s.lockSensitive) }
    }

    Group(
        Modifier,
        {
            Row(
                Modifier.fillMaxWidth().clickable(enabled = can) { flip() }.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp).testTag("privacy-lock"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(t(R.string.privacy_lock), style = MaterialTheme.typography.titleMedium, color = NoveloColors.Text)
                    Text(
                        if (can) t(R.string.privacy_lock_line) else t(R.string.privacy_no_lock),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (can) NoveloColors.TextMuted else NoveloColors.Accent,
                    )
                }
                Switch(
                    checked = s.lockSensitive && can,
                    onCheckedChange = { flip() },
                    enabled = can,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = NoveloColors.Accent,
                        checkedThumbColor = NoveloColors.OnAccent,
                        uncheckedTrackColor = NoveloColors.Surface2,
                        uncheckedThumbColor = NoveloColors.TextFaint,
                        uncheckedBorderColor = NoveloColors.Surface2,
                    ),
                )
            }
        },
    )
}
