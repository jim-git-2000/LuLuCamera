package com.lulucamera.app.generation

import android.content.Context
import android.graphics.BitmapFactory
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
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
        if (job.status == GenerationStatus.CANCELLED && job.errorCode != "CANCEL_PENDING") return@withContext Result.success()
        if (!api.isConfigured) {
            dao.updateUnlessCancelled(job.failed("SERVICE_NOT_CONFIGURED", false))
            return@withContext Result.failure()
        }
        try {
            if (job.errorCode == "CANCEL_PENDING") {
                // 只上传取消标记，不为取得未知 Job ID 再次上传照片。
                api.cancelRequest(job.idempotencyKey)
                dao.upsert(job.copy(status = GenerationStatus.CANCELLED, errorCode = null))
                return@withContext Result.success()
            }
            val remoteId = job.remoteJobId
            var remote = when {
                remoteId == null -> {
                    val created = api.create(job)
                    if (job.errorCode == "USER_RETRY" && created.status == GenerationStatus.FAILED && created.retryable) {
                        job = job.copy(remoteJobId = created.id)
                        api.retry(created.id)
                    } else created
                }
                job.errorCode == "USER_RETRY" -> api.retry(remoteId)
                else -> api.get(remoteId)
            }
            job = job.copy(remoteJobId = remote.id, errorCode = null, updatedAtMs = System.currentTimeMillis())
            repeat(48) {
                if (dao.get(localId)?.status == GenerationStatus.CANCELLED) return@withContext Result.success()
                job = job.copy(status = remote.status, errorCode = remote.errorCode, retryable = remote.retryable,
                    expiresAt = remote.expiresAt, updatedAtMs = System.currentTimeMillis())
                if (!dao.updateUnlessCancelled(job)) return@withContext Result.success()
                when (remote.status) {
                    GenerationStatus.COMPLETED -> {
                        val directory = File(job.originalPath).parentFile ?: throw IOException("CAPTURE_DIRECTORY_MISSING")
                        val result = File(directory, "hd-result.png")
                        api.download(remote, result)
                        val bounds = BitmapFactory.Options().also {
                            it.inJustDecodeBounds = true
                            BitmapFactory.decodeFile(result.absolutePath, it)
                        }
                        if (bounds.outWidth != remote.width || bounds.outHeight != remote.height ||
                            remote.width !in 8..2048 || remote.height !in 8..2048) {
                            result.delete()
                            throw GenerationApiException("INVALID_RESULT_DIMENSIONS", false)
                        }
                        if (dao.get(localId)?.status == GenerationStatus.CANCELLED) {
                            result.delete()
                            return@withContext Result.success()
                        }
                        File(directory, "generation-mode.txt").writeText(remote.mode)
                        if (!dao.updateUnlessCancelled(job.copy(resultPath = result.absolutePath))) result.delete()
                        return@withContext Result.success()
                    }
                    // 模型失败交由用户主动重试，避免后台无限重复 GPU 计费。
                    GenerationStatus.FAILED, GenerationStatus.CANCELLED, GenerationStatus.EXPIRED -> return@withContext Result.failure()
                    else -> Unit
                }
                delay(5_000)
                remote = api.get(remote.id)
            }
            // 本次运行有界，后续交给 WorkManager 调度并继续查询原任务。
            Result.retry()
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            if (dao.get(localId)?.status == GenerationStatus.CANCELLED && job.errorCode != "CANCEL_PENDING") return@withContext Result.success()
            val retryable = (error as? GenerationApiException)?.retryable ?: true
            val code = (error as? GenerationApiException)?.code ?: "NETWORK_ERROR"
            if (job.errorCode != "CANCEL_PENDING") dao.updateUnlessCancelled(job.failed(code, retryable))
            if (retryable && runAttemptCount < 5) Result.retry() else Result.failure()
        } catch (error: Exception) {
            if (dao.get(localId)?.status != GenerationStatus.CANCELLED) dao.updateUnlessCancelled(job.failed("CLIENT_ERROR", false))
            Result.failure()
        }
    }

    private fun GenerationJob.failed(code: String, retryable: Boolean) = copy(
        status = GenerationStatus.FAILED, errorCode = code, retryable = retryable,
        updatedAtMs = System.currentTimeMillis(),
    )

    companion object { const val LOCAL_ID = "local_id" }
}
