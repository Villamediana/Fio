package com.villamediana.fio.local

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.villamediana.fio.FioApplication

/**
 * The instructions run on the phone's model and kept, ahead of the first thing said. As work of
 * its own, it gets to the end with the app off the screen too (right after an update, say).
 */
class PrepareWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        runCatching { FioApplication.graph.thinking.prepare() }
        return Result.success()
    }

    companion object {
        private const val NAME = "prepare-local"

        /** One at a time: asked again while it runs, the one running goes on. */
        fun start(context: Context) {
            runCatching { WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<PrepareWorker>().build()) }
        }
    }
}
