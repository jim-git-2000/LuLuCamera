package com.lulucamera.app.generation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

class CaptureCleanupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(LOCAL_RETENTION_DAYS)
        val root = File(applicationContext.filesDir, "captures")
        root.listFiles()?.filter { it.isDirectory && it.lastModified() < cutoff }?.forEach(File::deleteRecursively)
        GenerationDatabase.get(applicationContext).jobs().deleteFinishedBefore(cutoff)
        Result.success()
    }

    private companion object {
        const val LOCAL_RETENTION_DAYS = 7L
    }
}
