package com.example.media

import kotlin.math.sqrt

/**
 * Pure speech-onset finder on decoded mono 16-bit PCM — no Android APIs, so the waveform
 * logic is unit-testable on the JVM.
 *
 * The hardest real-world case is a line that starts with *music playing first and the
 * actor only beginning to speak over it later*. An energy-only detector cannot find that
 * onset: speech added on top of a music bed raises the total RMS by only ~1.2–1.4x, far
 * below the "2x noise floor" style thresholds, so the detector gives up, the dub stays at
 * the subtitle start and plays while the music is still playing — ahead of the actor.
 *
 * So instead of one absolute threshold, this finder looks for a **step-up**: the moment
 * the signal rises above its own recent past and *stays* up. Two kinds of step count:
 *  - an RMS step (speech clearly louder than the bed before it), and
 *  - a high-frequency step: the first-difference energy jumps when a voice starts over
 *    tonal music, even when the total loudness barely changes.
 *
 * Guards keep the step-up honest:
 *  - the rise must start at the candidate frame itself (previous frame still quiet), and
 *    the elevated level must persist (~200ms), which rejects clicks and drum hits;
 *  - if the signal was already that loud shortly before, the step is a pause inside
 *    ongoing speech, not a first onset;
 *  - a flat signal (steady music, or speech filling the whole slice) has no onset inside
 *    the slice and returns `null`, so callers keep the subtitle timing.
 *
 * Once a loud step is found, the finder walks back over the quieter beginning of the
 * utterance (soft consonants, breathy starts) to the true start of the line.
 */
object SpeechOnsetFinder {

    /** Analysis window length, in milliseconds. */
    const val FRAME_MS = 25L

    /** RMS below this (16-bit scale) is treated as silence; nothing can be an onset. */
    private const val MIN_AUDIBLE_RMS = 300.0

    /** Frames of "recent past" a step-up is measured against (8 * 25ms = 200ms). */
    private const val PREV_FRAMES = 8

    /** Frames averaged to confirm the level right after a candidate step (75ms). */
    private const val NEXT_FRAMES = 3

    /** Frames over which the stepped-up level must persist (8 * 25ms = 200ms). */
    private const val SUSTAIN_FRAMES = 8

    /** How far (in frames) the low-threshold walk-back may travel from the loud frame. */
    private const val MAX_WALK_BACK_FRAMES = 40 // 40 * 25ms = 1.0s

    /**
     * Finds when speech starts in [samples], which begin at [sliceStartSec] on the media
     * timeline.
     *
     * @return absolute onset time in seconds (frame precision), or `null` when the slice
     * has no clear speech onset (silence, flat music/noise bed, speech already ongoing…).
     */
    fun findOnsetSec(samples: ShortArray, sampleRate: Int, sliceStartSec: Double): Double? {
        if (samples.isEmpty() || sampleRate <= 0) return null

        val frameSamples = ((FRAME_MS * sampleRate) / 1000).toInt().coerceAtLeast(100)
        val numFrames = samples.size / frameSamples
        if (numFrames < 8) return null

        // Per-frame RMS, plus the RMS of the first difference (a cheap high-frequency
        // energy proxy: steady tones barely change sample-to-sample, voices do).
        val frameRms = DoubleArray(numFrames)
        val frameHf = DoubleArray(numFrames)
        for (f in 0 until numFrames) {
            val offset = f * frameSamples
            var sumSq = 0.0
            var hfSumSq = 0.0
            var prev = samples[offset].toInt()
            for (i in 0 until frameSamples) {
                val s = samples[offset + i].toInt()
                sumSq += s.toDouble() * s
                val diff = s - prev
                hfSumSq += diff.toDouble() * diff
                prev = s
            }
            frameRms[f] = sqrt(sumSq / frameSamples)
            frameHf[f] = sqrt(hfSumSq / frameSamples)
        }

        val sorted = frameRms.clone().apply { sort() }
        val maxRms = sorted.last()
        if (maxRms < MIN_AUDIBLE_RMS) return null // (near) silence: no speech at all

        // Bed: a low percentile (silence/music under the speech). Peak: a high percentile,
        // so a single click cannot inflate it.
        val bed = sorted[(numFrames * 0.20).toInt().coerceIn(0, numFrames - 1)]
        val peak = maxOf(
            sorted[(numFrames * 0.95).toInt().coerceIn(0, numFrames - 1)],
            maxRms * 0.5
        )

        findStepUpOnset(frameRms, frameHf, bed, sliceStartSec, frameSamples, sampleRate)
            ?.let { return it }

        // Flat signal: steady music/noise, or speech that fills the whole slice — there is
        // no onset *inside* the slice. Returning null lets the caller keep subtitle timing.
        if (peak <= bed * 1.35) return null

        return findAbsoluteOnset(frameRms, bed, peak, sliceStartSec, frameSamples, sampleRate)
    }

