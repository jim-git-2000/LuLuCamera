package com.lulucamera.app.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.lulucamera.app.character.CharacterPose
import com.lulucamera.app.model.CharacterId
import com.lulucamera.app.model.TrackState
import com.lulucamera.app.ui.TrackedCharacter
import kotlin.math.cos
import kotlin.math.sin

object SnapshotCompositor {
    fun render(source: Bitmap, tracks: List<TrackedCharacter>): Bitmap =
        source.copy(Bitmap.Config.ARGB_8888, true).also { output ->
            val canvas = Canvas(output)
            tracks.forEach { tracked ->
                if (tracked.track.state == TrackState.TRACKED &&
                    tracked.track.selectedCharacter != CharacterId.HUMAN
                ) {
                    tracked.pose?.let { drawCharacter(canvas, tracked.track.selectedCharacter, it) }
                }
            }
        }

    fun createEditMask(width: Int, height: Int, tracks: List<TrackedCharacter>): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        tracks.filter {
            it.track.state == TrackState.TRACKED && it.track.selectedCharacter != CharacterId.HUMAN
        }.forEach { tracked ->
            val bounds = tracked.track.observation.bounds
            val padX = bounds.width * 0.14f
            val padY = bounds.height * 0.10f
            canvas.drawRoundRect(
                RectF(
                    ((bounds.left - padX).coerceAtLeast(0f) * width),
                    ((bounds.top - padY).coerceAtLeast(0f) * height),
                    ((bounds.right + padX).coerceAtMost(1f) * width),
                    ((bounds.bottom + padY).coerceAtMost(1f) * height),
                ),
                width * 0.025f,
                width * 0.025f,
                paint,
            )
        }
        return bitmap
    }

    private fun drawCharacter(canvas: Canvas, character: CharacterId, pose: CharacterPose) {
        val height = (pose.scale * canvas.height).coerceAtLeast(80f)
        val width = height * 0.72f
        val x = pose.anchor.x * canvas.width
        val y = pose.anchor.y * canvas.height
        val bodyColor = if (character == CharacterId.LULU_A) Color.rgb(154, 101, 69) else Color.rgb(214, 178, 139)
        val muzzleColor = if (character == CharacterId.LULU_A) Color.rgb(213, 162, 119) else Color.rgb(242, 215, 184)
        val outline = Color.rgb(59, 41, 30)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bodyColor }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = outline
            style = Paint.Style.STROKE
            strokeWidth = (width * 0.035f).coerceAtLeast(3f)
        }
        canvas.save()
        canvas.rotate(pose.bodyTiltDegrees, x, y - height * 0.32f)
        val body = RectF(x - width / 2f, y - height * 0.66f, x + width / 2f, y + height * 0.06f)
        canvas.drawRoundRect(body, width * 0.32f, width * 0.32f, fill)
        canvas.drawRoundRect(body, width * 0.32f, width * 0.32f, stroke)
        val headX = x + width * 0.27f
        val headY = y - height * 0.48f
        canvas.drawCircle(headX, headY, width * 0.25f, fill)
        fill.color = muzzleColor
        canvas.drawCircle(headX + width * 0.13f, headY + width * 0.05f, width * 0.12f, fill)
        fill.color = outline
        canvas.drawCircle(headX + width * 0.09f, headY - width * 0.04f, width * 0.022f, fill)
        canvas.drawCircle(headX + width * 0.23f, headY + width * 0.04f, width * 0.026f, fill)
        drawLimb(canvas, x - width * 0.22f, y - height * 0.30f, height * 0.36f, pose.leftFrontLegDegrees, bodyColor, outline, width)
        drawLimb(canvas, x + width * 0.16f, y - height * 0.28f, height * 0.36f, pose.rightFrontLegDegrees, bodyColor, outline, width)
        drawLimb(canvas, x - width * 0.20f, y - height * 0.04f, height * 0.29f, pose.leftHindLegDegrees, bodyColor, outline, width)
        drawLimb(canvas, x + width * 0.19f, y - height * 0.04f, height * 0.29f, pose.rightHindLegDegrees, bodyColor, outline, width)
        canvas.restore()
    }

    private fun drawLimb(
        canvas: Canvas,
        x: Float,
        y: Float,
        length: Float,
        angle: Float,
        color: Int,
        outline: Int,
        bodyWidth: Float,
    ) {
        val radians = Math.toRadians(angle.toDouble())
        val endX = x - sin(radians).toFloat() * length
        val endY = y + cos(radians).toFloat() * length
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        paint.color = outline
        paint.strokeWidth = (bodyWidth * 0.12f).coerceAtLeast(10f) + 5f
        canvas.drawLine(x, y, endX, endY, paint)
        paint.color = color
        paint.strokeWidth -= 5f
        canvas.drawLine(x, y, endX, endY, paint)
    }
}
