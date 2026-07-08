package com.ppnam.station2aa

import android.app.Application
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.ppnam.station2aa.data.rfid.DataWedgeReceiver
import com.ppnam.station2aa.data.settings.SettingsRepository
import com.ppnam.station2aa.worker.OfflineQueueWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class PpnamApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var dataWedgeReceiver: DataWedgeReceiver
    @Inject lateinit var settingsRepository: SettingsRepository

    // Application-lifetime scope for startup work that must not block the main thread
    // (e.g. reading settings before scheduling periodic work) and must not be tied to
    // any single screen's ViewModel, which would cancel it on navigation.
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(DataWedgeReceiver.ACTION_SCAN)
            addAction(DataWedgeReceiver.ACTION_CHAINWAY_BARCODE)
            addAction(DataWedgeReceiver.ACTION_CHAINWAY_RFID)
        }
        ContextCompat.registerReceiver(this, dataWedgeReceiver, filter, ContextCompat.RECEIVER_EXPORTED)

        applicationScope.launch {
            scheduleOfflineQueueDrain()
        }
    }

    private suspend fun scheduleOfflineQueueDrain() {
        val intervalMin = settingsRepository.current().queueDrainIntervalMin.toLong()
        val request = PeriodicWorkRequestBuilder<OfflineQueueWorker>(intervalMin, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "offline-queue-drain",
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}
