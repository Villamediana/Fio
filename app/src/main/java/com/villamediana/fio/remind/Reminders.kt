package com.villamediana.fio.remind

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.villamediana.fio.FioApplication
import com.villamediana.fio.MainActivity
import com.villamediana.fio.Notifications
import com.villamediana.fio.R
import com.villamediana.fio.data.Entry
import com.villamediana.fio.data.FioDatabase
import com.villamediana.fio.data.Kind
import com.villamediana.fio.data.Progress
import com.villamediana.fio.data.Status
import com.villamediana.fio.mind.ReminderSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Reminders and check-ins on AlarmManager: one alarm per entry and kind, exact when Android allows
 * it (USE_EXACT_ALARM), re-armed after a reboot or an update.
 */
class Reminders(private val context: Context, private val db: FioDatabase) : ReminderSync {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    override suspend fun sync(entryIds: List<Long>) {
        entryIds.forEach { id ->
            val e = db.entries().get(id)
            arm(id, REMIND, e?.remindAt?.takeIf { it > System.currentTimeMillis() })
            arm(id, CHECK_IN, e?.checkInAt?.takeIf { it > System.currentTimeMillis() && e.isOpenTask })
        }
    }

    /**
     * Every reminder and check-in again (boot, update, app opening). One whose time passed while
     * the phone was off is armed too: AlarmManager fires it right away, late but not lost.
     */
    suspend fun syncAll() {
        db.entries().withReminder().forEach { arm(it.id, REMIND, it.remindAt) }
        db.entries().withCheckIn().forEach { arm(it.id, CHECK_IN, it.checkInAt) }
    }

    @SuppressLint("MissingPermission") // USE_EXACT_ALARM (33+) / SCHEDULE_EXACT_ALARM (≤32), checked below
    private fun arm(entryId: Long, kind: Int, at: Long?) {
        val pi = pending(entryId, kind, at ?: 0L, create = at != null) ?: return
        if (at == null) {
            alarms.cancel(pi)
            pi.cancel()
            return
        }
        val exact = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
        if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    private fun pending(entryId: Long, kind: Int, at: Long, create: Boolean): PendingIntent? {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ACTION_FIRE)
            .putExtra(EXTRA_ENTRY, entryId)
            .putExtra(EXTRA_KIND, kind)
            .putExtra(EXTRA_AT, at)
        val flags = PendingIntent.FLAG_IMMUTABLE or (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE)
        return PendingIntent.getBroadcast(context, requestCode(entryId, kind), intent, flags)
    }

    companion object {
        const val REMIND = 0
        const val CHECK_IN = 1
        const val ACTION_FIRE = "com.villamediana.fio.remind.FIRE"
        const val ACTION_DONE = "com.villamediana.fio.remind.DONE"
        const val ACTION_SNOOZE = "com.villamediana.fio.remind.SNOOZE"
        const val EXTRA_ENTRY = "entry"
        const val EXTRA_KIND = "kind"
        const val EXTRA_AT = "at"

        fun requestCode(entryId: Long, kind: Int): Int = (entryId * 2 + kind).toInt()
        fun notificationId(entryId: Long, kind: Int): Int = 10_000 + requestCode(entryId, kind)
    }
}

/** An alarm went off, or a notification button was tapped. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val entryId = intent.getLongExtra(Reminders.EXTRA_ENTRY, -1)
        if (entryId < 0) return
        val kind = intent.getIntExtra(Reminders.EXTRA_KIND, Reminders.REMIND)
        val graph = FioApplication.graph
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val db = graph.db
                val e = db.entries().get(entryId) ?: return@launch
                when (intent.action) {
                    Reminders.ACTION_FIRE -> fire(context, db, e, kind, intent.getLongExtra(Reminders.EXTRA_AT, 0))
                    Reminders.ACTION_DONE -> {
                        val now = System.currentTimeMillis()
                        db.entries().update(e.copy(status = Status.DONE.key, doneAt = now, updatedAt = now, checkInAt = null, remindAt = null))
                        db.progress().insert(Progress(entryId = e.id, at = now, text = "", status = Status.DONE.key))
                        graph.reminders.sync(listOf(e.id))
                        NotificationManagerCompat.from(context).cancel(Reminders.notificationId(e.id, kind))
                    }
                    Reminders.ACTION_SNOOZE -> {
                        val at = System.currentTimeMillis() + 60 * 60_000L
                        db.entries().update(if (kind == Reminders.CHECK_IN) e.copy(checkInAt = at) else e.copy(remindAt = at))
                        graph.reminders.sync(listOf(e.id))
                        NotificationManagerCompat.from(context).cancel(Reminders.notificationId(e.id, kind))
                    }
                }
            } finally {
                result.finish()
            }
        }
    }

    private suspend fun fire(context: Context, db: FioDatabase, e: Entry, kind: Int, at: Long) {
        // Moved or cleared since it was armed: this one is stale.
        val current = if (kind == Reminders.CHECK_IN) e.checkInAt else e.remindAt
        if (current == null || (at != 0L && current != at)) return
        if (kind == Reminders.CHECK_IN && !e.isOpenTask) return
        if (kind == Reminders.CHECK_IN) db.entries().update(e.copy(checkInAt = null)) // asked once; the answer may set the next
        else db.entries().update(e.copy(remindAt = null))
        notify(context, e, kind)
    }

    @SuppressLint("MissingPermission")
    private fun notify(context: Context, e: Entry, kind: Int) {
        val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed) return
        Notifications.createChannels(context)
        val id = Reminders.notificationId(e.id, kind)
        val open = PendingIntent.getActivity(
            context, id,
            MainActivity.openEntry(context, e.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val checkIn = kind == Reminders.CHECK_IN
        val text = when {
            checkIn -> e.title
            e.sensitive -> context.getString(R.string.notif_sensitive)
            e.body.isNotBlank() -> e.body.take(160)
            e.dueAt != null -> DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(e.dueAt))
            else -> ""
        }
        val b = NotificationCompat.Builder(context, if (checkIn) Notifications.CHANNEL_CHECKIN else Notifications.CHANNEL_REMIND)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.accent))
            .setContentTitle(if (checkIn) context.getString(R.string.notif_checkin_title) else e.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(if (checkIn) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_ALARM)
            .setPriority(if (checkIn) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
        if (checkIn) {
            val talk = PendingIntent.getActivity(
                context, id + 1,
                MainActivity.talkAbout(context, e.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(0, context.getString(R.string.notif_talk), talk)
        }
        if (e.kindEnum == Kind.TASK) b.addAction(0, context.getString(R.string.notif_done), action(context, Reminders.ACTION_DONE, e.id, kind))
        b.addAction(0, context.getString(R.string.notif_snooze), action(context, Reminders.ACTION_SNOOZE, e.id, kind))
        NotificationManagerCompat.from(context).notify(id, b.build())
    }

    private fun action(context: Context, action: String, entryId: Long, kind: Int): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).setAction(action)
            .putExtra(Reminders.EXTRA_ENTRY, entryId)
            .putExtra(Reminders.EXTRA_KIND, kind)
        val code = Reminders.requestCode(entryId, kind) * 4 + (if (action == Reminders.ACTION_DONE) 1 else 2)
        return PendingIntent.getBroadcast(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

/** After a reboot or an update, the alarms are armed again. */
class RearmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            intent.action != "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
        ) return
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                FioApplication.graph.reminders.syncAll()
            } finally {
                result.finish()
            }
        }
    }
}
