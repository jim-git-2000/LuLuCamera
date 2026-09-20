package com.lulucamera.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import com.lulucamera.app.camera.FrameGeometry
import com.lulucamera.app.camera.FrameTransform
import com.lulucamera.app.model.CharacterId
import com.lulucamera.app.model.PointN
import com.lulucamera.app.model.TrackState
import kotlin.math.abs

@Composable
fun CharacterOverlay(
    state: CameraUiState,
    modifier: Modifier = Modifier,
    onTap: (PointN) -> Unit,
) {
    Canvas(
        modifier = modifier.pointerInput(state.imageWidth, state.imageHeight) {
            detectTapGestures { offset ->
                val transform = FrameTransform(
                    FrameGeometry(
                        imageWidth = state.imageWidth.coerceAtLeast(1),
                        imageHeight = state.imageHeight.coerceAtLeast(1),
                        viewWidth = size.width.toFloat(),
                        viewHeight = size.height.toFloat(),
                    ),
                )
                onTap(transform.toImage(PointN(offset.x, offset.y)))
            }
        },
    ) {
        val transform = FrameTransform(
            FrameGeometry(
                imageWidth = state.imageWidth.coerceAtLeast(1),
                imageHeight = state.imageHeight.coerceAtLeast(1),
                viewWidth = size.width,
                viewHeight = size.height,
            ),
        )
        state.tracks.forEach { tracked ->
            val track = tracked.track
            if (track.state == TrackState.TRACKED && track.selectedCharacter != CharacterId.HUMAN) {
                tracked.pose?.let { pose -> drawLulu(track.selectedCharacter, pose, transform) }
            }
            if (state.selectedTrackId == track.trackId && track.state != TrackState.REMOVED) {
                val bounds = transform.toView(track.observation.bounds)
                drawRoundRect(
                    color = Color(0xFFFFD18C),
                    topLeft = Offset(bounds.left, bounds.top),
                    size = Size(bounds.width, bounds.height),
                    cornerRadius = CornerRadius(24f, 24f),
                    style = Stroke(width = 4f),
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLulu(
    characterId: CharacterId,
    pose: com.lulucamera.app.character.CharacterPose,
    transform: FrameTransform,
) {
    val anchor = transform.toView(pose.anchor)
    val top = transform.toView(PointN(pose.anchor.x, (pose.anchor.y - pose.scale).coerceAtLeast(0f)))
    val height = abs(anchor.y - top.y).coerceAtLeast(80f)
    val width = height * 0.72f
    val bodyColor = if (characterId == CharacterId.LULU_A) Color(0xFF9A6545) else Color(0xFFD6B28B)
    val muzzleColor = if (characterId == CharacterId.LULU_A) Color(0xFFD5A277) else Color(0xFFF2D7B8)
    val outline = Color(0xFF3B291E)
    val bodyLeft = anchor.x - width / 2f
    val bodyTop = anchor.y - height * 0.66f
    val limbWidth = (width * 0.12f).coerceAtLeast(10f)

    rotate(pose.bodyTiltDegrees, Offset(anchor.x, anchor.y - height * 0.32f)) {
        drawRoundRect(
            color = bodyColor,
            topLeft = Offset(bodyLeft, bodyTop),
            size = Size(width, height * 0.72f),
            cornerRadius = CornerRadius(width * 0.32f, width * 0.32f),
        )
        drawRoundRect(
            color = outline,
            topLeft = Offset(bodyLeft, bodyTop),
            size = Size(width, height * 0.72f),
            cornerRadius = CornerRadius(width * 0.32f, width * 0.32f),
            style = Stroke((width * 0.035f).coerceAtLeast(3f)),
        )
        val headCenter = Offset(anchor.x + width * 0.27f, bodyTop + height * 0.18f)
        drawCircle(bodyColor, radius = width * 0.25f, center = headCenter)
        drawCircle(muzzleColor, radius = width * 0.12f, center = headCenter + Offset(width * 0.13f, width * 0.05f))
        drawCircle(outline, radius = width * 0.022f, center = headCenter + Offset(width * 0.09f, -width * 0.04f))
        drawCircle(outline, radius = width * 0.026f, center = headCenter + Offset(width * 0.23f, width * 0.04f))
        drawCircle(bodyColor, radius = width * 0.07f, center = headCenter + Offset(-width * 0.10f, -width * 0.20f))

        drawLimb(
            start = Offset(anchor.x - width * 0.22f, bodyTop + height * 0.36f),
            length = height * 0.36f,
            width = limbWidth,
            angle = pose.leftFrontLegDegrees,
            color = bodyColor,
            outline = outline,
        )
        drawLimb(
            start = Offset(anchor.x + width * 0.16f, bodyTop + height * 0.38f),
            length = height * 0.36f,
            width = limbWidth,
            angle = pose.rightFrontLegDegrees,
            color = bodyColor,
            outline = outline,
        )
        drawLimb(
            start = Offset(anchor.x - width * 0.20f, bodyTop + height * 0.62f),
            length = height * 0.29f,
            width = limbWidth,
            angle = pose.leftHindLegDegrees,
            color = bodyColor,
            outline = outline,
        )
        drawLimb(
            start = Offset(anchor.x + width * 0.19f, bodyTop + height * 0.62f),
            length = height * 0.29f,
            width = limbWidth,
            angle = pose.rightHindLegDegrees,
            color = bodyColor,
            outline = outline,
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLimb(
    start: Offset,
    length: Float,
    width: Float,
    angle: Float,
    color: Color,
    outline: Color,
) {
    rotate(angle, start) {
        val end = start + Offset(0f, length)
        drawLine(outline, start, end, strokeWidth = width + 5f, cap = StrokeCap.Round)
        drawLine(color, start, end, strokeWidth = width, cap = StrokeCap.Round)
    }
}
