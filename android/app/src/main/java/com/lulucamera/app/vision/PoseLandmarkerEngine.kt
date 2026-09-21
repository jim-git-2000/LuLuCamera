package com.lulucamera.app.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.lulucamera.app.model.*
import java.nio.ByteOrder

/** 仅在同一个 CameraX 分析线程上创建、调用和关闭；背压由 CameraX 管理。 */
class PoseLandmarkerEngine(context: Context, delegate: Delegate = Delegate.CPU, maxPoses: Int = 3) : AutoCloseable {
    data class VisionResult(
        val frameId: Long, val timestampMs: Long, val imageWidth: Int, val imageHeight: Int,
        val inferenceTimeMs: Long, val observations: List<PersonObservation>,
        /** 接收方取得所有权。 */
        val frameBitmap: Bitmap,
    )
    private var frameCounter = 0L
    private var lastTimestamp = 0L
    private val landmarker = PoseLandmarker.createFromOptions(context,
        PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setDelegate(delegate)
                .setModelAssetPath("models/pose_landmarker_lite.task").build())
            .setRunningMode(RunningMode.VIDEO).setNumPoses(maxPoses).setOutputSegmentationMasks(true)
            .setMinPoseDetectionConfidence(.5f).setMinPosePresenceConfidence(.5f)
            .setMinTrackingConfidence(.5f).build())

    fun detect(image: ImageProxy, mirrorInput: Boolean): VisionResult {
        val start = SystemClock.uptimeMillis()
        val timestamp = maxOf(start, lastTimestamp + 1).also { lastTimestamp = it }
        val frameId = ++frameCounter
        val rotation = image.imageInfo.rotationDegrees
        val crop = image.cropRect
        // CameraX 自带转换处理 rowStride / pixelStride，不能假设 RGBA 行紧密排列。
        val raw = image.toBitmap()
        val matrix = Matrix().apply {
            postRotate(rotation.toFloat())
            if (mirrorInput) postScale(-1f, 1f)
        }
        val oriented = try { Bitmap.createBitmap(raw, crop.left, crop.top, crop.width(), crop.height(), matrix, true) }
        catch (error: Exception) { raw.recycle(); throw error }
        if (raw !== oriented) raw.recycle()
        try {
            BitmapImageBuilder(oriented).build().use { input ->
                val result = landmarker.detectForVideo(input, timestamp)
                val masks = result.segmentationMasks().orElse(emptyList())
                try {
                    val observations = result.landmarks().mapIndexedNotNull { index, landmarks ->
                        val joints = Joint.entries.mapNotNull { joint ->
                            landmarks.getOrNull(joint.mediaPipeIndex)?.let { landmark ->
                                joint to Keypoint(PointN(landmark.x(), landmark.y()),
                                    landmark.visibility().orElse(0f), landmark.presence().orElse(0f))
                            }
                        }.toMap()
                        val pose = PersonPose(joints)
                        val bounds = RectN.enclosing(pose.reliablePoints, padding = .06f) ?: return@mapIndexedNotNull null
                        val mask = masks.getOrNull(index)?.let { native ->
                            val buffer = ByteBufferExtractor.extract(native).order(ByteOrder.nativeOrder()).asFloatBuffer()
                            require(buffer.remaining() >= native.width * native.height)
                            val width = minOf(native.width, 256)
                            val height = minOf(native.height, 256)
                            PersonMask(width, height, ByteArray(width * height) { offset ->
                                val x = (offset % width) * native.width / width
                                val y = (offset / width) * native.height / height
                                val value = buffer.get(y * native.width + x)
                                (if (value.isFinite()) (value.coerceIn(0f, 1f) * 255).toInt() else 0).toByte()
                            })
                        }
                        PersonObservation(frameId, timestamp, index, bounds, pose, mask != null,
                            joints.values.count(Keypoint::isReliable).toFloat() / Joint.entries.size, mask)
                    }
                    return VisionResult(frameId, timestamp, oriented.width, oriented.height,
                        (SystemClock.uptimeMillis() - start).coerceAtLeast(0), observations,
                        oriented.copy(Bitmap.Config.ARGB_8888, false))
                } finally { masks.forEach { it.close() } }
            }
        } catch (error: Exception) {
            oriented.recycle()
            throw error
        }
    }
    override fun close() = landmarker.close()
}
