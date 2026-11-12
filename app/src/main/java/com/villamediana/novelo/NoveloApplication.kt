package com.villamediana.novelo

import com.villamediana.novelo.settings.Lang

import android.app.Application
import com.villamediana.novelo.backup.BackupWorker
import com.villamediana.novelo.update.ExitReport
import com.villamediana.novelo.update.UpdateCheckWorker
import com.villamediana.novelo.update.UpdateIdleWorker
import kotlinx.coroutines.launch

class NoveloApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // The app's own words in her language (Portuguese unless she chose another).
        Lang.init(this)
        // If the app ever closes by a crash, its first lines are kept (the update sheet shows why).
        runCatching { ExitReport.catchCrashes(this) }
        graph = AppGraph(this)
        Notifications.createChannels(this)
        graph.scope.launch { runCatching { graph.reminders.syncAll() } }
        // One diary a day (days left with two from before become one).
        graph.scope.launch { runCatching { graph.library.joinDayDiaries() } }
        // A backup after every change in what she keeps (and the daily one besides).
        graph.scope.launch { runCatching { graph.backups.watch(graph.db) } }
        // The automatic backup, as Ajustes has it (kept when it is already scheduled).
        graph.scope.launch { runCatching { BackupWorker.schedule(this@NoveloApplication, graph.settings.current(), replace = false) } }
        if (graph.updates.enabled) runCatching { UpdateCheckWorker.schedule(this) }
        runCatching { UpdateIdleWorker.watch(this, graph.updates) }
        // Done while she was away (the app minimized or closed): a notification says so.
        runCatching { WorkDone.watch(this, graph.scope, graph.mind.state) }
    }

    companion object {
        lateinit var graph: AppGraph
            private set
    }
}
