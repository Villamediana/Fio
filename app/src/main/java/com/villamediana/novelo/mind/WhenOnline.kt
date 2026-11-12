package com.villamediana.novelo.mind

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.villamediana.novelo.NoveloApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * What she said while offline goes by itself when the network comes back: a capture that stopped
 * for lack of it schedules this, Android runs it once there is a network (the app open or not),
 * and it stays until what was waiting went through (or [LIMIT_MS]).
 */
class WhenOnline(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val mind = NoveloApplication.graph.mind
        mind.retryPending()
        withTimeoutOrNull(LIMIT_MS) {
            delay(1_000)
            while (mind.working) delay(1_000)
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "when-online"

        /** Under WorkManager's 10 minutes. */
        private const val LIMIT_MS = 9 * 60_000L

        /**
         * Once there is a network. A short wait first: a network that is there but doesn't reach
         * the internet (a hotel Wi-Fi) doesn't make it go round and round.
         */
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<WhenOnline>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
