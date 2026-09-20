package com.lulucamera.app.generation

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class GenerationStatus {
    LOCAL_PENDING,
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    EXPIRED,
}

@Entity(tableName = "generation_jobs")
data class GenerationJob(
    @PrimaryKey val localId: String,
    val remoteJobId: String?,
    val idempotencyKey: String,
    val originalPath: String,
    val instantPath: String,
    val maskPath: String,
    val metadataPath: String,
    val resultPath: String?,
    val status: GenerationStatus,
    val errorCode: String?,
    val retryable: Boolean,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val expiresAt: String?,
)
