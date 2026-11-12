package com.villamediana.novelo.audio

import com.villamediana.novelo.settings.t
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.villamediana.novelo.NoveloApplication
import com.villamediana.novelo.NoveloActivity
import com.villamediana.novelo.Notifications
import com.villamediana.novelo.R
import com.villamediana.novelo.mind.MicHolder

/**
 * Keeps Novelo running while the screen is off or the app is in the background: a foreground service
 * that holds the microphone while recording ("quanto tempo eu quiser", with a "Parar" button) and
 * then the work while what was said is transcribed and organized — the same service, so between
 * the two there is no moment the app could be frozen or closed. Leaves when both are done.
 */
class MicService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            NoveloApplication.graph.mind.stopListening()
            refresh()
            return START_NOT_STICKY
        }
        // Android wants the foreground promise kept even when nothing is wanted any more (a very
        // short recording let go before this ran): kept here, and the service leaves right after.
        foreground()
        running = true
        refresh()
        return START_NOT_STICKY
    }

    /** Brings the notification and the kind of foreground in line with what is wanted now, or leaves. */
    fun refresh() {
        if (!recording && !working) {
            releaseWake()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        foreground()
        holdWake()
    }

    private fun foreground() {
        Notifications.createChannels(this)
        val open = PendingIntent.getActivity(this, 0, Intent(this, NoveloActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, Notifications.CHANNEL_MIC)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(this, R.color.accent))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (recording) {
            val stop = PendingIntent.getService(this, 1, Intent(this, MicService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
            b.setContentTitle(t(R.string.mic_listening))
                .setUsesChronometer(true)
                .setWhen(since)
                .addAction(0, t(R.string.mic_stop), stop)
        } else {
            b.setContentTitle(t(R.string.work_going)).setProgress(0, 0, true)
        }
        // Recording is the microphone; the work after it is just the app finishing a job.
        val type = if (recording) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or (if (working) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        runCatching { ServiceCompat.startForeground(this, Notifications.ID_MIC, b.build(), type) }
            .onFailure { if (!recording) { working = false; refresh() } }
    }

    /** The CPU stays awake with the screen off while recording or working (an hour at most). */
    private fun holdWake() {
        if (wake?.isHeld == true) return
        wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "novelo:work").apply {
            setReferenceCounted(false)
            acquire(60 * 60 * 1000L)
        }
    }

    private fun releaseWake() {
        wake?.takeIf { it.isHeld }?.release()
        wake = null
    }

    /** Android 15 caps background work time: when it is over, the service leaves (what was not done stays waiting). */
    override fun onTimeout(startId: Int, fgsType: Int) {
        working = false
        refresh()
    }

    override fun onDestroy() {
        running = false
        if (instance === this) instance = null
        releaseWake()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "com.villamediana.novelo.mic.STOP"

        /** The microphone is open (set by [AndroidMicHolder]). */
        @Volatile var recording = false

        /** Something said is being transcribed or organized. */
        @Volatile var working = false

        /** When the recording started (the notification's clock). */
        @Volatile var since = 0L

        /** onStartCommand already ran: stopping it now is safe. */
        @Volatile var running = false

        @Volatile var instance: MicService? = null
    }
}

class AndroidMicHolder(private val context: Context) : MicHolder {
    private val main = Handler(Looper.getMainLooper())

    /** Recording starts from the button, on screen: the service is (re)started for it. */
    override fun hold() {
        MicService.recording = true
        MicService.since = System.currentTimeMillis()
        main.post { start() }
    }

    override fun release() {
        MicService.recording = false
        main.post { MicService.instance?.takeIf { MicService.running }?.refresh() }
    }

    /**
     * The work after a recording finds the service already running and just changes it (allowed
     * in the background too); a photo or a retry starts it from the screen.
     */
    override fun working(on: Boolean) {
        MicService.working = on
        main.post {
            val s = MicService.instance
            if (s != null && MicService.running) s.refresh() else if (on) start()
        }
    }

    private fun start() {
        if (!MicService.recording && !MicService.working) return
        runCatching { ContextCompat.startForegroundService(context, Intent(context, MicService::class.java)) }
            .onFailure { if (!MicService.recording) MicService.working = false }
    }
}
