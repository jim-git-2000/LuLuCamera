package com.lulucamera.app.camera

import android.content.Context
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.LifecycleOwner
import com.lulucamera.app.vision.PoseLandmarkerEngine
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class CameraSession(
    context: Context,
    private val onVisionResult: (PoseLandmarkerEngine.VisionResult) -> Unit,
    private val onError: (String) -> Unit,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)
    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private val throttle = AnalysisThrottle()
    private val closed = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    // 只在 analyzerExecutor 上访问 engine。
    private var engine: PoseLandmarkerEngine? = null
    private var initializationFailed = false
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null

    fun bind(lifecycleOwner: LifecycleOwner, previewView: PreviewView, frontCamera: Boolean) {
        val token = generation.incrementAndGet()
        previewView.doOnLayout {
            val future = ProcessCameraProvider.getInstance(appContext)
            future.addListener({
                if (closed.get() || token != generation.get()) return@addListener
                runCatching {
                    val cameraProvider = future.get().also { provider = it }
                    unbindOwned()
                    analyzerExecutor.execute {
                        engine?.close(); engine = null; initializationFailed = false
                    }
                    val selector = if (frontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                    check(cameraProvider.hasCamera(selector)) { "当前设备没有该镜头，请切换另一镜头" }
                    val nextPreview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                    val nextAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
                    nextAnalysis.setAnalyzer(analyzerExecutor) { image ->
                        try {
                            if (closed.get() || token != generation.get() || !throttle.shouldAnalyze(SystemClock.uptimeMillis())) return@setAnalyzer
                            if (engine == null && !initializationFailed) {
                                try { engine = PoseLandmarkerEngine(appContext) }
                                catch (error: Exception) { initializationFailed = true; throw error }
                            }
                            val result = engine?.detect(image, frontCamera) ?: return@setAnalyzer
                            throttle.recordInference(result.inferenceTimeMs)
                            mainExecutor.execute {
                                if (closed.get() || token != generation.get()) result.frameBitmap.recycle()
                                else onVisionResult(result)
                            }
                        } catch (error: Exception) {
                            mainExecutor.execute {
                                if (!closed.get() && token == generation.get()) onError("人体识别暂不可用，请重新打开相机")
                            }
                        } finally { image.close() }
                    }
                    preview = nextPreview; analysis = nextAnalysis
                    val group = UseCaseGroup.Builder().addUseCase(nextPreview).addUseCase(nextAnalysis)
                    previewView.viewPort?.let { group.setViewPort(it) }
                    cameraProvider.bindToLifecycle(lifecycleOwner, selector, group.build())
                }.onFailure { onError(it.message ?: "相机启动失败，请重试") }
            }, mainExecutor)
        }
    }

    private fun unbindOwned() {
        analysis?.clearAnalyzer()
        preview?.let { provider?.unbind(it) }
        analysis?.let { provider?.unbind(it) }
        preview = null; analysis = null
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        generation.incrementAndGet()
        unbindOwned()
        analyzerExecutor.execute { engine?.close(); engine = null }
        analyzerExecutor.shutdown()
    }
}
