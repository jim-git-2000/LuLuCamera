package com.lulucamera.app

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.lulucamera.app.generation.CaptureCleanupWorker
import java.util.concurrent.TimeUnit

class LuLuApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val cleanup = PeriodicWorkRequestBuilder<CaptureCleanupWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "capture-retention-cleanup",
            ExistingPeriodicWorkPolicy.KEEP,
            cleanup,
        )
    }
}
