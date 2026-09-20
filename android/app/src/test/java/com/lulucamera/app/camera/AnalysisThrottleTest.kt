package com.lulucamera.app.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisThrottleTest {
    @Test
    fun `normal inference accepts roughly fifteen frames per second`() {
        val throttle = AnalysisThrottle()
        assertTrue(throttle.shouldAnalyze(1_000))
        assertFalse(throttle.shouldAnalyze(1_050))
        assertTrue(throttle.shouldAnalyze(1_066))
    }

    @Test
    fun `slow inference reduces analysis frequency`() {
        val throttle = AnalysisThrottle()
        assertTrue(throttle.shouldAnalyze(1_000))
        throttle.recordInference(100)
        assertFalse(throttle.shouldAnalyze(1_100))
        assertTrue(throttle.shouldAnalyze(1_120))
    }
}
