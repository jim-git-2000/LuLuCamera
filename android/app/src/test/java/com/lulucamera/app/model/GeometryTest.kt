package com.lulucamera.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryTest {
    @Test
    fun intersectionOverUnionUsesActualIntersection() {
        val first = RectN(0f, 0f, 0.5f, 0.5f)
        val second = RectN(0.25f, 0.25f, 0.75f, 0.75f)

        assertEquals(1f / 7f, first.iou(second), 0.0001f)
    }

    @Test
    fun enclosingBoundsAreClampedToImage() {
        val bounds = requireNotNull(RectN.enclosing(listOf(PointN(0.01f, 0.02f), PointN(0.98f, 0.99f))))

        assertTrue(bounds.left >= 0f)
        assertTrue(bounds.top >= 0f)
        assertTrue(bounds.right <= 1f)
        assertTrue(bounds.bottom <= 1f)
    }
}
