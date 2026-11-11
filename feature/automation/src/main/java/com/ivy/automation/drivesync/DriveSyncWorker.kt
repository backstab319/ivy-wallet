package com.ivy.automation.drivesync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ivy.automation.AutomationEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

class DriveSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val syncManager = AutomationEntryPoint.get(applicationContext).driveSyncManager()
        return when (syncManager.sync()) {
            is SyncResult.Failed -> if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
            // Nothing a background job can do about the others
            else -> Result.success()
        }
    }

    private companion object {
        const val MAX_RETRIES = 3
    }
}

@Singleton
class DriveSyncScheduler @Inject constructor(
    @ApplicationContext
    private val context: Context,
    private val syncManager: dagger.Lazy<DriveSyncManager>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val onlyWithInternet = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /**
     * Call when data may have changed (the app is opened or left, an automatic entry
     * was added). Does nothing when Drive sync is off.
     */
    fun syncSoonIfEnabled(delaySeconds: Long = 10) {
        scope.launch {
            if (syncManager.get().isEnabled()) {
                syncSoon(delaySeconds)
                schedulePeriodic()
            }
        }
    }

    fun syncSoon(delaySeconds: Long = 0) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_TIME_WORK,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<DriveSyncWorker>()
                .setConstraints(onlyWithInternet)
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .build()
        )
    }

    fun schedulePeriodic() {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DriveSyncWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                .setConstraints(onlyWithInternet)
                .build()
        )
    }

    fun cancelAll() {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(ONE_TIME_WORK)
            cancelUniqueWork(PERIODIC_WORK)
        }
    }

    private companion object {
        const val ONE_TIME_WORK = "drive_sync_once"
        const val PERIODIC_WORK = "drive_sync_periodic"
        const val PERIOD_HOURS = 6L
    }
}
