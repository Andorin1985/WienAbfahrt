package com.rwa.wienerlinien

import android.app.Application
import android.os.Build
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.*
import com.rwa.wienerlinien.data.api.RateLimitInterceptor
import com.rwa.wienerlinien.data.api.WienerLinienApi
import com.rwa.wienerlinien.data.repository.DepartureRepository
import com.rwa.wienerlinien.data.repository.StopRepository
import com.rwa.wienerlinien.data.worker.StopUpdateWorker
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

private val Application.dataStore by preferencesDataStore(name = "settings")

class WienerLinienApplication : Application() {

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(RateLimitInterceptor())
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val api by lazy { WienerLinienApi(okHttpClient) }

    val stopRepository by lazy { StopRepository(this) }

    val departureRepository by lazy {
        DepartureRepository(this, api, dataStore)
    }

    override fun onCreate() {
        super.onCreate()
        scheduleWeeklyStopUpdate()
    }

    private fun scheduleWeeklyStopUpdate() {
        val request = PeriodicWorkRequestBuilder<StopUpdateWorker>(7, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            StopUpdateWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}
