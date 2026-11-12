package com.villamediana.novelo.ui.entry

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.villamediana.novelo.R
import com.villamediana.novelo.data.Entry
import com.villamediana.novelo.remind.Repeat
import com.villamediana.novelo.settings.Lang
import com.villamediana.novelo.settings.t
import com.villamediana.novelo.ui.components.Dates
import com.villamediana.novelo.ui.components.NoveloIcons
import com.villamediana.novelo.ui.components.Pill
import com.villamediana.novelo.ui.sheets.NoveloSheet
import com.villamediana.novelo.ui.theme.NoveloColors
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle

/**
 * The reminder, set by hand: the day and the hour, and whether it comes back — not, every day, on
 * days of the week (tapped), or every month. [onSave] gets the first time and the rule (null: once);
 * [onRemove] takes it off.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReminderSheet(e: Entry, onSave: (Long, Repeat?) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val rule = remember(e.id) { Repeat.read(e.repeat) }
    val start = remember(e.id) {
        e.remindAt?.takeIf { it > System.currentTimeMillis() }?.let { Instant.ofEpochMilli(it).atZone(zone) }
            ?: ZonedDateTime.now(zone).plusHours(1).withMinute(0)
    }
    var date by remember(e.id) { mutableStateOf(start.toLocalDate()) }
    var time by remember(e.id) { mutableStateOf(rule?.let { runCatching { LocalTime.parse(it.time) }.getOrNull() } ?: start.toLocalTime().withSecond(0).withNano(0)) }
    var every by remember(e.id) { mutableStateOf(rule?.every) }
    var days by remember(e.id) { mutableStateOf(rule?.days?.toSet()?.takeIf { it.isNotEmpty() } ?: setOf(start.dayOfWeek.value)) }

    NoveloSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(t(R.string.reminder), style = MaterialTheme.typography.headlineSmall, color = NoveloColors.Text)
            Spacer(Modifier.height(16.dp))
            // When: the day (not for every day) and the hour.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (every != Repeat.DAY && every != Repeat.WEEK) {
                    Chip(NoveloIcons.Calendar, Dates.day(date.atStartOfDay(zone).toInstant().toEpochMilli(), zone), "${t(R.string.reminder_day)}") {
                        DatePickerDialog(context, { _, y, m, d -> date = LocalDate.of(y, m + 1, d) }, date.year, date.monthValue - 1, date.dayOfMonth).show()
                    }
                }
                Chip(NoveloIcons.Bell, "%02d:%02d".format(time.hour, time.minute), t(R.string.reminder_time)) {
                    TimePickerDialog(context, { _, h, min -> time = LocalTime.of(h, min) }, time.hour, time.minute, true).show()
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(t(R.string.repeat), style = MaterialTheme.typography.labelLarge, color = NoveloColors.TextMuted)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(t(R.string.repeat_none), every == null, { every = null })
                Pill(t(R.string.repeat_day), every == Repeat.DAY, { every = Repeat.DAY })
                Pill(t(R.string.repeat_week), every == Repeat.WEEK, { every = Repeat.WEEK })
                Pill(t(R.string.repeat_month), every == Repeat.MONTH, { every = Repeat.MONTH })
            }
            // On which days of the week: one round letter each, tapped on and off.
            if (every == Repeat.WEEK) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..7).forEach { d ->
                        val on = d in days
                        val name = DayOfWeek.of(d).getDisplayName(TextStyle.FULL, Lang.locale)
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(if (on) NoveloColors.Accent else NoveloColors.Surface2)
                                .clickable { days = if (on && days.size > 1) days - d else days + d }
                                .semantics { contentDescription = name; selected = on }
                                .testTag("repeat-day-$d"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                DayOfWeek.of(d).getDisplayName(TextStyle.NARROW, Lang.locale).uppercase(Lang.locale),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (on) NoveloColors.OnAccent else NoveloColors.TextMuted,
                            )
                        }
                    }
                }
            }
            // What it will be, in words.
            val first = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
            val made = Repeat.from(every, days.toList(), first, zone)
            Spacer(Modifier.height(14.dp))
            Text(made?.describe() ?: Dates.dayTime(first, zone), style = MaterialTheme.typography.bodyMedium, color = NoveloColors.Text)
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                if (e.remindAt != null) {
                    Box(Modifier.clip(CircleShape).clickable(onClick = onRemove).padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(t(R.string.reminder_remove), style = MaterialTheme.typography.labelLarge, color = NoveloColors.TextMuted)
                    }
                }
                Box(
                    Modifier.clip(CircleShape).background(NoveloColors.Accent).clickable {
                        // A day picked that the rule doesn't have (a Tuesday for Mon/Wed, a time already gone): its next time.
                        val now = System.currentTimeMillis()
                        val at = made?.next(maxOf(first, now) - 1, zone) ?: first
                        onSave(at, made)
                    }.padding(horizontal = 18.dp, vertical = 12.dp).testTag("reminder-save"),
                ) {
                    Text(t(R.string.reminder_save), style = MaterialTheme.typography.labelLarge, color = NoveloColors.OnAccent)
                }
            }
        }
    }
}

@Composable
private fun Chip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, name: String, onClick: () -> Unit) {
    Row(
        Modifier.height(40.dp).clip(CircleShape).background(NoveloColors.Surface2).clickable(onClick = onClick)
            .semantics { contentDescription = "$name: $text" }.padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = NoveloColors.Accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.padding(start = 8.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = NoveloColors.Text)
    }
}
