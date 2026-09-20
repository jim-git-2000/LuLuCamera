package com.lulucamera.app.camera

import com.lulucamera.app.model.PointN
import com.lulucamera.app.model.RectN
import kotlin.math.max

data class FrameGeometry(
    val imageWidth: Int,
    val imageHeight: Int,
    val viewWidth: Float,
    val viewHeight: Float,
    val mirrored: Boolean = false,
)

/** 将已经按显示方向旋转的推理坐标映射到 PreviewView 的 FILL_CENTER 画面。 */
class FrameTransform(private val geometry: FrameGeometry) {
    private val scale = max(
        geometry.viewWidth / geometry.imageWidth,
        geometry.viewHeight / geometry.imageHeight,
    )
    private val offsetX = (geometry.viewWidth - geometry.imageWidth * scale) / 2f
    private val offsetY = (geometry.viewHeight - geometry.imageHeight * scale) / 2f

    fun toView(point: PointN): PointN {
        val x = if (geometry.mirrored) 1f - point.x else point.x
        return PointN(
            x = offsetX + x * geometry.imageWidth * scale,
            y = offsetY + point.y * geometry.imageHeight * scale,
        )
    }

    fun toView(rect: RectN): RectN {
        val first = toView(PointN(rect.left, rect.top))
        val second = toView(PointN(rect.right, rect.bottom))
        return RectN(
            left = minOf(first.x, second.x),
            top = minOf(first.y, second.y),
            right = maxOf(first.x, second.x),
            bottom = maxOf(first.y, second.y),
        )
    }

    fun toImage(pointInView: PointN): PointN {
        val rawX = ((pointInView.x - offsetX) / (geometry.imageWidth * scale)).coerceIn(0f, 1f)
        val x = if (geometry.mirrored) 1f - rawX else rawX
        return PointN(
            x = x,
            y = ((pointInView.y - offsetY) / (geometry.imageHeight * scale)).coerceIn(0f, 1f),
        )
    }
}
