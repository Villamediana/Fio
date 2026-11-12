package com.villamediana.novelo

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import androidx.compose.runtime.Composable
import com.villamediana.novelo.billing.Billing
import com.villamediana.novelo.billing.TestBilling
import com.villamediana.novelo.settings.t
import com.villamediana.novelo.ui.update.UpdateHost
import com.villamediana.novelo.ui.update.UpdateRow
import com.villamediana.novelo.update.ExitReport
import com.villamediana.novelo.update.UpdateCheckWorker
import com.villamediana.novelo.update.UpdateIdleWorker
import com.villamediana.novelo.update.UpdateNotifier
import com.villamediana.novelo.update.Updates
import com.villamediana.novelo.update.createUpdates

/**
 * What only the build outside Google Play has: its own updates, from the GitHub releases (the
 * FreeSong updater, in src/github). The Play build has a Store with nothing in it (src/play):
 * Google Play updates the app there, and an app from Play may not update itself.
 */
object Store {
    val updates: Updates by lazy {
        val graph = NoveloApplication.graph
        createUpdates(graph.appContext, graph.http, graph.scope)
    }

    /** Novelo+ here is simulated: the same screens, nothing charged (the server takes it as a test purchase). */
    val billing: Billing get() = TestBilling(NoveloApplication.graph.novelo)

    /** As the app starts, before anything else: a crash leaves its first lines (the update sheet shows why). */
    fun early(app: Application) {
        runCatching { ExitReport.catchCrashes(app) }
    }

    /** Once the app is set up: the updates' channel, the daily look for a new version, the update left for when the app is idle. */
    fun started(app: Application) {
        app.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(Notifications.CHANNEL_UPDATES, t(R.string.channel_updates), NotificationManager.IMPORTANCE_DEFAULT),
        )
        if (updates.enabled) runCatching { UpdateCheckWorker.schedule(app) }
        runCatching { UpdateIdleWorker.watch(app, updates) }
    }

    /** The app opened by the "new version" notification: its sheet. */
    fun opened(intent: Intent?) {
        if (intent?.getBooleanExtra(UpdateNotifier.EXTRA_OPEN, false) == true) updates.openSheet()
    }

    /** Over every screen: the update sheet. */
    @Composable
    fun Host() = UpdateHost()

    /** Under the version, at the end of Ajustes: the update line (none in a debug build). */
    val settingsLine: (@Composable () -> Unit)? get() = if (updates.enabled) ({ UpdateRow() }) else null
}
