package com.lulucamera.app.generation

import android.content.Context
import com.lulucamera.app.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

class GenerationApiException(val code: String, val retryable: Boolean) : IOException(code)

data class RemoteGeneration(
    val id: String,
    val status: GenerationStatus,
    val errorCode: String?,
    val retryable: Boolean,
    val resultUrl: String?,
    val expiresAt: String?,
    val mode: String,
    val sha256: String,
    val resultBytes: Long,
    val width: Int,
    val height: Int,
)

class GenerationApi(context: Context) {
    private val baseUrl = BuildConfig.API_BASE_URL.trimEnd('/')
    private val sessionToken = sessionToken(context.applicationContext)
    val isConfigured: Boolean get() = !baseUrl.endsWith(".invalid") &&
        (baseUrl.startsWith("https://") || (BuildConfig.DEBUG && baseUrl.startsWith("http://")))

    suspend fun create(job: GenerationJob): RemoteGeneration {
        val imageType = "image/png".toMediaType()
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("idempotency_key", job.idempotencyKey)
            .addFormDataPart("original", "original.png", File(job.originalPath).asRequestBody(imageType))
            .addFormDataPart("reference", "instant.png", File(job.instantPath).asRequestBody(imageType))
            .addFormDataPart("mask", "edit-mask.png", File(job.maskPath).asRequestBody(imageType))
            .addFormDataPart("metadata", "metadata.json", File(job.metadataPath).asRequestBody("application/json".toMediaType()))
        val instances = File(File(job.originalPath).parentFile, "instances.png")
        if (instances.isFile) builder.addFormDataPart("instances", "instances.png", instances.asRequestBody(imageType))
        val body = builder.build()
        return executeJson(Request.Builder().url("$baseUrl/generations").post(body).authorized().build())
    }

    suspend fun get(jobId: String): RemoteGeneration = executeJson(
        Request.Builder().url("$baseUrl/generations/$jobId").authorized().build(),
    )

    suspend fun retry(jobId: String): RemoteGeneration = executeJson(
        Request.Builder().url("$baseUrl/generations/$jobId/retry").post(ByteArray(0).toRequestBody()).authorized().build(),
    )

    suspend fun cancel(jobId: String) {
        executeJson(Request.Builder().url("$baseUrl/generations/$jobId").delete().authorized().build())
    }

    suspend fun cancelRequest(idempotencyKey: String) {
        execute(Request.Builder().url("$baseUrl/generation-requests/$idempotencyKey").delete().authorized().build())
            .use { checkResponse(it) }
    }

    suspend fun download(remote: RemoteGeneration, target: File) {
        if (!remote.sha256.matches(Regex("[a-f0-9]{64}")) || remote.resultBytes !in 1..MAX_RESULT_BYTES) {
            throw GenerationApiException("INVALID_RESULT_METADATA", false)
        }
        val temporary = File(target.parentFile, "${target.name}.part")
        try {
            execute(Request.Builder().url("$baseUrl/generations/${remote.id}/result").authorized().build()).use { response ->
                checkResponse(response)
                val body = response.body ?: throw IOException("EMPTY_RESULT")
                var written = 0L
                val digest = MessageDigest.getInstance("SHA-256")
                body.byteStream().use { input -> temporary.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        written += count
                        if (written > MAX_RESULT_BYTES) throw GenerationApiException("INVALID_RESULT_SIZE", false)
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                } }
                val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                if (written != remote.resultBytes || hash != remote.sha256) throw IOException("RESULT_CHECKSUM_MISMATCH")
                if (!temporary.renameTo(target)) throw IOException("RESULT_MOVE_FAILED")
            }
        } finally {
            temporary.delete()
        }
    }

    private suspend fun executeJson(request: Request): RemoteGeneration = execute(request).use { response ->
        checkResponse(response)
        val json = JSONObject(response.body?.string().orEmpty())
        RemoteGeneration(
            id = json.getString("id"),
            status = GenerationStatus.valueOf(json.getString("status").uppercase(java.util.Locale.ROOT)),
            errorCode = json.optString("error_code").takeIf { it.isNotBlank() && it != "null" },
            retryable = json.optBoolean("retryable", false),
            resultUrl = json.optString("result_url").takeIf { it.isNotBlank() && it != "null" },
            expiresAt = json.optString("expires_at").takeIf { it.isNotBlank() },
            mode = json.optString("mode", "unknown"),
            sha256 = json.optString("result_sha256", ""),
            resultBytes = json.optLong("result_bytes", -1),
            width = json.optInt("result_width", 0), height = json.optInt("result_height", 0),
        )
    }

    private fun checkResponse(response: Response) {
        if (response.isSuccessful) return
        val detail = runCatching { JSONObject(response.body?.string().orEmpty()).optJSONObject("detail") }.getOrNull()
        throw GenerationApiException(
            detail?.optString("code") ?: "HTTP_${response.code}",
            detail?.optBoolean("retryable", false) ?: (response.code == 429 || response.code >= 500),
        )
    }

    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        })
    }

    private fun Request.Builder.authorized(): Request.Builder = header("X-Session-Token", sessionToken)

    private fun sessionToken(context: Context): String = synchronized(tokenLock) {
        val preferences = context.getSharedPreferences("generation-session", Context.MODE_PRIVATE)
        preferences.getString("token", null)?.let { return@synchronized it }
        val token = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        check(preferences.edit().putString("token", token).commit())
        token
    }

    private companion object {
        val tokenLock = Any()
        val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS).writeTimeout(90, TimeUnit.SECONDS)
            .callTimeout(2, TimeUnit.MINUTES).followRedirects(false).followSslRedirects(false).build()
        const val MAX_RESULT_BYTES = 30L * 1024 * 1024
    }
}
