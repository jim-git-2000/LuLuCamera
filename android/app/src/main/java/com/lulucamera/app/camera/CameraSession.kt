package com.lulucamera.app.camera

import android.content.Context
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.tasks.core.Delegate
import com.lulucamera.app.vision.PoseLandmarkerEngine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraSession(
    context: Context,
    private val onVisionResult: (PoseLandmarkerEngine.VisionResult) -> Unit,
    private val onError: (String) -> Unit,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val analyzerExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val throttle = AnalysisThrottle()
    private val engine = runCatching {
        PoseLandmarkerEngine(
            context = appContext,
            delegate = Delegate.CPU,
            listener = object : PoseLandmarkerEngine.Listener {
                override fun onResult(result: PoseLandmarkerEngine.VisionResult) {
                    throttle.recordInference(result.inferenceTimeMs)
                    onVisionResult(result)
                }
                override fun onError(message: String) = onError(message)
            },
        )
    }.onFailure {
        onError(it.message ?: "人体识别模型初始化失败")
    }.getOrNull()
    private var provider: ProcessCameraProvider? = null

    fun bind(lifecycleOwner: LifecycleOwner, previewView: PreviewView, frontCamera: Boolean) {
        val providerFuture = ProcessCameraProvider.getInstance(appContext)
        providerFuture.addListener(
            {
                runCatching {
                    val cameraProvider = providerFuture.get()
                    provider = cameraProvider
                    cameraProvider.unbindAll()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()
                        .also { useCase ->
                            useCase.setAnalyzer(analyzerExecutor) { image ->
                                if (engine != null && throttle.shouldAnalyze(SystemClock.uptimeMillis())) {
                                    engine.detect(image, mirrorInput = frontCamera)
                                } else {
                                    image.close()
                                }
                            }
                        }
                    val selector = if (frontCamera) {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    } else {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    }
                    cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                }.onFailure { onError(it.message ?: "相机启动失败") }
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    override fun close() {
        provider?.unbindAll()
        engine?.close()
        analyzerExecutor.shutdown()
    }
}
