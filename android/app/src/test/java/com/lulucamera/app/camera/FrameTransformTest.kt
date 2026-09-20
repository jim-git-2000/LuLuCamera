package com.lulucamera.app.camera

import com.lulucamera.app.model.PointN
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameTransformTest {
    @Test
    fun centerMapsToCenterWithFillCenterCrop() {
        val transform = FrameTransform(FrameGeometry(1920, 1080, 1080f, 1920f))

        val mapped = transform.toView(PointN(0.5f, 0.5f))

        assertEquals(540f, mapped.x, 0.01f)
        assertEquals(960f, mapped.y, 0.01f)
    }

    @Test
    fun mappingRoundTripsAfterMirrorAndCrop() {
        val transform = FrameTransform(FrameGeometry(1080, 1920, 800f, 1200f, mirrored = true))
        val source = PointN(0.2f, 0.7f)

        val restored = transform.toImage(transform.toView(source))

        assertEquals(source.x, restored.x, 0.0001f)
        assertEquals(source.y, restored.y, 0.0001f)
    }
}
