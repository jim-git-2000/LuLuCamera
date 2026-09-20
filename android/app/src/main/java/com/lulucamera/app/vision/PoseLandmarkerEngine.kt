package com.lulucamera.app.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import com.lulucamera.app.model.Joint
import com.lulucamera.app.model.Keypoint
import com.lulucamera.app.model.PersonObservation
import com.lulucamera.app.model.PersonPose
import com.lulucamera.app.model.PointN
import com.lulucamera.app.model.RectN
import java.util.concurrent.atomic.AtomicLong

class PoseLandmarkerEngine(
    context: Context,
    private val listener: Listener,
    delegate: Delegate = Delegate.CPU,
    maxPoses: Int = 3,
) : AutoCloseable {
    interface Listener {
        fun onResult(result: VisionResult)
        fun onError(message: String)
    }

    data class VisionResult(
        val frameId: Long,
        val timestampMs: Long,
        val imageWidth: Int,
        val imageHeight: Int,
        val inferenceTimeMs: Long,
        val observations: List<PersonObservation>,
        /** 接收方取得所有权，使用完毕后必须 recycle。 */
        val frameBitmap: Bitmap,
    )

    private data class PendingFrame(val frameId: Long, val bitmap: Bitmap)

    private val frameCounter = AtomicLong(0)
    private val submittedFrames = mutableMapOf<Long, PendingFrame>()
    private val landmarker: PoseLandmarker

    init {
        val baseOptions = BaseOptions.builder()
            .setDelegate(delegate)
            .setModelAssetPath(MODEL_PATH)
            .build()
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumPoses(maxPoses)
            .setOutputSegmentationMasks(true)
            .setMinPoseDetectionConfidence(0.5f)
            .setMinPosePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener(::onMediaPipeResult)
            .setErrorListener { error -> listener.onError(error.message ?: "人体识别失败") }
            .build()
        landmarker = PoseLandmarker.createFromOptions(context, options)
    }

    fun detect(imageProxy: ImageProxy, mirrorInput: Boolean) {
        val frameId = frameCounter.incrementAndGet()
        val timestamp = SystemClock.uptimeMillis()
        val rotation = imageProxy.imageInfo.rotationDegrees
        val sourceWidth = imageProxy.width
        val sourceHeight = imageProxy.height
        val bitmap = Bitmap.createBitmap(sourceWidth, sourceHeight, Bitmap.Config.ARGB_8888)
        var oriented: Bitmap? = null
        try {
            imageProxy.use { bitmap.copyPixelsFromBuffer(it.planes[0].buffer) }
            val transform = Matrix().apply {
                postRotate(rotation.toFloat())
                if (mirrorInput) postScale(-1f, 1f)
            }
            val orientedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true)
            oriented = orientedBitmap
            if (orientedBitmap !== bitmap) bitmap.recycle()
            val mpImage = BitmapImageBuilder(orientedBitmap).build()
            synchronized(submittedFrames) {
                submittedFrames[timestamp] = PendingFrame(frameId, orientedBitmap)
                while (submittedFrames.size > 12) {
                    submittedFrames.remove(submittedFrames.keys.first())?.bitmap?.recycle()
                }
            }
            landmarker.detectAsync(mpImage, timestamp)
        } catch (error: RuntimeException) {
            if (!bitmap.isRecycled) bitmap.recycle()
            oriented?.takeIf { it !== bitmap && !it.isRecycled }?.recycle()
            listener.onError(error.message ?: "无法处理相机帧")
        }
    }

    override fun close() {
        synchronized(submittedFrames) {
            submittedFrames.values.forEach { it.bitmap.recycle() }
            submittedFrames.clear()
        }
        landmarker.close()
    }

    private fun onMediaPipeResult(result: PoseLandmarkerResult, input: MPImage) {
        val timestamp = result.timestampMs()
        val pending = synchronized(submittedFrames) { submittedFrames.remove(timestamp) } ?: return
        val frameId = pending.frameId
        val segmentationMasks = result.segmentationMasks().orElse(emptyList())
        val observations = result.landmarks().mapIndexedNotNull { index, landmarks ->
            val joints = Joint.entries.mapNotNull { joint ->
                landmarks.getOrNull(joint.mediaPipeIndex)?.let { landmark ->
                    joint to Keypoint(
                        point = PointN(landmark.x(), landmark.y()),
                        visibility = landmark.visibility().orElse(0f),
                        presence = landmark.presence().orElse(0f),
                    )
                }
            }.toMap()
            val pose = PersonPose(joints)
            val bounds = RectN.enclosing(pose.reliablePoints, padding = 0.06f) ?: return@mapIndexedNotNull null
            val reliableRatio = joints.values.count(Keypoint::isReliable).toFloat() / Joint.entries.size
            PersonObservation(
                frameId = frameId,
                timestampMs = timestamp,
                sourceIndex = index,
                bounds = bounds,
                pose = pose,
                hasSegmentationMask = index < segmentationMasks.size,
                confidence = reliableRatio,
            )
        }
        listener.onResult(
            VisionResult(
                frameId = frameId,
                timestampMs = timestamp,
                imageWidth = input.width,
                imageHeight = input.height,
                inferenceTimeMs = (SystemClock.uptimeMillis() - timestamp).coerceAtLeast(0),
                observations = observations,
                frameBitmap = pending.bitmap,
            ),
        )
    }

    private companion object {
        const val MODEL_PATH = "models/pose_landmarker_lite.task"
    }
}
