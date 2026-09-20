package com.lulucamera.app.generation

import android.content.Context
import com.lulucamera.app.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

data class RemoteGeneration(
    val id: String,
    val status: GenerationStatus,
    val errorCode: String?,
    val retryable: Boolean,
    val resultUrl: String?,
    val expiresAt: String?,
)

class GenerationApi(context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .callTimeout(2, TimeUnit.MINUTES)
        .build()
    private val baseUrl = BuildConfig.API_BASE_URL.trimEnd('/')
    private val sessionToken = sessionToken(context.applicationContext)

    val isConfigured: Boolean get() = !baseUrl.endsWith(".invalid") &&
        (baseUrl.startsWith("https://") || (BuildConfig.DEBUG && baseUrl.startsWith("http://")))

    fun create(job: GenerationJob): RemoteGeneration {
        val imageType = "image/png".toMediaType()
        val jsonType = "application/json".toMediaType()
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("idempotency_key", job.idempotencyKey)
            .addFormDataPart("original", "original.png", File(job.originalPath).asRequestBody(imageType))
            .addFormDataPart("reference", "instant.png", File(job.instantPath).asRequestBody(imageType))
            .addFormDataPart("mask", "edit-mask.png", File(job.maskPath).asRequestBody(imageType))
            .addFormDataPart("metadata", "metadata.json", File(job.metadataPath).asRequestBody(jsonType))
            .build()
        return executeJson(
            Request.Builder().url("$baseUrl/generations").post(body).authorized().build(),
        )
    }

    fun get(jobId: String): RemoteGeneration = executeJson(
        Request.Builder().url("$baseUrl/generations/$jobId").get().authorized().build(),
    )

    fun cancel(jobId: String) {
        client.newCall(
            Request.Builder().url("$baseUrl/generations/$jobId").delete().authorized().build(),
        ).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP_${response.code}")
        }
    }

    fun download(jobId: String, target: File) {
        client.newCall(
            Request.Builder().url("$baseUrl/generations/$jobId/result").get().authorized().build(),
        ).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP_${response.code}")
            val body = response.body ?: throw IOException("EMPTY_RESULT")
            val temporary = File(target.parentFile, "${target.name}.part")
            body.byteStream().use { input -> temporary.outputStream().use(input::copyTo) }
            if (temporary.length() !in 1..MAX_RESULT_BYTES) {
                temporary.delete()
                throw IOException("INVALID_RESULT_SIZE")
            }
            target.delete()
            if (!temporary.renameTo(target)) {
                temporary.delete()
                throw IOException("RESULT_MOVE_FAILED")
            }
        }
    }

    private fun executeJson(request: Request): RemoteGeneration = client.newCall(request).execute().use { response ->
        val raw = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw IOException(parseErrorCode(raw) ?: "HTTP_${response.code}")
        val json = JSONObject(raw)
        RemoteGeneration(
            id = json.getString("id"),
            status = when (json.getString("status")) {
                "queued" -> GenerationStatus.QUEUED
                "running" -> GenerationStatus.RUNNING
                "completed" -> GenerationStatus.COMPLETED
                "failed" -> GenerationStatus.FAILED
                "cancelled" -> GenerationStatus.CANCELLED
                "expired" -> GenerationStatus.EXPIRED
                else -> GenerationStatus.FAILED
            },
            errorCode = json.optString("error_code").takeIf { it.isNotBlank() && it != "null" },
            retryable = json.optBoolean("retryable", false),
            resultUrl = json.optString("result_url").takeIf { it.isNotBlank() && it != "null" },
            expiresAt = json.optString("expires_at").takeIf { it.isNotBlank() },
        )
    }

    private fun Request.Builder.authorized(): Request.Builder = header("X-Session-Token", sessionToken)

    private fun parseErrorCode(raw: String): String? = runCatching {
        JSONObject(raw).optJSONObject("detail")?.optString("code")
    }.getOrNull()

    private fun sessionToken(context: Context): String {
        val preferences = context.getSharedPreferences("generation-session", Context.MODE_PRIVATE)
        preferences.getString("token", null)?.let { return it }
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val token = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        preferences.edit().putString("token", token).apply()
        return token
    }

    private companion object {
        const val MAX_RESULT_BYTES = 30L * 1024 * 1024
    }
}
