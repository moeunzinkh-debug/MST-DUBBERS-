package com.example.media

import com.example.data.db.SegmentEntity
import kotlin.math.min

/** One AI-voice clip placed on the video timeline. */
data class DubClip(
    val segmentId: Long,
    val audioPath: String,
    /** Where the clip starts on the video timeline, in seconds. */
    val startSec: Double,
    /** Playback speed (time-stretched, pitch preserved). */
    val rate: Float,
    /** The clip is cut after this many seconds, when the next line starts or the video ends. */
    val maxDurationSec: Double
)

/**
 * Decides when and how fast each dubbed line plays.
 *
 * The live preview ([com.example.ui.DubberViewModel]) and the video/audio export use the same
 * rules, so the exported file sounds like what the user heard in the editor:
 * - a clip starts at its line's start time,
 * - it plays at [playbackRate] (manual speed, or Auto-Fit),
 * - it keeps playing through pauses between lines and is cut when the next line starts
 *   (or at the end of the video).
 */
object DubTimeline {

    /** Auto-Fit cap used when the configured fit mode is unknown. */
    const val DEFAULT_MAX_RATE = 1.35f

    /** Clips shorter than this after cutting are dropped. */
    const val MIN_CLIP_SEC = 0.05

    /**
     * The speed a segment's voice clip plays at: the manual speed if the user set one, otherwise
     * Auto-Fit, which speeds up a clip that is longer than its line (up to [fitMaxRate]) and never
     * slows clips down.
     *
     * Clips written by the current voice generation are already fitted to their slot
     * ([SlotFitter]), so their [SegmentEntity.audioDuration] equals the slot and Auto-Fit
     * returns 1.0; this stays the safety net for clips made before slot-fitting, or whenever
     * fitting failed and the raw TTS clip was kept.
     */
    fun playbackRate(segment: SegmentEntity, fitMaxRate: Float): Float {
        segment.customSpeed?.let { return it.coerceIn(0.25f, 4f) }
        val slot = (segment.end - segment.start).coerceAtLeast(0.3)
        val clipDuration = segment.audioDuration?.takeIf { it > 0.0 } ?: slot
        val maxRate = fitMaxRate.coerceAtLeast(1f)
        return (clipDuration / slot).toFloat().coerceIn(1f, maxRate)
    }

    /**
     * Places every voiced segment on the timeline.
     *
     * @param timelineEndSec the video duration; clips are cut there. Pass 0 if unknown.
     * @return non-overlapping clips sorted by start time.
     */
    fun plan(
        segments: List<SegmentEntity>,
        fitMaxRate: Float,
        timelineEndSec: Double
    ): List<DubClip> {
        val sorted = segments.sortedWith(compareBy<SegmentEntity>({ it.start }, { it.idx }))
        val timelineEnd = if (timelineEndSec > 0.0) timelineEndSec else Double.MAX_VALUE
        val clips = ArrayList<DubClip>()
        var lastClipStart = Double.NEGATIVE_INFINITY
        for ((index, segment) in sorted.withIndex()) {
            val path = segment.audioPath
            if (path.isNullOrBlank()) continue
            val start = segment.start.coerceAtLeast(0.0)
            // Two lines starting at the same moment can't both be heard: keep the first one.
            if (start <= lastClipStart) continue

            var limit = timelineEnd
            for (j in index + 1 until sorted.size) {
                if (sorted[j].start > start) {
                    limit = min(limit, sorted[j].start)
                    break
                }
            }
            val maxDuration = limit - start
            if (maxDuration < MIN_CLIP_SEC) continue

            clips += DubClip(
                segmentId = segment.id,
                audioPath = path,
                startSec = start,
                rate = playbackRate(segment, fitMaxRate),
                maxDurationSec = maxDuration
            )
            lastClipStart = start
        }
        return clips
    }
}
