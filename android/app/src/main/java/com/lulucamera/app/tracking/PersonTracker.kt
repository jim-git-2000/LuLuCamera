package com.lulucamera.app.tracking

import com.lulucamera.app.model.CharacterId
import com.lulucamera.app.model.Joint
import com.lulucamera.app.model.PersonObservation
import com.lulucamera.app.model.PersonTrack
import com.lulucamera.app.model.PointN
import com.lulucamera.app.model.TrackState

class PersonTracker(
    private val maxPeople: Int = 3,
    private val confirmationHits: Int = 2,
    private val maxMissedFrames: Int = 6,
) {
    private data class MutableTrack(
        val id: Long,
        var observation: PersonObservation,
        var state: TrackState,
        var character: CharacterId = CharacterId.HUMAN,
        var hits: Int = 1,
        var missed: Int = 0,
    )

    private data class Candidate(val trackIndex: Int, val observationIndex: Int, val score: Float)

    private val tracks = mutableListOf<MutableTrack>()
    private var nextId = 1L

    @Synchronized
    fun update(observations: List<PersonObservation>): List<PersonTrack> {
        val limited = observations
            .sortedByDescending(PersonObservation::confidence)
            .take(maxPeople)
        val active = tracks.filter { it.state != TrackState.REMOVED }
        val candidates = buildList {
            active.forEachIndexed { trackIndex, track ->
                limited.forEachIndexed { observationIndex, observation ->
                    matchScore(track.observation, observation)?.let { score ->
                        add(Candidate(trackIndex, observationIndex, score))
                    }
                }
            }
        }.sortedByDescending(Candidate::score)

        // 两个匹配分数接近时保留原身份但暂停替换，避免贪心匹配直接互换 A/B。
        val ambiguousTracks = candidates.groupBy { it.trackIndex }.filterValues {
            it.size > 1 && it[0].score - it[1].score < .06f
        }.keys
        val ambiguousObservations = candidates.groupBy { it.observationIndex }.filterValues {
            it.size > 1 && it[0].score - it[1].score < .06f
        }.keys + candidates.filter { it.trackIndex in ambiguousTracks }.map { it.observationIndex }
        val matchedTracks = mutableSetOf<Int>()
        val matchedObservations = mutableSetOf<Int>()
        for (candidate in candidates) {
            if (candidate.trackIndex in ambiguousTracks || candidate.observationIndex in ambiguousObservations) continue
            if (!matchedTracks.add(candidate.trackIndex)) continue
            if (!matchedObservations.add(candidate.observationIndex)) {
                matchedTracks.remove(candidate.trackIndex)
                continue
            }
            val track = active[candidate.trackIndex]
            track.observation = limited[candidate.observationIndex]
            track.hits += 1
            track.missed = 0
            track.state = if (track.hits >= confirmationHits) TrackState.TRACKED else TrackState.TENTATIVE
        }

        active.forEachIndexed { index, track ->
            if (index !in matchedTracks) {
                track.missed += 1
                track.state = if (track.missed > maxMissedFrames) TrackState.REMOVED else TrackState.LOST
            }
        }

        limited.forEachIndexed { index, observation ->
            if (index !in matchedObservations && index !in ambiguousObservations) {
                tracks += MutableTrack(
                    id = nextId++,
                    observation = observation,
                    state = if (confirmationHits <= 1) TrackState.TRACKED else TrackState.TENTATIVE,
                )
            }
        }
        tracks.removeAll { it.state == TrackState.REMOVED }
        return snapshot()
    }

    @Synchronized
    fun selectCharacter(trackId: Long, characterId: CharacterId): Boolean {
        val track = tracks.firstOrNull { it.id == trackId && it.state != TrackState.REMOVED } ?: return false
        track.character = characterId
        return true
    }

    @Synchronized
    fun hitTest(point: PointN): PersonTrack? = snapshot()
        .asSequence()
        .filter { it.state == TrackState.TRACKED && (it.observation.mask?.contains(point)
            ?: it.observation.bounds.contains(point)) }
        .minByOrNull { it.observation.bounds.area }

    @Synchronized
    fun reset() {
        tracks.clear()
    }

    @Synchronized
    fun snapshot(): List<PersonTrack> = tracks
        .filter { it.state != TrackState.REMOVED }
        .map {
            PersonTrack(
                trackId = it.id,
                state = it.state,
                observation = it.observation,
                selectedCharacter = it.character,
                hits = it.hits,
                missedFrames = it.missed,
                lastSeenMs = it.observation.timestampMs,
            )
        }

    private fun matchScore(previous: PersonObservation, current: PersonObservation): Float? {
        val iou = previous.bounds.iou(current.bounds)
        val centerDistance = previous.bounds.center.distanceTo(current.bounds.center)
        val poseDistance = poseDistance(previous, current)
        if (iou < 0.05f && centerDistance > 0.22f) return null
        if (poseDistance != null && poseDistance > 0.28f) return null
        val poseScore = poseDistance?.let { (1f - it / 0.28f).coerceIn(0f, 1f) } ?: 0.5f
        val motionScore = (1f - centerDistance / 0.22f).coerceIn(0f, 1f)
        return iou * 0.5f + motionScore * 0.25f + poseScore * 0.25f
    }

    private fun poseDistance(first: PersonObservation, second: PersonObservation): Float? {
        val anchors = listOf(Joint.LEFT_SHOULDER, Joint.RIGHT_SHOULDER, Joint.LEFT_HIP, Joint.RIGHT_HIP)
        val distances = anchors.mapNotNull { joint ->
            val a = first.pose.reliablePoint(joint)
            val b = second.pose.reliablePoint(joint)
            if (a == null || b == null) null else a.distanceTo(b)
        }
        return distances.takeIf { it.size >= 2 }?.average()?.toFloat()
    }
}
