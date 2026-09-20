package com.lulucamera.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lulucamera.app.camera.CameraSession
import com.lulucamera.app.generation.GenerationStatus
import com.lulucamera.app.model.CharacterId

@Composable
fun CameraScreen(viewModel: CameraViewModel) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
    }
    LaunchedEffect(state.page) {
        if (state.page == AppPage.CAMERA && !hasPermission) requestPermission.launch(Manifest.permission.CAMERA)
    }
    when {
        state.page == AppPage.PREVIEW && state.capture != null -> PreviewContent(state, viewModel)
        hasPermission -> CameraContent(state, viewModel)
        else -> PermissionRequired(onRequest = { requestPermission.launch(Manifest.permission.CAMERA) })
    }
}

@Composable
private fun CameraContent(state: CameraUiState, viewModel: CameraViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val session = remember {
        CameraSession(
            context = context,
            onVisionResult = viewModel::onVisionResult,
            onError = viewModel::showError,
        )
    }
    DisposableEffect(session) { onDispose { session.close() } }
    LaunchedEffect(state.frontCamera, lifecycleOwner) {
        session.bind(lifecycleOwner, previewView, state.frontCamera)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        CharacterOverlay(state = state, modifier = Modifier.fillMaxSize(), onTap = viewModel::selectAt)
        Row(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusChip("${state.tracks.count { it.track.state.name == "TRACKED" }} 人")
            StatusChip("识别 ${state.inferenceTimeMs} ms")
        }
        Button(
            onClick = viewModel::switchCamera,
            enabled = !state.busy,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 42.dp, end = 16.dp),
        ) { Text(if (state.frontCamera) "后置" else "前置") }
        state.message?.let { MessageCard(it, viewModel::clearMessage, Modifier.align(Alignment.Center)) }
        CharacterPicker(
            visible = state.selectedTrackId != null,
            busy = state.busy,
            onChoose = viewModel::chooseCharacter,
            onCapture = viewModel::capture,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun PreviewContent(state: CameraUiState, viewModel: CameraViewModel) {
    val capture = requireNotNull(state.capture)
    val generation = state.generationJob
    var showConsent by remember { mutableStateOf(false) }
    val path = when (state.previewVariant) {
        PreviewVariant.ORIGINAL -> capture.originalPath
        PreviewVariant.INSTANT -> capture.instantPath
        PreviewVariant.HD -> generation?.resultPath ?: capture.instantPath
    }
    val bitmap = remember(path) { BitmapFactory.decodeFile(path)?.asImageBitmap() }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = "拍照预览",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(color = Color(0xCC18130F), shape = RoundedCornerShape(22.dp)) {
                Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PreviewButton("原图", state.previewVariant == PreviewVariant.ORIGINAL) {
                        viewModel.showPreview(PreviewVariant.ORIGINAL)
                    }
                    PreviewButton("即时噜噜", state.previewVariant == PreviewVariant.INSTANT) {
                        viewModel.showPreview(PreviewVariant.INSTANT)
                    }
                    if (generation?.resultPath != null) {
                        PreviewButton("高清", state.previewVariant == PreviewVariant.HD) {
                            viewModel.showPreview(PreviewVariant.HD)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("${capture.width} × ${capture.height} · 实时分析帧", color = Color.White)
        }
        state.message?.let { MessageCard(it, viewModel::clearMessage, Modifier.align(Alignment.Center)) }
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GenerationStatusCard(generation?.status, generation?.errorCode)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = viewModel::retake, enabled = !state.busy) { Text("重拍") }
                Button(onClick = viewModel::saveCurrent, enabled = !state.busy) { Text("保存当前图") }
            }
            Spacer(Modifier.height(8.dp))
            when (generation?.status) {
                GenerationStatus.FAILED -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = viewModel::retryGeneration, enabled = generation.retryable) { Text("重试高清") }
                    OutlinedButton(onClick = viewModel::cancelGeneration) { Text("取消任务") }
                }
                GenerationStatus.LOCAL_PENDING, GenerationStatus.QUEUED, GenerationStatus.RUNNING ->
                    OutlinedButton(onClick = viewModel::cancelGeneration) { Text("取消高清") }
                GenerationStatus.COMPLETED -> Unit
                else -> Button(onClick = {
                    if (viewModel.generationConfigured) showConsent = true else viewModel.requestGeneration()
                }) { Text("生成高清噜噜") }
            }
        }
    }
    if (showConsent) {
        AlertDialog(
            onDismissRequest = { showConsent = false },
            title = { Text("上传照片生成高清版本") },
            text = { Text("将上传原图、即时噜噜图、人物姿态和编辑掩码。普通拍照始终在本机完成；服务结果默认 24 小时过期，本地原图和即时图会保留。") },
            confirmButton = {
                Button(onClick = { showConsent = false; viewModel.requestGeneration() }) { Text("同意并生成") }
            },
            dismissButton = { OutlinedButton(onClick = { showConsent = false }) { Text("暂不") } },
        )
    }
}

