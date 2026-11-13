package com.villamediana.novelo.ui.entry

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.villamediana.novelo.NoveloApplication
import com.villamediana.novelo.R
import com.villamediana.novelo.data.Categories
import com.villamediana.novelo.data.Entry
import com.villamediana.novelo.settings.t
import com.villamediana.novelo.ui.components.CategoryDot
import com.villamediana.novelo.ui.components.IconAction
import com.villamediana.novelo.ui.components.NoveloIcons
import com.villamediana.novelo.ui.home.QuietIcon
import com.villamediana.novelo.ui.sheets.NoveloSheet
import com.villamediana.novelo.ui.theme.NoveloColors
import kotlinx.coroutines.launch

/** A task's category, as a chip ("● Trabalho ⌄"): a tap opens the categories to pick another, rename one, or make a new one. */
@Composable
fun CategoryLine(e: Entry) {
    var picking by remember { mutableStateOf(false) }
    Row(
        Modifier
            .clip(CircleShape)
            .background(NoveloColors.Surface2)
            .clickable { picking = true }
            .padding(start = 12.dp, end = 10.dp, top = 7.dp, bottom = 7.dp)
            .testTag("entry-category"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val c = e.category
        if (c != null) {
            CategoryDot(c, 8.dp)
            Spacer(Modifier.width(7.dp))
            Text(c, style = MaterialTheme.typography.labelLarge, color = NoveloColors.TextMuted, maxLines = 1)
        } else {
            Text(t(R.string.category_choose), style = MaterialTheme.typography.labelLarge, color = NoveloColors.TextFaint, maxLines = 1)
        }
        Icon(NoveloIcons.Chevron, null, tint = NoveloColors.TextFaint, modifier = Modifier.padding(start = 4.dp).size(14.dp).rotate(90f))
    }
    if (picking) CategorySheet(e.id, e.category, onDismiss = { picking = false })
}

/**
 * The categories there are (the ones with the most open tasks first): a tap puts the task in one; the
 * pencil renames it on every task (to the name of another that exists: the two become one); under
 * them, a new one.
 */
@Composable
private fun CategorySheet(id: Long, current: String?, onDismiss: () -> Unit) {
    val graph = NoveloApplication.graph
    val lib = graph.library
    val scope = rememberCoroutineScope()
    val entries by remember { lib.all() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val names = remember(entries, current) { (Categories.inUse(entries) + listOfNotNull(current)).distinct() }
    var renaming by remember { mutableStateOf<String?>(null) }
    var renamed by remember { mutableStateOf("") }
    var made by remember { mutableStateOf("") }
    fun choose(name: String) {
        scope.launch { lib.setCategory(id, name) }
        onDismiss()
    }
    NoveloSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(t(R.string.category), style = MaterialTheme.typography.headlineSmall, color = NoveloColors.Text, modifier = Modifier.padding(bottom = 10.dp))
            names.forEachIndexed { i, n ->
                if (renaming == n) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { NameField(renamed, { renamed = it }, n, onDone = { scope.launch { lib.renameCategory(n, renamed) }; renaming = null }, tag = "category-rename") }
                        IconAction(NoveloIcons.Check, t(R.string.ok), {
                            scope.launch { lib.renameCategory(n, renamed) }
                            renaming = null
                        }, modifier = Modifier.padding(start = 8.dp), primary = true)
                    }
                } else {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { choose(n) }
                            .padding(start = 6.dp)
                            .testTag("category-$i"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CategoryDot(n, 10.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(n, style = MaterialTheme.typography.titleMedium, color = if (n == current) NoveloColors.Accent else NoveloColors.Text, modifier = Modifier.weight(1f))
                        if (n == current) Icon(NoveloIcons.Check, null, tint = NoveloColors.Accent, modifier = Modifier.size(18.dp))
                        QuietIcon(NoveloIcons.Pencil, t(R.string.category_rename), { renaming = n; renamed = n }, alpha = 0.45f, size = 44.dp)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            // A new one: written, and the task goes in it.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { NameField(made, { made = it }, t(R.string.category_new), onDone = { if (made.isNotBlank()) choose(made) }, tag = "category-new") }
                if (made.isNotBlank()) IconAction(NoveloIcons.Check, t(R.string.ok), { choose(made) }, modifier = Modifier.padding(start = 8.dp), primary = true)
            }
        }
    }
}

@Composable
private fun NameField(value: String, onChange: (String) -> Unit, hint: String, onDone: () -> Unit, tag: String) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(NoveloColors.Surface2).padding(horizontal = 16.dp, vertical = 14.dp)) {
        if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyLarge, color = NoveloColors.TextFaint)
        BasicTextField(
            value = value,
            onValueChange = { onChange(it.take(Categories.MAX)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = NoveloColors.Text),
            cursorBrush = SolidColor(NoveloColors.Accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier.fillMaxWidth().testTag(tag),
        )
    }
}
