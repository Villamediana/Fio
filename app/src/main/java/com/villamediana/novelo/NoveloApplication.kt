package com.villamediana.novelo

import com.villamediana.novelo.settings.Lang

import android.app.Application
import com.villamediana.novelo.backup.BackupWorker
import kotlinx.coroutines.launch

class NoveloApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // The app's own words in her language (Portuguese unless she chose another).
        Lang.init(this)
        // What only the GitHub build has (its own updates) starts here; the Play build has none.
        Store.early(this)
        graph = AppGraph(this)
        Notifications.createChannels(this)
        graph.scope.launch { runCatching { graph.reminders.syncAll() } }
        // One diary a day (days left with two from before become one).
        graph.scope.launch { runCatching { graph.library.joinDayDiaries() } }
        // The automatic backup, as Ajustes has it (kept when it is already scheduled).
        graph.scope.launch { runCatching { BackupWorker.schedule(this@NoveloApplication, graph.settings.current(), replace = false) } }
        Store.started(this)
        // The plan and what is left of the day, fresh for the screens (the Novelo's intelligence only).
        graph.scope.launch { if (graph.settings.current().think == com.villamediana.novelo.settings.Source.NOVELO) runCatching { graph.novelo.refresh() } }
    }

    companion object {
        lateinit var graph: AppGraph
            private set
    }
}
