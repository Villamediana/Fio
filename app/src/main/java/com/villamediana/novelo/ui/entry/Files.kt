package com.villamediana.novelo.ui.entry

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.villamediana.novelo.NoveloApplication
import com.villamediana.novelo.R
import com.villamediana.novelo.data.Entry
import com.villamediana.novelo.data.KeptFile
import com.villamediana.novelo.data.Kind
import com.villamediana.novelo.mind.DayDiary
import com.villamediana.novelo.mind.FileKind
import com.villamediana.novelo.settings.Lang
import com.villamediana.novelo.settings.t
import com.villamediana.novelo.ui.components.NoveloIcons
import com.villamediana.novelo.ui.components.StitchColor
import com.villamediana.novelo.ui.components.stitched
import com.villamediana.novelo.ui.theme.NoveloColors
import java.io.File
import java.time.ZoneId

/**
 * The files an item holds — and, on the day's diary, the day's files that live in no item — under
 * its text: one quiet line each; a tap opens it in the app she picks. [hidden]: something sensitive
 * not shown yet (the names blurred, a tap asks to see).
 */
@Composable
internal fun EntryFiles(entry: Entry, modifier: Modifier = Modifier, hidden: Boolean = false, onReveal: () -> Unit = {}) {
    val lib = NoveloApplication.graph.library
    val context = LocalContext.current
    val own by remember(entry.id) { lib.filesOf(entry.id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val diary = entry.kindEnum == Kind.DIARY
    val day = remember(entry.createdAt) { DayDiary.bounds(entry.createdAt, ZoneId.systemDefault()) }
    val isDay by produceState(false, entry.id, day, diary) { value = diary && lib.isDayDiary(entry.id, day) }
    val loose by remember(day) { lib.dayFiles(day) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val files = remember(own, loose, isDay) { (own + if (isDay) loose else emptyList()).distinctBy { it.id } }
    if (files.isEmpty()) return
    var removing by remember { androidx.compose.runtime.mutableStateOf<KeptFile?>(null) }
    Column(modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        files.forEachIndexed { i, f ->
            FileRow(
                f,
                onClick = { if (hidden) onReveal() else openFile(context, f) },
                onLongClick = { if (hidden) onReveal() else removing = f },
                hidden = hidden,
                modifier = Modifier.testTag(if (hidden) "entry-file-hidden-$i" else "entry-file-$i"),
            )
        }
    }
    removing?.let { f -> RemoveFile(f) { removing = null } }
}

/**
 * One file: its kind in a small square, its name, and under it what it is and how big — and, when
 * [where] is given, the item it lives in.
 */
@Composable
internal fun FileRow(f: KeptFile, onClick: () -> Unit, modifier: Modifier = Modifier, hidden: Boolean = false, where: String? = null, onLongClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(NoveloColors.Surface)
            .stitched(shape, StitchColor, inset = 5.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = onLongClick?.let { t(R.string.file_remove) })
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(NoveloColors.Accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(NoveloIcons.File, null, tint = NoveloColors.Accent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        // Hidden: blurred where Android blurs; elsewhere the name doesn't show at all.
        val veil = if (hidden && Build.VERSION.SDK_INT >= 31) Modifier.blur(8.dp) else Modifier
        Column(Modifier.weight(1f).then(veil)) {
            Text(
                if (hidden && Build.VERSION.SDK_INT < 31) "••••••••" else f.name,
                style = MaterialTheme.typography.titleMedium,
                color = NoveloColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(FileKind.label(f.name, f.mime), f.size.takeIf { it > 0 }?.let { FileKind.size(it, Lang.locale) }, where).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = NoveloColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Taking [f] out of Novelo, once more asked: from its item, the day's diary, the Arquivos tab and the phone. */
@Composable
internal fun RemoveFile(f: KeptFile, onDone: () -> Unit) {
    val graph = NoveloApplication.graph
    com.villamediana.novelo.ui.sheets.ConfirmSheet(
        title = t(R.string.file_remove_title),
        line = f.name,
        yes = t(R.string.file_remove),
        onYes = { onDone(); graph.scope.launch { graph.library.removeFile(f.id) } },
        onDismiss = onDone,
    )
}

/** Opens [f] in the app she picks for its type (the phone asks when there is more than one). */
fun openFile(context: Context, f: KeptFile) {
    val file = File(f.path)
    if (!file.isFile) {
        Toast.makeText(context, t(R.string.file_gone), Toast.LENGTH_SHORT).show()
        return
    }
    val uri = runCatching { FileProvider.getUriForFile(context, context.packageName + ".files", file) }.getOrNull() ?: return
    fun view(type: String) = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(view(f.mime ?: "*/*"))
    } catch (_: ActivityNotFoundException) {
        // No app for its type: any app that opens files may still.
        try {
            context.startActivity(view("*/*"))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, t(R.string.file_no_app), Toast.LENGTH_SHORT).show()
        }
    }
}
