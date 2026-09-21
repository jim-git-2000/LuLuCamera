package com.lulucamera.app.model

/** 复制到 JVM 的独立置信度掩码，生命周期不依赖 MediaPipe native buffer。 */
class PersonMask(val width: Int, val height: Int, private val confidence: ByteArray) {
    init { require(width > 0 && height > 0 && confidence.size == width * height) }
    fun valueAt(x: Int, y: Int): Int = confidence[y * width + x].toInt() and 255
    fun contains(point: PointN): Boolean {
        if (point.x !in 0f..1f || point.y !in 0f..1f) return false
        return valueAt((point.x * width).toInt().coerceAtMost(width - 1),
            (point.y * height).toInt().coerceAtMost(height - 1)) >= 128
    }
}
