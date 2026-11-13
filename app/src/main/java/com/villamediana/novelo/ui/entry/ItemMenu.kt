package com.villamediana.novelo.ui.entry

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.villamediana.novelo.NoveloApplication
import com.villamediana.novelo.data.Entry
import com.villamediana.novelo.ui.theme.NoveloColors
import kotlinx.coroutines.flow.first
import java.io.File

/** One line of a menu: a thin icon and its words; [danger] (taking something away) in red, apart. */
internal class MenuAction(val icon: ImageVector, val label: String, val tag: String, val danger: Boolean = false, val onClick: () -> Unit)

/**
 * The little menu of an item or a file: a round card of paper over the screen, each line with its
 * icon; what takes something away comes last, in red, under a thin line.
 */
@Composable
internal fun ItemMenu(expanded: Boolean, onDismiss: () -> Unit, actions: List<MenuAction>, offset: DpOffset = DpOffset(0.dp, 0.dp)) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = offset,
        shape = RoundedCornerShape(20.dp),
        containerColor = NoveloColors.Surface,
        tonalElevation = 0.dp,
        shadowElevation = 14.dp,
        border = BorderStroke(1.dp, NoveloColors.Line),
        modifier = Modifier.widthIn(min = 224.dp),
    ) {
        actions.forEachIndexed { i, a ->
            if (a.danger && i > 0) HorizontalDivider(Modifier.padding(horizontal = 18.dp, vertical = 4.dp), thickness = 1.dp, color = NoveloColors.Line)
            DropdownMenuItem(
                text = { Text(a.label, style = MaterialTheme.typography.bodyLarge, color = if (a.danger) NoveloColors.Danger else NoveloColors.Text) },
                leadingIcon = { Icon(a.icon, null, tint = if (a.danger) NoveloColors.Danger else NoveloColors.TextMuted, modifier = Modifier.size(20.dp)) },
                onClick = { onDismiss(); a.onClick() },
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
                modifier = Modifier.testTag(a.tag),
            )
        }
    }
}

/**
 * A note out to another app, by the phone's share sheet: its title and text, and the files and
 * photos it holds.
 */
internal suspend fun shareNote(context: Context, e: Entry) {
    val lib = NoveloApplication.graph.library
    val held = lib.filesOf(e.id).first().map { File(it.path) } + lib.photosOf(e.id).first().map { File(it.path) }
    val uris = held.filter { it.isFile }.mapNotNull { runCatching { FileProvider.getUriForFile(context, context.packageName + ".files", it) }.getOrNull() }
    val text = listOf(e.title, e.body).filter { it.isNotBlank() }.joinToString("\n\n")
    val send = if (uris.isEmpty()) {
        Intent(Intent.ACTION_SEND).setType("text/plain")
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // The files go through the chooser to the app she picks only when they are in its clip.
            .also { i -> i.clipData = ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } } }
    }
    send.putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, e.title)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
