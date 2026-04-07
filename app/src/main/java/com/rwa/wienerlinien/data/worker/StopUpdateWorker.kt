package com.rwa.wienerlinien.data.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rwa.wienerlinien.WienerLinienApplication

/**
 * Downloads the three OGD CSV files from Wiener Linien and rebuilds
 * haltestellen_mit_linien.json in internal storage.
 *
 * Scheduled weekly via WorkManager in WienerLinienApplication.
 * The actual download/parse logic lives in StopRepository.downloadAndUpdate().
 */
class StopUpdateWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val WORK_NAME = "stop_update_weekly"
    }

    override suspend fun doWork(): Result {
        val success = (applicationContext as WienerLinienApplication)
            .stopRepository
            .downloadAndUpdate()
        return if (success) Result.success() else Result.retry()
    }
}
