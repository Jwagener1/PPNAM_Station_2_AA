package com.ppnam.station2aa.di

import android.content.Context
import androidx.room.Room
import androidx.work.*
import com.ppnam.station2aa.data.local.AppDatabase
import com.ppnam.station2aa.data.local.BomCacheDao
import com.ppnam.station2aa.data.local.OfflineQueueDao
import com.ppnam.station2aa.data.mqtt.MqttRepositoryImpl
import com.ppnam.station2aa.data.settings.SettingsRepository
import com.ppnam.station2aa.domain.repository.MqttRepository
import com.ppnam.station2aa.worker.OfflineQueueWorker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, "ppnam_station2.db").build()

    @Provides
    @Singleton
    fun provideOfflineQueueDao(db: AppDatabase): OfflineQueueDao = db.offlineQueueDao()

    @Provides
    @Singleton
    fun provideBomCacheDao(db: AppDatabase): BomCacheDao = db.bomCacheDao()

    @Provides
    @Singleton
    fun provideMqttRepository(impl: MqttRepositoryImpl): MqttRepository = impl

    @Provides
    @Singleton
    fun scheduleOfflineQueueWorker(
        @ApplicationContext ctx: Context,
        settingsRepository: SettingsRepository
    ): WorkManager {
        val intervalMin = runBlocking { settingsRepository.current().queueDrainIntervalMin }.toLong()
        val wm = WorkManager.getInstance(ctx)
        val request = PeriodicWorkRequestBuilder<OfflineQueueWorker>(intervalMin, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        wm.enqueueUniquePeriodicWork(
            "offline-queue-drain",
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
        return wm
    }
}
