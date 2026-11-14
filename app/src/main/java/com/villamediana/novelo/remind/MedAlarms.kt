package com.villamediana.novelo.remind

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
import com.villamediana.novelo.NoveloActivity
import com.villamediana.novelo.NoveloApplication
import com.villamediana.novelo.Notifications
import com.villamediana.novelo.R
import com.villamediana.novelo.data.Med
import com.villamediana.novelo.data.MedTaken
import com.villamediana.novelo.data.NoveloDatabase
import com.villamediana.novelo.meds.Doses
import com.villamediana.novelo.meds.MedText
import com.villamediana.novelo.settings.t
import com.villamediana.novelo.settings.tn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * The medicines' reminders: one alarm, always on the next time any of them is due. When it goes off,
 * the ones due then come in one notification together (one alone, its own), and the next time is
 * armed. A change to any medicine arms it again.
 */
class MedAlarms(private val context: Context, private val db: NoveloDatabase, private val zone: () -> ZoneId = { ZoneId.systemDefault() }) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    /** The next time after [after] armed (or none, when no medicine has one ahead). */
    suspend fun sync(after: Long = System.currentTimeMillis()) {
        val next = Doses.next(db.meds().all(), after, zone())
        arm(ACTION_FIRE, 0, next, next ?: 0L)
    }

    /** The ones of [at] again in a while (15 min). */
    fun again(at: Long) {
        arm(ACTION_AGAIN, 1 + minuteOfDay(at), System.currentTimeMillis() + SNOOZE_MS, at)
    }

    @SuppressLint("MissingPermission") // USE_EXACT_ALARM (33+) / SCHEDULE_EXACT_ALARM (≤32)
    private fun arm(action: String, code: Int, time: Long?, slot: Long) {
        val intent = Intent(context, MedReceiver::class.java).setAction(action).putExtra(EXTRA_AT, slot)
        val flags = PendingIntent.FLAG_IMMUTABLE or (if (time != null) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE)
        val pi = PendingIntent.getBroadcast(context, code, intent, flags) ?: return
        if (time == null) {
            alarms.cancel(pi)
            pi.cancel()
            return
        }
        val exact = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
        if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pi)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pi)
    }

    private fun minuteOfDay(at: Long): Int = Instant.ofEpochMilli(at).atZone(zone()).let { it.hour * 60 + it.minute }

    /** The notification of the time [at] (the same time each day is the same one). */
    fun notificationId(at: Long): Int = NOTIFICATION_BASE + minuteOfDay(at)

    /** The ones due at [at] not taken yet, in one notification ("Remédios das 08:00"); nothing when all were taken. */
    @SuppressLint("MissingPermission")
    suspend fun notify(at: Long) {
        val z = zone()
        val taken = db.meds().takenBetween(at, at).map { it.medId }.toSet()
        val due = Doses.at(db.meds().all(), at, z).filter { it.id !in taken }
        if (due.isEmpty()) return
        val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed) return
        Notifications.createChannels(context)
        val id = notificationId(at)
        val open = PendingIntent.getActivity(context, id, NoveloActivity.openMeds(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val time = Doses.hhmm(Instant.ofEpochMilli(at).atZone(z).toLocalTime())
        val b = NotificationCompat.Builder(context, Notifications.CHANNEL_MEDS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.accent))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setWhen(at)
        if (due.size == 1) {
            val m = due.first()
            val text = line(m, withName = false)
            b.setContentTitle(m.name + (MedText.amount(m) ?: m.strength)?.let { " · $it" }.orEmpty()).setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        } else {
            val lines = due.map { line(it, withName = true) }
            val style = NotificationCompat.InboxStyle().setBigContentTitle(t(R.string.med_notif_many, time))
            lines.forEach { style.addLine(it) }
            b.setContentTitle(t(R.string.med_notif_many, time)).setContentText(lines.joinToString(" · ")).setStyle(style)
                .setSubText(tn(R.plurals.med_count, due.size))
        }
        b.addAction(0, t(R.string.med_taken_action), action(ACTION_TAKEN, at, id))
        b.addAction(0, t(R.string.med_snooze), action(ACTION_SNOOZE, at, id))
        NotificationManagerCompat.from(context).notify(id, b.build())
    }

    /** "Amoxicilina · ½ comprimido · Cooper · depois de comer" (without the name when it is the title). */
    private fun line(m: Med, withName: Boolean): String = listOfNotNull(
        m.name.takeIf { withName },
        m.dose?.takeIf { it.isNotBlank() } ?: MedText.amount(m) ?: m.strength,
        m.person,
        m.notes.takeIf { it.isNotBlank() }?.take(60),
    ).joinToString(" · ")

    private fun action(action: String, at: Long, id: Int): PendingIntent {
        val intent = Intent(context, MedReceiver::class.java).setAction(action).putExtra(EXTRA_AT, at)
        return PendingIntent.getBroadcast(context, id * 4 + (if (action == ACTION_TAKEN) 1 else 2), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** All due at [at] taken (ticked in the app): its notification, if it is up, goes. */
    suspend fun dismissIfDone(at: Long) {
        val taken = db.meds().takenBetween(at, at).map { it.medId }.toSet()
        if (Doses.at(db.meds().all(), at, zone()).all { it.id in taken }) NotificationManagerCompat.from(context).cancel(notificationId(at))
    }

    /** Every one due at [at] (and not taken yet) taken now. */
    suspend fun takeAll(at: Long) {
        val now = System.currentTimeMillis()
        Doses.at(db.meds().all(), at, zone()).forEach { db.meds().take(MedTaken(it.id, at, now)) }
        NotificationManagerCompat.from(context).cancel(notificationId(at))
    }

    companion object {
        const val ACTION_FIRE = "com.villamediana.novelo.meds.FIRE"
        const val ACTION_AGAIN = "com.villamediana.novelo.meds.AGAIN"
        const val ACTION_TAKEN = "com.villamediana.novelo.meds.TAKEN"
        const val ACTION_SNOOZE = "com.villamediana.novelo.meds.SNOOZE"
        const val EXTRA_AT = "at"
        private const val NOTIFICATION_BASE = 40_000
        private const val SNOOZE_MS = 15 * 60_000L
    }
}

/** A medicine's time came (or came again), or a button of its notification was tapped. */
class MedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val at = intent.getLongExtra(MedAlarms.EXTRA_AT, 0L).takeIf { it > 0 } ?: return
        val alarms = NoveloApplication.graph.reminders.meds
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    MedAlarms.ACTION_FIRE -> {
                        alarms.notify(at)
                        // The next one after this (even when it went off a moment early).
                        alarms.sync(maxOf(System.currentTimeMillis(), at))
                    }
                    MedAlarms.ACTION_AGAIN -> alarms.notify(at)
                    MedAlarms.ACTION_TAKEN -> alarms.takeAll(at)
                    MedAlarms.ACTION_SNOOZE -> {
                        alarms.again(at)
                        NotificationManagerCompat.from(context).cancel(alarms.notificationId(at))
                    }
                }
            } finally {
                result.finish()
            }
        }
    }
}
