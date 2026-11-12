package com.villamediana.novelo.update

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
import com.villamediana.novelo.NoveloActivity
import com.villamediana.novelo.NoveloApplication
import com.villamediana.novelo.Notifications
import com.villamediana.novelo.R
import com.villamediana.novelo.settings.t
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the app running while the new version downloads: minimized, or with the screen off, Android
 * freezes an app soon after (the download then dies halfway: "a conexão caiu"). A foreground service
 * with the download's progress in a quiet notification isn't frozen. It leaves when the download
 * does (well or not); the install itself is Android's screen, back in the app.
 */
class UpdateService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watch: Job? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The foreground promise is kept even when the download already ended: then it leaves at once.
        show(null)
        if (!wanted) {
            leave()
            return START_NOT_STICKY
        }
        holdWake()
        if (watch == null) {
            watch = scope.launch {
                NoveloApplication.graph.updates.state.collect { s -> if (s is UpdateState.Downloading) show(s.fraction) }
            }
        }
        return START_NOT_STICKY
    }

    private fun show(fraction: Float?) {
        Notifications.createChannels(this)
        val open = PendingIntent.getActivity(this, 0, Intent(this, NoveloActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, Notifications.CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(this, R.color.accent))
            .setContentTitle(t(R.string.update_downloading_note))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, ((fraction ?: 0f) * 100).toInt(), fraction == null)
            .build()
        runCatching { ServiceCompat.startForeground(this, Notifications.ID_UPDATE_DOWNLOAD, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) }
    }

    /** The CPU awake with the screen off while it downloads (20 minutes at most). */
    private fun holdWake() {
        if (wake?.isHeld == true) return
        wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "novelo:update").apply {
            setReferenceCounted(false)
            acquire(20 * 60 * 1000L)
        }
    }

    fun leave() {
        watch?.cancel()
        watch = null
        wake?.takeIf { it.isHeld }?.release()
        wake = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Android 15 caps this kind of work: when it is over, the service leaves (the download may still end on screen). */
    override fun onTimeout(startId: Int, fgsType: Int) = leave()

    override fun onDestroy() {
        if (instance === this) instance = null
        scope.cancel()
        wake?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object {
        @Volatile private var wanted = false
        @Volatile private var instance: UpdateService? = null
        private val main = Handler(Looper.getMainLooper())

        /** On while the new version downloads, off when it is done (or failed). */
        fun hold(context: Context, on: Boolean) {
            wanted = on
            main.post {
                val s = instance
                when {
                    on && s == null -> runCatching { ContextCompat.startForegroundService(context, Intent(context, UpdateService::class.java)) }.onFailure { wanted = false }
                    !on && s != null -> s.leave()
                }
            }
        }
    }
}
