package com.lulucamera.app.generation

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Upsert
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface GenerationJobDao {
    @Upsert
    suspend fun upsert(job: GenerationJob)

    @Query("SELECT * FROM generation_jobs WHERE localId = :localId")
    suspend fun get(localId: String): GenerationJob?

    @Query("SELECT * FROM generation_jobs ORDER BY createdAtMs DESC LIMIT 30")
    fun observeRecent(): Flow<List<GenerationJob>>

    @Query("SELECT * FROM generation_jobs WHERE localId = :localId")
    fun observe(localId: String): Flow<GenerationJob?>

    @Query("SELECT * FROM generation_jobs WHERE status IN ('LOCAL_PENDING', 'QUEUED', 'RUNNING') OR errorCode = 'CANCEL_PENDING'")
    suspend fun active(): List<GenerationJob>

    @Query("SELECT * FROM generation_jobs WHERE originalPath = :path ORDER BY createdAtMs DESC LIMIT 1")
    suspend fun forCapture(path: String): GenerationJob?

    @Transaction
    suspend fun updateUnlessCancelled(job: GenerationJob): Boolean {
        if (get(job.localId)?.status == GenerationStatus.CANCELLED) return false
        upsert(job)
        return true
    }

    @Query("DELETE FROM generation_jobs WHERE updatedAtMs < :beforeMs AND status IN ('COMPLETED', 'FAILED', 'CANCELLED', 'EXPIRED') AND (errorCode IS NULL OR errorCode != 'CANCEL_PENDING')")
    suspend fun deleteFinishedBefore(beforeMs: Long): Int
}

class GenerationConverters {
    @TypeConverter
    fun statusToString(value: GenerationStatus): String = value.name

    @TypeConverter
    fun stringToStatus(value: String): GenerationStatus = GenerationStatus.valueOf(value)
}

@Database(entities = [GenerationJob::class], version = 1, exportSchema = false)
@TypeConverters(GenerationConverters::class)
abstract class GenerationDatabase : RoomDatabase() {
    abstract fun jobs(): GenerationJobDao

    companion object {
        @Volatile private var instance: GenerationDatabase? = null

        fun get(context: Context): GenerationDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                GenerationDatabase::class.java,
                "generation-jobs.db",
            ).build().also { instance = it }
        }
    }
}
