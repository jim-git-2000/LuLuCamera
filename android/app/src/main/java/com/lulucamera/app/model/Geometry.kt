package com.lulucamera.app.model

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class PointN(val x: Float, val y: Float) {
    fun distanceTo(other: PointN): Float {
        val dx = x - other.x
        val dy = y - other.y
        return sqrt(dx * dx + dy * dy)
    }
}

data class RectN(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val area: Float get() = width * height
    val center: PointN get() = PointN((left + right) / 2f, (top + bottom) / 2f)

    fun contains(point: PointN): Boolean =
        point.x in left..right && point.y in top..bottom

    fun iou(other: RectN): Float {
        val intersectionWidth = (min(right, other.right) - max(left, other.left)).coerceAtLeast(0f)
        val intersectionHeight = (min(bottom, other.bottom) - max(top, other.top)).coerceAtLeast(0f)
        val intersection = intersectionWidth * intersectionHeight
        val union = area + other.area - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    companion object {
        fun enclosing(points: Collection<PointN>, padding: Float = 0.03f): RectN? {
            if (points.isEmpty()) return null
            val minX = points.minOf { it.x }
            val minY = points.minOf { it.y }
            val maxX = points.maxOf { it.x }
            val maxY = points.maxOf { it.y }
            return RectN(
                left = (minX - padding).coerceIn(0f, 1f),
                top = (minY - padding).coerceIn(0f, 1f),
                right = (maxX + padding).coerceIn(0f, 1f),
                bottom = (maxY + padding).coerceIn(0f, 1f),
            )
        }
    }
}
