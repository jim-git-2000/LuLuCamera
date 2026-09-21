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
        val dao = GenerationDatabase.get(applicationContext).jobs()
        val activeDirectories = dao.active().mapNotNull { File(it.originalPath).parent }.toSet()
        root.listFiles()?.filter {
            it.isDirectory && it.lastModified() < cutoff && it.absolutePath !in activeDirectories
        }?.forEach(File::deleteRecursively)
        dao.deleteFinishedBefore(cutoff)
        Result.success()
    }

    private companion object {
        const val LOCAL_RETENTION_DAYS = 7L
    }
}
