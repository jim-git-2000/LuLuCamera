package com.lulucamera.app.model

import org.junit.Assert.*
import org.junit.Test

class PersonMaskTest {
    @Test fun thresholdAndImageEdges() {
        val mask = PersonMask(2, 2, byteArrayOf(0, 127, 128.toByte(), 255.toByte()))
        assertFalse(mask.contains(PointN(.25f, .25f)))
        assertFalse(mask.contains(PointN(.75f, .25f)))
        assertTrue(mask.contains(PointN(.25f, .75f)))
        assertTrue(mask.contains(PointN(1f, 1f)))
        assertFalse(mask.contains(PointN(-.1f, .5f)))
        assertFalse(mask.contains(PointN(Float.NaN, .5f)))
    }
}