    /**
     * The music-bed case: first frame where the signal steps up from its own recent past
     * (in RMS and/or high-frequency energy) and stays up.
     */
    private fun findStepUpOnset(
        frameRms: DoubleArray,
        frameHf: DoubleArray,
        bed: Double,
        sliceStartSec: Double,
        frameSamples: Int,
        sampleRate: Int
    ): Double? {
        val numFrames = frameRms.size
        for (f in 1 until numFrames - 1) {
            val prevFrom = maxOf(0, f - PREV_FRAMES)
            val prevRms = mean(frameRms, prevFrom, f)
            val prevHf = mean(frameHf, prevFrom, f)
            val nextRms = mean(frameRms, f, minOf(numFrames, f + NEXT_FRAMES))
            val nextHf = mean(frameHf, f, minOf(numFrames, f + NEXT_FRAMES))
            if (nextRms < MIN_AUDIBLE_RMS) continue

            val stepRms = nextRms / maxOf(prevRms, 1.0)
            val stepHf = nextHf / maxOf(prevHf, 1.0)
            val sustainEnd = minOf(numFrames, f + SUSTAIN_FRAMES)
            val rmsSustain = fractionAtLeast(frameRms, f, sustainEnd, prevRms * 1.18)
            val hfSustain = fractionAtLeast(frameHf, f, sustainEnd, prevHf * 1.8)

            // The rise must start at f itself: the previous frame is still at bed level.
            val rmsDriven = frameRms[f] >= maxOf(prevRms * 1.22, 200.0) &&
                frameRms[f - 1] <= frameRms[f] * 0.82 &&
                stepRms >= 1.38 && rmsSustain >= 0.75 &&
                (stepHf >= 1.4 || stepRms >= 2.2)
            val hfDriven = frameHf[f] >= maxOf(prevHf * 1.8, 60.0) &&
                frameHf[f - 1] <= frameHf[f] * 0.82 &&
                frameRms[f] >= maxOf(prevRms * 1.05, 150.0) &&
                stepRms >= 1.10 && stepHf >= 2.5 && hfSustain >= 0.75 &&
                nextRms >= bed * 1.05
            if (!rmsDriven && !hfDriven) continue

            // Already this loud shortly before the bed dip? Then this is speech resuming
            // after a pause, not the first onset.
            val guardFrom = maxOf(0, f - 24)
            val guardUntil = maxOf(guardFrom, f - 10)
            val guardLevel = maxOf(nextRms * 0.85, prevRms * 1.6)
            var wasLoudBefore = false
            for (i in guardFrom until guardUntil) {
                if (frameRms[i] >= guardLevel) {
                    wasLoudBefore = true
                    break
                }
            }
            if (wasLoudBefore) continue

            // Walk back over the soft beginning of the utterance.
            val lowThreshold = maxOf(bed * 1.15, prevRms * 0.95, 120.0)
            var onsetFrame = f
            var walked = 0
            while (onsetFrame > 0 && walked < MAX_WALK_BACK_FRAMES &&
                frameRms[onsetFrame - 1] >= lowThreshold
            ) {
                onsetFrame--
                walked++
            }
            return onsetAt(onsetFrame, sliceStartSec, frameSamples, sampleRate)
        }
        return null
    }

    /** Fallback: loud, sustained speech over a quiet bed (the classic VAD case). */
    private fun findAbsoluteOnset(
        frameRms: DoubleArray,
        bed: Double,
        peak: Double,
        sliceStartSec: Double,
        frameSamples: Int,
        sampleRate: Int
    ): Double? {
        val loudThreshold = maxOf(bed * 2.1, peak * 0.32, MIN_AUDIBLE_RMS)
        if (loudThreshold > peak || peak <= bed * 1.6) return null
        val onsetThreshold = maxOf(bed * 1.45, peak * 0.10, 180.0)

        var loudFrame = -1
        for (f in frameRms.indices) {
            if (frameRms[f] < loudThreshold) continue
            val next = frameRms.getOrNull(f + 1)
            if (next == null || next >= loudThreshold * 0.7) {
                loudFrame = f
                break
            }
        }
        if (loudFrame < 0) return null
        if (loudFrame == 0) return roundToCentisecond(sliceStartSec)

        var onsetFrame = loudFrame
        var walked = 0
        while (onsetFrame > 0 && walked < MAX_WALK_BACK_FRAMES &&
            frameRms[onsetFrame - 1] >= onsetThreshold
        ) {
            onsetFrame--
            walked++
        }
        return onsetAt(onsetFrame, sliceStartSec, frameSamples, sampleRate)
    }

    private fun onsetAt(frame: Int, sliceStartSec: Double, frameSamples: Int, sampleRate: Int): Double =
        roundToCentisecond(sliceStartSec + frame * frameSamples.toDouble() / sampleRate)

    private fun mean(values: DoubleArray, from: Int, until: Int): Double {
        if (until <= from) return 0.0
        var sum = 0.0
        for (i in from until until) sum += values[i]
        return sum / (until - from)
    }

    private fun fractionAtLeast(values: DoubleArray, from: Int, until: Int, level: Double): Double {
        if (until <= from) return 0.0
        var count = 0
        for (i in from until until) if (values[i] >= level) count++
        return count.toDouble() / (until - from)
    }

    private fun roundToCentisecond(sec: Double): Double = Math.round(sec * 100.0) / 100.0
}
