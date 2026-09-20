package com.lulucamera.app.camera

/** 根据最近一次推理耗时限制送入模型的频率，始终优先保留相机预览。 */
class AnalysisThrottle(
    private val normalIntervalMs: Long = 66,
    private val slowIntervalMs: Long = 120,
    private val slowInferenceMs: Long = 90,
) {
    private var lastAcceptedMs: Long = Long.MIN_VALUE
    private var lastInferenceMs: Long = 0

    @Synchronized
    fun shouldAnalyze(nowMs: Long): Boolean {
        val interval = if (lastInferenceMs >= slowInferenceMs) slowIntervalMs else normalIntervalMs
        if (lastAcceptedMs != Long.MIN_VALUE && nowMs - lastAcceptedMs < interval) return false
        lastAcceptedMs = nowMs
        return true
    }

    @Synchronized
    fun recordInference(durationMs: Long) {
        lastInferenceMs = durationMs.coerceAtLeast(0)
    }
}
