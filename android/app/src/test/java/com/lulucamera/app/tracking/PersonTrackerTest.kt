package com.lulucamera.app.tracking

import com.lulucamera.app.model.CharacterId
import com.lulucamera.app.model.Joint
import com.lulucamera.app.model.Keypoint
import com.lulucamera.app.model.PersonObservation
import com.lulucamera.app.model.PersonPose
import com.lulucamera.app.model.PointN
import com.lulucamera.app.model.RectN
import com.lulucamera.app.model.TrackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonTrackerTest {
    @Test
    fun characterSelectionSurvivesMotion() {
        val tracker = PersonTracker(confirmationHits = 1)
        val initial = tracker.update(listOf(observation(1, 0.20f))).single()
        tracker.selectCharacter(initial.trackId, CharacterId.LULU_A)

        val moved = tracker.update(listOf(observation(2, 0.24f))).single()

        assertEquals(initial.trackId, moved.trackId)
        assertEquals(CharacterId.LULU_A, moved.selectedCharacter)
        assertEquals(TrackState.TRACKED, moved.state)
    }

    @Test
    fun hitTestChoosesSmallestOverlappingPerson() {
        val tracker = PersonTracker(confirmationHits = 1)
        tracker.update(
            listOf(
                observation(1, 0.20f, width = 0.50f),
                observation(1, 0.35f, width = 0.20f),
            ),
        )

        val hit = tracker.hitTest(PointN(0.4f, 0.5f))

        assertEquals(0.20f, hit?.observation?.bounds?.width ?: 0f, 0.0001f)
    }

    @Test
    fun lostTrackDoesNotReceiveClicksAndExpires() {
        val tracker = PersonTracker(confirmationHits = 1, maxMissedFrames = 1)
        tracker.update(listOf(observation(1, 0.2f)))

        val lost = tracker.update(emptyList()).single()
        assertEquals(TrackState.LOST, lost.state)
        assertNull(tracker.hitTest(PointN(0.3f, 0.5f)))

        assertTrue(tracker.update(emptyList()).isEmpty())
    }

    private fun observation(frame: Long, left: Float, width: Float = 0.25f): PersonObservation {
        val right = left + width
        val center = (left + right) / 2f
        val points = mapOf(
            Joint.LEFT_SHOULDER to keypoint(center - 0.05f, 0.3f),
            Joint.RIGHT_SHOULDER to keypoint(center + 0.05f, 0.3f),
            Joint.LEFT_HIP to keypoint(center - 0.04f, 0.55f),
            Joint.RIGHT_HIP to keypoint(center + 0.04f, 0.55f),
        )
        return PersonObservation(
            frameId = frame,
            timestampMs = frame * 33,
            sourceIndex = 0,
            bounds = RectN(left, 0.2f, right, 0.8f),
            pose = PersonPose(points),
            hasSegmentationMask = true,
            confidence = 0.9f,
        )
    }

    private fun keypoint(x: Float, y: Float) = Keypoint(PointN(x, y), 1f, 1f)
}
