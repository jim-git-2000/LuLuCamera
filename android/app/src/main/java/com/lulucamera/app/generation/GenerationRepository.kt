package com.lulucamera.app.generation

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.lulucamera.app.camera.CaptureSnapshot
import kotlinx.coroutines.flow.Flow

class GenerationRepository(context: Context) {
    private val dao = GenerationDatabase.get(context).jobs()
    private val workManager = WorkManager.getInstance(context)
    val isConfigured: Boolean = GenerationApi(context).isConfigured

    suspend fun enqueue(snapshot: CaptureSnapshot): String {
        val existing = dao.forCapture(snapshot.originalPath)
        if (existing != null) return existing.localId
        val localId = snapshot.captureId
        dao.upsert(GenerationJob(
            localId = localId, remoteJobId = null, idempotencyKey = "capture-${snapshot.captureId}",
            originalPath = snapshot.originalPath, instantPath = snapshot.instantPath,
            maskPath = snapshot.maskPath, metadataPath = snapshot.metadataPath, resultPath = null,
            status = GenerationStatus.LOCAL_PENDING, errorCode = null, retryable = true,
            createdAtMs = System.currentTimeMillis(), updatedAtMs = System.currentTimeMillis(), expiresAt = null,
        ))
        schedule(localId)
        return localId
    }

    fun observeRecent(): Flow<List<GenerationJob>> = dao.observeRecent()

    fun observe(localId: String): Flow<GenerationJob?> = dao.observe(localId)
    suspend fun forCapture(path: String): GenerationJob? = dao.forCapture(path)

    suspend fun resumePending() {
        dao.active().forEach { schedule(it.localId) }
    }

    suspend fun retry(localId: String) {
        val job = dao.get(localId) ?: return
        if (job.status != GenerationStatus.FAILED || !job.retryable) return
        dao.upsert(job.copy(status = GenerationStatus.LOCAL_PENDING, errorCode = "USER_RETRY",
            updatedAtMs = System.currentTimeMillis()))
        schedule(localId, ExistingWorkPolicy.REPLACE)
    }

    suspend fun cancel(localId: String) {
        val job = dao.get(localId) ?: return
        dao.upsert(job.copy(status = GenerationStatus.CANCELLED, errorCode = "CANCEL_PENDING",
            updatedAtMs = System.currentTimeMillis()))
        // 替换原查询任务；取消请求也持久化，断网时等待网络恢复。
        schedule(localId, ExistingWorkPolicy.REPLACE)
    }

    private fun schedule(localId: String, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
        val request = OneTimeWorkRequestBuilder<GenerationWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(Data.Builder().putString(GenerationWorker.LOCAL_ID, localId).build())
            .build()
        workManager.enqueueUniqueWork("generation-$localId", policy, request)
    }
}
