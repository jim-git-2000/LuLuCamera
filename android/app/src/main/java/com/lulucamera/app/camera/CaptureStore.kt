package com.lulucamera.app.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import com.lulucamera.app.model.CharacterId
import com.lulucamera.app.model.TrackState
import com.lulucamera.app.ui.TrackedCharacter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class CaptureSnapshot(
    val captureId: String,
    val originalPath: String,
    val instantPath: String,
    val maskPath: String,
    val metadataPath: String,
    val width: Int,
    val height: Int,
    val hasReplacements: Boolean,
    val createdAtMs: Long,
)

class CaptureStore(private val context: Context) {
    fun create(source: Bitmap, tracks: List<TrackedCharacter>, frontCamera: Boolean): CaptureSnapshot {
        val captureId = UUID.randomUUID().toString()
        val directory = File(context.filesDir, "captures/$captureId").apply { mkdirs() }
        val rendered = SnapshotCompositor.render(source, tracks)
        val mask = SnapshotCompositor.createEditMask(source.width, source.height, tracks)
        val original = File(directory, "original.png")
        val instant = File(directory, "instant.png")
        val editMask = File(directory, "edit-mask.png")
        val metadata = File(directory, "metadata.json")
        try {
            writePng(source, original)
            writePng(rendered, instant)
            writePng(mask, editMask)
            metadata.writeText(buildMetadata(captureId, source, tracks, frontCamera).toString(2))
            return CaptureSnapshot(
                captureId = captureId,
                originalPath = original.absolutePath,
                instantPath = instant.absolutePath,
                maskPath = editMask.absolutePath,
                metadataPath = metadata.absolutePath,
                width = source.width,
                height = source.height,
                hasReplacements = tracks.any {
                    it.track.state == TrackState.TRACKED && it.track.selectedCharacter != CharacterId.HUMAN
                },
                createdAtMs = System.currentTimeMillis(),
            )
        } catch (error: Exception) {
            directory.deleteRecursively()
            throw error
        } finally {
            rendered.recycle()
            mask.recycle()
        }
    }

    fun saveToGallery(path: String): Uri {
        val source = BitmapFactory.decodeFile(path) ?: error("照片文件不可读取")
        val name = "LuLuCamera_${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LuLuCamera")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        var uri: Uri? = null
        try {
            val inserted = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("无法创建相册文件")
            uri = inserted
            resolver.openOutputStream(inserted, "w")?.use { output ->
                check(source.compress(Bitmap.CompressFormat.PNG, 100, output)) { "照片编码失败" }
            } ?: error("无法写入相册")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(inserted, values, null, null)
            return inserted
        } catch (error: Exception) {
            uri?.let { resolver.delete(it, null, null) }
            throw error
        } finally {
            source.recycle()
        }
    }

    private fun writePng(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "照片编码失败" }
        }
    }

    private fun buildMetadata(
        captureId: String,
        source: Bitmap,
        tracks: List<TrackedCharacter>,
        frontCamera: Boolean,
    ): JSONObject = JSONObject().apply {
        put("schemaVersion", 1)
        put("captureId", captureId)
        put("width", source.width)
        put("height", source.height)
        put("frontCamera", frontCamera)
        put("orientation", "upright")
        put("characterAssetVersion", "vector-placeholder-v1")
        put("people", JSONArray().apply {
            tracks.forEach { tracked ->
                val track = tracked.track
                put(JSONObject().apply {
                    put("trackId", track.trackId)
                    put("character", track.selectedCharacter.name)
                    put("state", track.state.name)
                    put("confidence", track.observation.confidence.toDouble())
                    put("bounds", JSONArray(listOf(
                        track.observation.bounds.left,
                        track.observation.bounds.top,
                        track.observation.bounds.right,
                        track.observation.bounds.bottom,
                    )))
                    put("pose", JSONObject().apply {
                        track.observation.pose.joints.forEach { (joint, keypoint) ->
                            put(joint.name, JSONArray(listOf(
                                keypoint.point.x,
                                keypoint.point.y,
                                keypoint.visibility,
                                keypoint.presence,
                            )))
                        }
                    })
                })
            }
        })
    }
}
