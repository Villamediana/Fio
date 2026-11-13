package com.villamediana.novelo.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.villamediana.novelo.data.Categories
import com.villamediana.novelo.ui.theme.NoveloColors

/** Each category's color: a quiet one of a few, always the same for the same name (light and dark each their own). */
object CategoryColors {
    private val LIGHT = listOf(0xFF7F9A84, 0xFF7389AC, 0xFF9A7398, 0xFFC0954A, 0xFF8E9356, 0xFFC07B74, 0xFF5F9792, 0xFFA88B6A).map { Color(it) }
    private val DARK = listOf(0xFFA3BDA7, 0xFF9CB0D0, 0xFFC39DC0, 0xFFDDB46E, 0xFFB5BA7E, 0xFFDCA19A, 0xFF8BBFBA, 0xFFCBAE8C).map { Color(it) }

    /** Which of them [name] gets (FNV-1a of the name without case or accents). */
    fun index(name: String): Int {
        var h = 0x811C9DC5.toInt()
        Categories.fold(name).forEach { c -> h = (h xor c.code) * 0x01000193 }
        return Math.floorMod(h, LIGHT.size)
    }

    fun of(name: String, dark: Boolean): Color = (if (dark) DARK else LIGHT)[index(name)]
}

/** A category's dot. */
@Composable
fun CategoryDot(name: String, size: Dp = 7.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(CategoryColors.of(name, NoveloColors.palette.dark)))
}

/** A category as a word with its dot ("● Trabalho"), quiet. */
@Composable
fun CategoryTag(name: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        CategoryDot(name, 6.dp)
        Spacer(Modifier.width(5.dp))
        Text(name, style = MaterialTheme.typography.labelMedium, color = NoveloColors.TextFaint, maxLines = 1)
    }
}

/** A filter: a category (with its dot) or, with no [name], all of them ([label]). */
@Composable
fun CategoryChip(label: String, name: String?, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bg by animateColorAsState(if (selected) NoveloColors.Accent.copy(alpha = 0.13f) else NoveloColors.Surface2, label = "chipBg")
    val fg by animateColorAsState(if (selected) NoveloColors.Accent else NoveloColors.TextMuted, label = "chipFg")
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .pressScale(interaction)
            .clip(CircleShape)
            .background(bg)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { role = Role.Tab; this.selected = selected }
            .padding(start = if (name != null) 11.dp else 14.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (name != null) {
            CategoryDot(name)
            Spacer(Modifier.width(7.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
    }
}