@Composable
private fun GenerationStatusCard(status: GenerationStatus?, errorCode: String?) {
    val label = when (status) {
        GenerationStatus.LOCAL_PENDING -> "等待网络上传"
        GenerationStatus.QUEUED -> "高清任务排队中"
        GenerationStatus.RUNNING -> "正在生成高清噜噜"
        GenerationStatus.COMPLETED -> "高清结果已就绪"
        GenerationStatus.FAILED -> "高清失败：${errorCode ?: "未知原因"}"
        GenerationStatus.CANCELLED -> "高清任务已取消"
        GenerationStatus.EXPIRED -> "高清结果已过期"
        null -> "原图和即时噜噜图仅保存在本机"
    }
    Surface(color = Color(0xCC18130F), shape = RoundedCornerShape(16.dp)) {
        Text(label, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp), color = Color.White)
    }
}

@Composable
private fun PreviewButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Color(0xFF9A6545) else Color.Transparent,
        ),
    ) { Text(label) }
}

@Composable
private fun CharacterPicker(
    visible: Boolean,
    busy: Boolean,
    onChoose: (CharacterId) -> Unit,
    onCapture: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (visible) {
            Surface(color = Color(0xCC18130F), shape = RoundedCornerShape(24.dp)) {
                Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CharacterButton("噜噜 A", Color(0xFF9A6545)) { onChoose(CharacterId.LULU_A) }
                    CharacterButton("噜噜 B", Color(0xFFD6B28B)) { onChoose(CharacterId.LULU_B) }
                    CharacterButton("真人", Color(0xFF666666)) { onChoose(CharacterId.HUMAN) }
                }
            }
            Spacer(Modifier.height(16.dp))
        } else {
            Text("点击画面中的人物选择噜噜", color = Color.White)
            Spacer(Modifier.height(16.dp))
        }
        Box(
            Modifier.size(76.dp).background(Color.White, CircleShape).clickable(enabled = !busy, onClick = onCapture),
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.size(62.dp).background(Color(0xFFDDD4CA), CircleShape)) }
    }
}

@Composable
private fun CharacterButton(label: String, color: Color, onClick: () -> Unit) {
    Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = color)) {
        Text(label, color = Color.White)
    }
}

@Composable
private fun StatusChip(label: String) {
    Surface(color = Color(0x9918130F), shape = RoundedCornerShape(99.dp)) {
        Text(label, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = Color.White)
    }
}

@Composable
private fun MessageCard(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.padding(24.dp).clickable(onClick = onDismiss),
        color = Color(0xDD3A2720),
        shape = RoundedCornerShape(16.dp),
    ) { Text(message, modifier = Modifier.padding(16.dp), color = Color.White) }
}

@Composable
private fun PermissionRequired(onRequest: () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("需要相机权限才能识别并替换人物")
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRequest) { Text("授予相机权限") }
        }
    }
}
