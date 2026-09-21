package com.lulucamera.app.model

enum class Joint(val mediaPipeIndex: Int) {
    NOSE(0),
    LEFT_EAR(7),
    RIGHT_EAR(8),
    LEFT_SHOULDER(11),
    RIGHT_SHOULDER(12),
    LEFT_ELBOW(13),
    RIGHT_ELBOW(14),
    LEFT_WRIST(15),
    RIGHT_WRIST(16),
    LEFT_HIP(23),
    RIGHT_HIP(24),
    LEFT_KNEE(25),
    RIGHT_KNEE(26),
    LEFT_ANKLE(27),
    RIGHT_ANKLE(28),
}

data class Keypoint(
    val point: PointN,
    val visibility: Float,
    val presence: Float,
) {
    val isReliable: Boolean get() = visibility >= 0.45f && presence >= 0.45f && point.x.isFinite() && point.y.isFinite()
}

data class PersonPose(val joints: Map<Joint, Keypoint>) {
    operator fun get(joint: Joint): Keypoint? = joints[joint]

    fun reliablePoint(joint: Joint): PointN? = joints[joint]
        ?.takeIf(Keypoint::isReliable)
        ?.point

    val reliablePoints: List<PointN>
        get() = joints.values.filter(Keypoint::isReliable).map(Keypoint::point)
}

data class PersonObservation(
    val frameId: Long,
    val timestampMs: Long,
    val sourceIndex: Int,
    val bounds: RectN,
    val pose: PersonPose,
    val hasSegmentationMask: Boolean,
    val confidence: Float,
    val mask: PersonMask? = null,
)

enum class CharacterId {
    HUMAN,
    LULU_A,
    LULU_B,
}

enum class TrackState {
    TENTATIVE,
    TRACKED,
    LOST,
    REMOVED,
}

data class PersonTrack(
    val trackId: Long,
    val state: TrackState,
    val observation: PersonObservation,
    val selectedCharacter: CharacterId,
    val hits: Int,
    val missedFrames: Int,
    val lastSeenMs: Long,
)
