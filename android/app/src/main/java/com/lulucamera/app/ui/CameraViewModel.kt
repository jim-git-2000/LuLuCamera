package com.lulucamera.app.ui

import android.app.Application
import android.content.Context
import java.io.File
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.lulucamera.app.character.CharacterCatalog
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lulucamera.app.camera.CaptureSnapshot
import com.lulucamera.app.camera.CaptureStore
import com.lulucamera.app.character.CharacterPose
import com.lulucamera.app.character.PoseMapper
import com.lulucamera.app.generation.GenerationJob
import com.lulucamera.app.generation.GenerationRepository
import com.lulucamera.app.model.CharacterId
import com.lulucamera.app.model.PersonTrack
import com.lulucamera.app.model.PointN
import com.lulucamera.app.model.TrackState
import com.lulucamera.app.tracking.PersonTracker
import com.lulucamera.app.vision.PoseLandmarkerEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TrackedCharacter(val track: PersonTrack, val pose: CharacterPose?)

enum class AppPage { CAMERA, PREVIEW }
enum class PreviewVariant { ORIGINAL, INSTANT, HD }

data class CameraUiState(
    val page: AppPage = AppPage.CAMERA,
    val tracks: List<TrackedCharacter> = emptyList(),
    val selectedTrackId: Long? = null,
    val imageWidth: Int = 1,
    val imageHeight: Int = 1,
    val inferenceTimeMs: Long = 0,
    val frontCamera: Boolean = false,
    val capture: CaptureSnapshot? = null,
    val previewVariant: PreviewVariant = PreviewVariant.INSTANT,
    val generationJob: GenerationJob? = null,
    val generationMode: String = "unknown",
    val assetsReady: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
)

class CameraViewModel(application: Application) : AndroidViewModel(application) {
    private data class LatestFrame(val bitmap: Bitmap, val tracks: List<TrackedCharacter>, val timestampMs: Long)

