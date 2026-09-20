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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class GenerationRepository(private val context: Context) {
    private val dao = GenerationDatabase.get(context).jobs()
    private val workManager = WorkManager.getInstance(context)
    val isConfigured: Boolean get() = GenerationApi(context).isConfigured

    suspend fun enqueue(snapshot: CaptureSnapshot): String {
        val localId = UUID.randomUUID().toString()
        dao.upsert(
            GenerationJob(
                localId = localId,
                remoteJobId = null,
                idempotencyKey = "capture-${snapshot.captureId}",
                originalPath = snapshot.originalPath,
                instantPath = snapshot.instantPath,
                maskPath = snapshot.maskPath,
                metadataPath = snapshot.metadataPath,
                resultPath = null,
                status = GenerationStatus.LOCAL_PENDING,
                errorCode = null,
                retryable = true,
                createdAtMs = System.currentTimeMillis(),
                updatedAtMs = System.currentTimeMillis(),
                expiresAt = null,
            ),
        )
        val request = OneTimeWorkRequestBuilder<GenerationWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(Data.Builder().putString(GenerationWorker.LOCAL_ID, localId).build())
            .build()
        workManager.enqueueUniqueWork(workName(localId), ExistingWorkPolicy.KEEP, request)
        return localId
    }

    fun observe(localId: String): Flow<GenerationJob?> = dao.observe(localId)

    suspend fun retry(localId: String) {
        val job = dao.get(localId) ?: return
        dao.upsert(job.copy(
            remoteJobId = null,
            status = GenerationStatus.LOCAL_PENDING,
            errorCode = null,
            updatedAtMs = System.currentTimeMillis(),
        ))
        val request = OneTimeWorkRequestBuilder<GenerationWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(Data.Builder().putString(GenerationWorker.LOCAL_ID, localId).build())
            .build()
        workManager.enqueueUniqueWork(workName(localId), ExistingWorkPolicy.REPLACE, request)
    }

    suspend fun cancel(localId: String) = withContext(Dispatchers.IO) {
        workManager.cancelUniqueWork(workName(localId))
        val job = dao.get(localId) ?: return@withContext
        job.remoteJobId?.let { runCatching { GenerationApi(context).cancel(it) } }
        dao.upsert(job.copy(status = GenerationStatus.CANCELLED, updatedAtMs = System.currentTimeMillis()))
    }

    private fun workName(localId: String) = "generation-$localId"
}
