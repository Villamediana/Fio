package com.villamediana.fio

import android.app.Application
import com.villamediana.fio.backup.BackupWorker
import com.villamediana.fio.update.ExitReport
import com.villamediana.fio.update.UpdateCheckWorker
import com.villamediana.fio.update.UpdateIdleWorker
import kotlinx.coroutines.launch

class FioApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // If the app ever closes by a crash, its first lines are kept (the update sheet shows why).
        runCatching { ExitReport.catchCrashes(this) }
        graph = AppGraph(this)
        Notifications.createChannels(this)
        graph.scope.launch { runCatching { graph.reminders.syncAll() } }
        // One diary a day (days left with two from before become one).
        graph.scope.launch { runCatching { graph.library.joinDayDiaries() } }
        // The automatic backup, as Ajustes has it (kept when it is already scheduled).
        graph.scope.launch { runCatching { BackupWorker.schedule(this@FioApplication, graph.settings.current(), replace = false) } }
        if (graph.updates.enabled) runCatching { UpdateCheckWorker.schedule(this) }
        runCatching { UpdateIdleWorker.watch(this, graph.updates) }
    }

    companion object {
        lateinit var graph: AppGraph
            private set
    }
}
