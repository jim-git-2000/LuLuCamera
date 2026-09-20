package com.lulucamera.app.generation

import android.content.Context
import android.graphics.BitmapFactory
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class GenerationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private val dao = GenerationDatabase.get(context).jobs()
    private val api = GenerationApi(context)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val localId = inputData.getString(LOCAL_ID) ?: return@withContext Result.failure()
        var job = dao.get(localId) ?: return@withContext Result.failure()
        if (!api.isConfigured) {
            dao.upsert(job.failed("SERVICE_NOT_CONFIGURED", retryable = false))
            return@withContext Result.failure()
        }
        try {
            var remote = if (job.remoteJobId == null) {
                api.create(job).also { created ->
                    job = job.copy(
                        remoteJobId = created.id,
                        status = created.status,
                        updatedAtMs = System.currentTimeMillis(),
                        expiresAt = created.expiresAt,
                    )
                    dao.upsert(job)
                }
            } else {
                api.get(job.remoteJobId)
            }
            repeat(MAX_POLLS) {
                if (isStopped) return@withContext Result.failure()
                job = job.copy(
                    status = remote.status,
                    errorCode = remote.errorCode,
                    retryable = remote.retryable,
                    updatedAtMs = System.currentTimeMillis(),
                    expiresAt = remote.expiresAt,
                )
                dao.upsert(job)
                when (remote.status) {
                    GenerationStatus.COMPLETED -> {
                        val directory = File(job.originalPath).parentFile ?: throw IOException("CAPTURE_DIRECTORY_MISSING")
                        val resultFile = File(directory, "hd-result.png")
                        api.download(remote.id, resultFile)
                        val bounds = BitmapFactory.Options().also { options ->
                            options.inJustDecodeBounds = true
                            BitmapFactory.decodeFile(resultFile.absolutePath, options)
                        }
                        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 8192 || bounds.outHeight > 8192) {
                            resultFile.delete()
                            throw IOException("INVALID_RESULT_DIMENSIONS")
                        }
                        dao.upsert(job.copy(resultPath = resultFile.absolutePath, updatedAtMs = System.currentTimeMillis()))
                        return@withContext Result.success()
                    }
                    GenerationStatus.FAILED -> {
                        if (remote.retryable) {
                            job = job.copy(remoteJobId = null, updatedAtMs = System.currentTimeMillis())
                            dao.upsert(job)
                            return@withContext Result.retry()
                        }
                        return@withContext Result.failure()
                    }
                    GenerationStatus.CANCELLED, GenerationStatus.EXPIRED -> return@withContext Result.failure()
                    else -> Unit
                }
                delay(POLL_INTERVAL_MS)
                remote = api.get(remote.id)
            }
            dao.upsert(job.failed("POLL_TIMEOUT", retryable = true))
            Result.retry()
        } catch (error: IOException) {
            dao.upsert(job.failed(error.message ?: "NETWORK_ERROR", retryable = true))
            Result.retry()
        } catch (error: Exception) {
            dao.upsert(job.failed(error.message ?: "CLIENT_ERROR", retryable = false))
            Result.failure()
        }
    }

    private fun GenerationJob.failed(code: String, retryable: Boolean) = copy(
        status = GenerationStatus.FAILED,
        errorCode = code.take(64),
        retryable = retryable,
        updatedAtMs = System.currentTimeMillis(),
    )

    companion object {
        const val LOCAL_ID = "local_id"
        private const val MAX_POLLS = 120
        private const val POLL_INTERVAL_MS = 5_000L
    }
}