    private val preferences = application.getSharedPreferences("active-capture", Context.MODE_PRIVATE)
    private val tracker = PersonTracker()
    private val poseMapper = PoseMapper()
    private val captureStore = CaptureStore(application)
    private val generationRepository = GenerationRepository(application)
    private val mutableState = MutableStateFlow(CameraUiState())
    private val frameLock = Any()
    private var latestFrame: LatestFrame? = null
    // UI 持有的 Bitmap 交给 GC 释放，不能在 RenderThread 使用期间主动 recycle。
    var previewFrame: Bitmap? by mutableStateOf(null)
        private set
    private var generationObserver: Job? = null
    val state: StateFlow<CameraUiState> = mutableState.asStateFlow()
    val recentJobs = generationRepository.observeRecent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val generationConfigured: Boolean get() = generationRepository.isConfigured

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { CharacterCatalog.load(application) }
            mutableState.update { it.copy(assetsReady = true) }
            runCatching {
                generationRepository.resumePending()
                val id = preferences.getString("captureId", null) ?: return@runCatching
                val snapshot = withContext(Dispatchers.IO) { captureStore.restore(id) } ?: return@runCatching
                // 恢复期间若用户已经拍了新照片，不覆盖新状态。
                if (mutableState.value.capture != null || mutableState.value.busy) return@runCatching
                mutableState.update { it.copy(page = AppPage.PREVIEW, capture = snapshot) }
                generationRepository.forCapture(snapshot.originalPath)?.let {
                    if (mutableState.value.capture?.captureId == snapshot.captureId) observeGeneration(it.localId)
                }
            }.onFailure { showError("上次任务恢复失败，可重新拍照") }
        }
    }

    fun onVisionResult(result: PoseLandmarkerEngine.VisionResult) {
        if (mutableState.value.page != AppPage.CAMERA) { result.frameBitmap.recycle(); return }
        val tracks = tracker.update(result.observations)
        poseMapper.retain(tracks.map { it.trackId }.toSet())
        val characters = tracks.map { track ->
            TrackedCharacter(track, poseMapper.map(track.trackId, track.observation.pose))
        }
        synchronized(frameLock) {
            latestFrame = LatestFrame(result.frameBitmap, characters, result.timestampMs)
            previewFrame = result.frameBitmap
        }
        val selected = mutableState.value.selectedTrackId
            ?.takeIf { id -> tracks.any { it.trackId == id && it.state != TrackState.REMOVED } }
        mutableState.update {
            it.copy(
                tracks = characters,
                selectedTrackId = selected,
                imageWidth = result.imageWidth,
                imageHeight = result.imageHeight,
                inferenceTimeMs = result.inferenceTimeMs,
            )
        }
    }

    fun selectAt(point: PointN) {
        mutableState.update { it.copy(selectedTrackId = tracker.hitTest(point)?.trackId) }
    }

    fun chooseCharacter(character: CharacterId) {
        val trackId = mutableState.value.selectedTrackId ?: return
        if (tracker.selectCharacter(trackId, character)) {
            val characters = tracker.snapshot().map { track ->
                TrackedCharacter(track, poseMapper.map(track.trackId, track.observation.pose))
            }
            mutableState.update { it.copy(tracks = characters) }
            synchronized(frameLock) {
                latestFrame = latestFrame?.copy(tracks = characters)
            }
        }
    }

    fun switchCamera() {
        tracker.reset()
        poseMapper.retain(emptySet())
        previewFrame = null
        synchronized(frameLock) {
            latestFrame = null
        }
        mutableState.update {
            it.copy(frontCamera = !it.frontCamera, tracks = emptyList(), selectedTrackId = null)
        }
    }

    fun capture() {
        if (mutableState.value.busy || !mutableState.value.assetsReady) return
        val frozen = synchronized(frameLock) {
            latestFrame?.let { LatestFrame(it.bitmap.copy(Bitmap.Config.ARGB_8888, false), it.tracks, it.timestampMs) }
        }
        if (frozen == null || android.os.SystemClock.uptimeMillis() - frozen.timestampMs > 1500) {
            frozen?.bitmap?.recycle()
            showError("相机画面尚未准备好，请稍后重试")
            return
        }
        mutableState.update { it.copy(busy = true, message = "正在冻结当前画面…") }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    frozen.bitmap.useBitmap {
                        captureStore.create(it, frozen.tracks, mutableState.value.frontCamera)
                    }
                }
            }.onSuccess { snapshot ->
                preferences.edit().putString("captureId", snapshot.captureId).apply()
                mutableState.update {
                    it.copy(
                        page = AppPage.PREVIEW,
                        capture = snapshot,
                        previewVariant = PreviewVariant.INSTANT,
                        generationJob = null,
                        generationMode = "unknown",
                        busy = false,
                        message = null,
                    )
                }
            }.onFailure { error ->
                mutableState.update { it.copy(busy = false, message = error.message ?: "拍照失败") }
            }
        }
    }

    fun showPreview(variant: PreviewVariant) {
        val state = mutableState.value
        if (variant == PreviewVariant.HD && state.generationJob?.resultPath == null) return
        mutableState.update { it.copy(previewVariant = variant) }
    }

    fun saveCurrent() {
        val state = mutableState.value
        if (state.busy) return
        val path = when (state.previewVariant) {
            PreviewVariant.ORIGINAL -> state.capture?.originalPath
            PreviewVariant.INSTANT -> state.capture?.instantPath
            PreviewVariant.HD -> state.generationJob?.resultPath
        } ?: return
        mutableState.update { it.copy(busy = true, message = "正在保存到相册…") }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { captureStore.saveToGallery(path) } }
                .onSuccess {
                    mutableState.update { value -> value.copy(busy = false, message = "已保存到 Pictures/LuLuCamera") }
                }
                .onFailure { error ->
                    mutableState.update { value -> value.copy(busy = false, message = error.message ?: "保存失败") }
                }
        }
    }

    fun requestGeneration() {
        val snapshot = mutableState.value.capture ?: return
        if (!snapshot.hasReplacements) {
            showError("这张照片没有选择噜噜，请重拍并先为人物选择噜噜 A 或 B")
            return
        }
        if (!generationConfigured) {
            showError("高清服务尚未配置，原图和即时噜噜图仍可正常保存")
            return
        }
        if (mutableState.value.generationJob != null || mutableState.value.busy) return
        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching { generationRepository.enqueue(snapshot) }
                .onSuccess { observeGeneration(it) }
                .onFailure { showError("无法创建任务，请稍后重试") }
            mutableState.update { it.copy(busy = false) }
        }
    }

    fun openGeneration(job: GenerationJob) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            val captureId = File(job.originalPath).parentFile?.name
            val snapshot = withContext(Dispatchers.IO) { captureId?.let(captureStore::restore) }
            if (snapshot == null) {
                mutableState.update { it.copy(busy = false, message = "本地照片已清理，请查看已保存的相册照片") }
            } else {
                preferences.edit().putString("captureId", snapshot.captureId).apply()
                mutableState.update { it.copy(page = AppPage.PREVIEW, capture = snapshot, generationJob = null,
                    previewVariant = PreviewVariant.INSTANT, busy = false, message = null) }
                observeGeneration(job.localId)
            }
        }
    }

    fun retryGeneration() {
        val localId = mutableState.value.generationJob?.localId ?: return
        viewModelScope.launch { runCatching { generationRepository.retry(localId) }.onFailure { showError("重试失败，请稍后再试") } }
    }

    fun cancelGeneration() {
        val localId = mutableState.value.generationJob?.localId ?: return
        viewModelScope.launch { runCatching { generationRepository.cancel(localId) }.onFailure { showError("取消失败，请稍后再试") } }
    }

    fun retake() {
        if (mutableState.value.busy) return
        tracker.reset()
        poseMapper.retain(emptySet())
        previewFrame = null
        synchronized(frameLock) { latestFrame = null }
        preferences.edit().remove("captureId").apply()
        generationObserver?.cancel()
        mutableState.update {
            it.copy(
                page = AppPage.CAMERA,
                capture = null,
                generationJob = null,
                previewVariant = PreviewVariant.INSTANT,
                message = null,
            )
        }
    }

    fun showError(message: String) {
        mutableState.update { it.copy(message = message) }
    }

    fun clearMessage() {
        mutableState.update { it.copy(message = null) }
    }

    override fun onCleared() {
        synchronized(frameLock) {
            latestFrame = null
        }
        super.onCleared()
    }

    private fun observeGeneration(localId: String) {
        generationObserver?.cancel()
        generationObserver = viewModelScope.launch {
            generationRepository.observe(localId).collect { job ->
                val mode = withContext(Dispatchers.IO) {
                    job?.resultPath?.let { path ->
                        runCatching { File(File(path).parentFile, "generation-mode.txt").readText() }.getOrNull()
                    } ?: "unknown"
                }
                mutableState.update { current ->
                    val variant = if (job?.resultPath != null) PreviewVariant.HD else current.previewVariant
                    current.copy(generationJob = job, previewVariant = variant, generationMode = mode)
                }
            }
        }
    }

    private inline fun <T> Bitmap.useBitmap(block: (Bitmap) -> T): T = try {
        block(this)
    } finally {
        recycle()
    }
}
