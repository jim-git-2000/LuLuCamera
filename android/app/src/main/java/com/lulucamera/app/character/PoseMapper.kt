package com.lulucamera.app.character

import com.lulucamera.app.model.Joint
import com.lulucamera.app.model.PersonPose
import com.lulucamera.app.model.PointN
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2

data class CharacterPose(
    val anchor: PointN,
    val scale: Float,
    val bodyTiltDegrees: Float,
    val headYaw: Float,
    val leftFrontLegDegrees: Float,
    val rightFrontLegDegrees: Float,
    val leftHindLegDegrees: Float,
    val rightHindLegDegrees: Float,
    val reliable: Boolean,
)

class PoseMapper(private val smoothing: Float = 0.35f) {
    private val previous = mutableMapOf<Long, CharacterPose>()

    @Synchronized
    fun map(trackId: Long, pose: PersonPose): CharacterPose? {
        val leftShoulder = pose.reliablePoint(Joint.LEFT_SHOULDER) ?: return null
        val rightShoulder = pose.reliablePoint(Joint.RIGHT_SHOULDER) ?: return null
        val leftHip = pose.reliablePoint(Joint.LEFT_HIP) ?: return null
        val rightHip = pose.reliablePoint(Joint.RIGHT_HIP) ?: return null
        val shoulderCenter = midpoint(leftShoulder, rightShoulder)
        val hipCenter = midpoint(leftHip, rightHip)
        val torso = shoulderCenter.distanceTo(hipCenter).coerceAtLeast(0.05f)
        val ankleCenter = pairMidpoint(pose, Joint.LEFT_ANKLE, Joint.RIGHT_ANKLE)
        val head = pose.reliablePoint(Joint.NOSE)
        val height = if (ankleCenter != null && head != null) {
            head.distanceTo(ankleCenter)
        } else {
            torso * 2.7f
        }
        val shoulderAngle = angleDegrees(leftShoulder, rightShoulder).coerceIn(-25f, 25f)
        val shoulderWidth = leftShoulder.distanceTo(rightShoulder).coerceAtLeast(0.04f)
        val hipWidth = leftHip.distanceTo(rightHip).coerceAtLeast(0.04f)
        val raw = CharacterPose(
            anchor = hipCenter,
            scale = (height * 1.08f).coerceIn(0.18f, 1.15f),
            bodyTiltDegrees = shoulderAngle,
            headYaw = ((shoulderWidth - hipWidth) / shoulderWidth).coerceIn(-0.35f, 0.35f),
            leftFrontLegDegrees = limbAngle(pose, Joint.LEFT_SHOULDER, Joint.LEFT_ELBOW, 55f),
            rightFrontLegDegrees = limbAngle(pose, Joint.RIGHT_SHOULDER, Joint.RIGHT_ELBOW, 55f),
            leftHindLegDegrees = limbAngle(pose, Joint.LEFT_HIP, Joint.LEFT_KNEE, 35f),
            rightHindLegDegrees = limbAngle(pose, Joint.RIGHT_HIP, Joint.RIGHT_KNEE, 35f),
            reliable = true,
        )
        val smoothed = previous[trackId]?.let { old -> interpolate(old, raw, smoothing) } ?: raw
        previous[trackId] = smoothed
        return smoothed
    }

    @Synchronized
    fun remove(trackId: Long) {
        previous.remove(trackId)
    }

    private fun limbAngle(pose: PersonPose, root: Joint, end: Joint, limit: Float): Float {
        val a = pose.reliablePoint(root) ?: return 0f
        val b = pose.reliablePoint(end) ?: return 0f
        val fromDown = angleDegrees(a, b) - 90f
        return normalizeDegrees(fromDown).coerceIn(-limit, limit)
    }

    private fun interpolate(old: CharacterPose, new: CharacterPose, alpha: Float): CharacterPose =
        CharacterPose(
            anchor = PointN(lerp(old.anchor.x, new.anchor.x, alpha), lerp(old.anchor.y, new.anchor.y, alpha)),
            scale = lerp(old.scale, new.scale, alpha),
            bodyTiltDegrees = lerp(old.bodyTiltDegrees, new.bodyTiltDegrees, alpha),
            headYaw = lerp(old.headYaw, new.headYaw, alpha),
            leftFrontLegDegrees = lerp(old.leftFrontLegDegrees, new.leftFrontLegDegrees, alpha),
            rightFrontLegDegrees = lerp(old.rightFrontLegDegrees, new.rightFrontLegDegrees, alpha),
            leftHindLegDegrees = lerp(old.leftHindLegDegrees, new.leftHindLegDegrees, alpha),
            rightHindLegDegrees = lerp(old.rightHindLegDegrees, new.rightHindLegDegrees, alpha),
            reliable = new.reliable,
        )

    private fun pairMidpoint(pose: PersonPose, left: Joint, right: Joint): PointN? {
        val a = pose.reliablePoint(left) ?: return null
        val b = pose.reliablePoint(right) ?: return null
        return midpoint(a, b)
    }

    private fun midpoint(a: PointN, b: PointN) = PointN((a.x + b.x) / 2f, (a.y + b.y) / 2f)
    private fun lerp(a: Float, b: Float, alpha: Float) = a + (b - a) * alpha
    private fun angleDegrees(a: PointN, b: PointN) = atan2(b.y - a.y, b.x - a.x) * 180f / PI.toFloat()
    private fun normalizeDegrees(value: Float): Float {
        var result = value
        while (result > 180f) result -= 360f
        while (result < -180f) result += 360f
        return if (abs(result) < 0.5f) 0f else result
    }
}
