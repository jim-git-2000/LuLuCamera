package com.lulucamera.app.ui

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import com.lulucamera.app.camera.FrameGeometry
import com.lulucamera.app.camera.FrameTransform
import com.lulucamera.app.camera.SnapshotCompositor
import com.lulucamera.app.model.CharacterId
import com.lulucamera.app.model.PointN
import com.lulucamera.app.model.TrackState

/** 实时底图和角色均来自同一分析帧；保存复用同一个 Canvas 角色渲染器。 */
@Composable
fun CharacterOverlay(state: CameraUiState, frame: Bitmap?, modifier: Modifier = Modifier, onTap: (PointN) -> Unit) {
    Canvas(modifier.pointerInput(state.imageWidth, state.imageHeight) {
        detectTapGestures { offset ->
            val transform = FrameTransform(FrameGeometry(state.imageWidth, state.imageHeight,
                size.width.toFloat(), size.height.toFloat()))
            onTap(transform.toImage(PointN(offset.x, offset.y)))
        }
    }) {
        if (frame == null) return@Canvas
        val transform = FrameTransform(FrameGeometry(frame.width, frame.height, size.width, size.height))
        val origin = transform.toView(PointN(0f, 0f))
        val end = transform.toView(PointN(1f, 1f))
        val canvas = drawContext.canvas.nativeCanvas
        canvas.drawBitmap(frame, null, RectF(origin.x, origin.y, end.x, end.y), null)
        canvas.save()
        canvas.translate(origin.x, origin.y)
        canvas.scale((end.x - origin.x) / frame.width, (end.y - origin.y) / frame.height)
        state.tracks.forEach { tracked ->
            if (tracked.track.state == TrackState.TRACKED && tracked.track.selectedCharacter != CharacterId.HUMAN) {
                tracked.pose?.let { SnapshotCompositor.drawCharacter(canvas, tracked.track.selectedCharacter, it, frame.width, frame.height) }
            }
        }
        canvas.restore()
        state.tracks.firstOrNull { it.track.trackId == state.selectedTrackId }?.let {
            val bounds = transform.toView(it.track.observation.bounds)
            drawRoundRect(Color(0xFFFFD18C), Offset(bounds.left, bounds.top), Size(bounds.width, bounds.height),
                cornerRadius = CornerRadius(24f, 24f), style = Stroke(4f))
        }
    }
}
