package com.lulucamera.app.character

import com.lulucamera.app.model.Joint
import com.lulucamera.app.model.Keypoint
import com.lulucamera.app.model.PersonPose
import com.lulucamera.app.model.PointN
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseMapperTest {
    @Test
    fun mapsRaisedArmAndClampsCharacterJoint() {
        val mapper = PoseMapper(smoothing = 1f)
        val pose = basePose(
            Joint.LEFT_ELBOW to point(0.25f, 0.05f),
            Joint.RIGHT_ELBOW to point(0.65f, 0.55f),
        )

        val mapped = requireNotNull(mapper.map(1, pose))

        assertTrue(mapped.leftFrontLegDegrees in -55f..55f)
        assertTrue(mapped.rightFrontLegDegrees in -55f..55f)
        assertTrue(mapped.scale > 0f)
    }

    @Test
    fun rejectsPoseWithoutReliableTorso() {
        val mapper = PoseMapper()
        val pose = PersonPose(mapOf(Joint.NOSE to point(0.5f, 0.2f)))

        assertNull(mapper.map(1, pose))
    }

    @Test
    fun fallsBackToTorsoScaleForHalfBodyPose() {
        val mapper = PoseMapper(smoothing = 1f)

        val mapped = mapper.map(1, basePose())

        assertNotNull(mapped)
        assertTrue(requireNotNull(mapped).scale >= 0.18f)
    }

    @Test
    fun swappingShoulderOrderDoesNotTiltUprightCharacter() {
        val mapper = PoseMapper(smoothing = 1f)
        val reversed = basePose(Joint.LEFT_SHOULDER to point(.6f, .3f), Joint.RIGHT_SHOULDER to point(.4f, .3f))
        org.junit.Assert.assertEquals(0f, requireNotNull(mapper.map(1, reversed)).bodyTiltDegrees, .001f)
    }

    private fun basePose(vararg overrides: Pair<Joint, Keypoint>): PersonPose {
        val values = mutableMapOf(
            Joint.NOSE to point(0.5f, 0.12f),
            Joint.LEFT_SHOULDER to point(0.4f, 0.30f),
            Joint.RIGHT_SHOULDER to point(0.6f, 0.30f),
            Joint.LEFT_ELBOW to point(0.35f, 0.48f),
            Joint.RIGHT_ELBOW to point(0.65f, 0.48f),
            Joint.LEFT_HIP to point(0.43f, 0.58f),
            Joint.RIGHT_HIP to point(0.57f, 0.58f),
            Joint.LEFT_KNEE to point(0.44f, 0.78f),
            Joint.RIGHT_KNEE to point(0.56f, 0.78f),
        )
        values.putAll(overrides)
        return PersonPose(values)
    }

    private fun point(x: Float, y: Float) = Keypoint(PointN(x, y), 1f, 1f)
}
