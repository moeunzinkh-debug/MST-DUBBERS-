package com.example.media

import kotlin.math.sqrt

/**
 * Pure speech-onset finder on decoded mono 16-bit PCM — no Android APIs, so the waveform
 * logic is unit-testable on the JVM.
 *
 * Why the old detector kept placing the dub *before* the actor starts speaking:
 *  - it used a single high threshold (`max(noise * 2.4, peak * 0.22)`), so background music
 *    that was already loud at the start of the subtitle slot passed the "3 frames above
 *    threshold" test on frame 0 and was reported as speech at the slot start;
 *  - a loud sound later in the slot (door slam, music swell) pushed that threshold so high
 *    that the real, quieter speech onset was never crossed at all;
 *  - callers then fell back to guessing instead of the waveform.
 *
 * This finder uses two thresholds instead:
 *  1. a *loud* threshold to locate unmistakable speech (sustained, so clicks/taps are
 *     rejected), then
 *  2. a *low* threshold walked backwards from there, so the soft first consonant of the
 *     line — not the first loud vowel — is reported as the onset.
 *
 * Steady background music is handled because a flat signal (peak close to the noise floor)
 * has no clear onset and returns `null` instead of frame 0.
 */
object SpeechOnsetFinder {

    /** Analysis window length, in milliseconds. */
    const val FRAME_MS = 25L

    /** RMS below this (16-bit scale) is treated as silence; nothing can be an onset. */
    private const val MIN_AUDIBLE_RMS = 300.0

    /** How far (in frames) the low-threshold walk-back may travel from the loud frame. */
    private const val MAX_WALK_BACK_FRAMES = 40 // 40 * 25ms = 1.0s

    /**
     * Finds when speech starts in [samples], which begin at [sliceStartSec] on the media
     * timeline.
     *
     * @return absolute onset time in seconds (frame precision, not rounded to 0.1s), or
     * `null` when the slice has no clear speech onset (silence, flat music/noise bed, …).
     */
    fun findOnsetSec(samples: ShortArray, sampleRate: Int, sliceStartSec: Double): Double? {
        if (samples.isEmpty() || sampleRate <= 0) return null

        val frameSamples = ((FRAME_MS * sampleRate) / 1000).toInt().coerceAtLeast(100)
        val numFrames = samples.size / frameSamples
        if (numFrames < 4) return null

        // RMS energy of each frame.
        val frameRms = DoubleArray(numFrames)
        for (f in 0 until numFrames) {
            val offset = f * frameSamples
            var sumSq = 0.0
            for (i in 0 until frameSamples) {
                val s = samples[offset + i].toDouble()
                sumSq += s * s
            }
            frameRms[f] = sqrt(sumSq / frameSamples)
        }

        val sorted = frameRms.clone().apply { sort() }
        val maxRms = sorted.last()
        if (maxRms < MIN_AUDIBLE_RMS) return null // (near) silence: no speech at all

        // Noise floor: a low percentile, so it reflects the bed (silence/music) under the
        // speech rather than the speech itself. Peak: a high percentile, so a single click
        // cannot inflate it.
        val noiseFloor = sorted[(numFrames * 0.20).toInt().coerceIn(0, numFrames - 1)]
        val peak = sorted[(numFrames * 0.95).toInt().coerceIn(0, numFrames - 1)]
            .coerceAtLeast(maxRms * 0.5)

        // Flat signal (steady music/noise, no dialogue standing out): there is no onset to
        // find. Returning null is correct — guessing frame 0 put the dub ahead of the actor.
        if (peak <= noiseFloor * 1.6) return null

        val loudThreshold = maxOf(noiseFloor * 2.1, peak * 0.32, MIN_AUDIBLE_RMS)
        if (loudThreshold > peak) return null
        val onsetThreshold = maxOf(noiseFloor * 1.45, peak * 0.10, 180.0)

        // First *sustained* loud frame: the next frame must stay fairly loud too, which
        // rejects clicks, taps and other one-frame transients.
        var loudFrame = -1
        for (f in 0 until numFrames) {
            if (frameRms[f] < loudThreshold) continue
            val next = frameRms.getOrNull(f + 1)
            if (next == null || next >= loudThreshold * 0.7) {
                loudFrame = f
                break
            }
        }
        if (loudFrame < 0) return null

        // Speech was already ongoing when the slice began.
        if (loudFrame == 0) return roundToCentisecond(sliceStartSec)

        // Walk back over the quieter beginning of the utterance (soft consonants, breathy
        // starts) until the signal drops to the background bed.
        var onsetFrame = loudFrame
        var walked = 0
        while (onsetFrame > 0 && walked < MAX_WALK_BACK_FRAMES &&
            frameRms[onsetFrame - 1] >= onsetThreshold
        ) {
            onsetFrame--
            walked++
        }

        val onsetSec = sliceStartSec + onsetFrame * frameSamples.toDouble() / sampleRate
        return roundToCentisecond(onsetSec)
    }

    private fun roundToCentisecond(sec: Double): Double = Math.round(sec * 100.0) / 100.0
}
