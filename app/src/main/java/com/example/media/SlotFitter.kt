package com.example.media

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Fits a generated voice clip into the time slot of its subtitle line.
 *
 * Voice generation used to save whatever length the TTS engine returned — a
 * length driven by the text alone, not by the line's timing — so the dub
 * followed the subtitle *words* but never the subtitle *clock*: a short line
 * left a long unplanned gap, a long line overran its slot and was cut mid-word
 * where the next line starts, and neither the silence nor the fitted reading
 * speed lived in the clip itself.
 *
 * SlotFitter does the fitting when the voice is created, so the saved clip IS
 * the line's timing:
 *  - the near-silence the TTS encoder puts around the speech is trimmed,
 *  - the speech is sped up, or gently slowed down, to fill the slot (within
 *    [MIN_FIT_RATE]..[fitMaxRate]),
 *  - the rest of the slot is filled with silence, so the voice starts exactly
 *    at the slot start (the detected speech onset) and ends with the line.
 *
 * The preview player and the export renderer then place the clip at the line's
 * start time and hear it exactly within the line's timing — the same way, in
 * both, because the timing is baked into the clip.
 */
object SlotFitter {

    /** Slowest the fit may slow a short voice down to fill its slot. */
    const val MIN_FIT_RATE = 0.85f

    /** RMS below this (16-bit scale) counts as silence when trimming. */
    private const val SILENCE_RMS = 300.0

    /** Analysis window for the silence trim, in milliseconds. */
    private const val FRAME_MS = 25L

    /** Breath kept around the trimmed speech so it doesn't start on a click. */
    private const val TRIM_PAD_SEC = 0.025

    /**
     * Fits [clip] into a slot of [slotSec] seconds.
     *
     * @param fitMaxRate fastest the fit may read (the user's Auto-Fit mode).
     * @return mono 16-bit PCM at [DubTrackRenderer.OUTPUT_SAMPLE_RATE], exactly
     *   [slotSec] seconds long: the voice first, silence after (all silence when
     *   [clip] has no audible speech).
     */
    fun fitToSlot(
        clip: PcmAudio,
        slotSec: Double,
        fitMaxRate: Float,
        outputSampleRate: Int = DubTrackRenderer.OUTPUT_SAMPLE_RATE
    ): PcmAudio {
        val slotSamples = (slotSec * outputSampleRate).roundToInt().coerceAtLeast(0)
        if (slotSamples <= 0) return PcmAudio(ShortArray(0), outputSampleRate)

        val speech = trimSilence(clip.samples, clip.sampleRate)
        if (speech.isEmpty()) return PcmAudio(ShortArray(slotSamples), outputSampleRate)

        // Speed the speech up — or gently slow it down — so it fills the slot.
        val rawRate = speech.size.toDouble() / clip.sampleRate / slotSec
        val rate = rawRate.toFloat().coerceIn(MIN_FIT_RATE, fitMaxRate.coerceAtLeast(1f))
        val stretched = DubTrackRenderer.timeStretch(
            PcmAudio(speech, clip.sampleRate), rate, outputSampleRate
        )

        // The voice starts at the slot start (the detected speech onset); whatever
        // is left of the slot stays silence. A voice that still overflows (the fit
        // mode's cap was hit) is cut with a short fade, the way the export renderer
        // cuts a clip at the next line.
        val out = ShortArray(slotSamples)
        if (stretched.isNotEmpty()) {
            val count = min(stretched.size, slotSamples)
            if (count < stretched.size) {
                DubTrackRenderer.fadeOut(
                    stretched, count, (DubTrackRenderer.CUT_FADE_SEC * outputSampleRate).toInt()
                )
            }
            System.arraycopy(stretched, 0, out, 0, count)
        }
        return PcmAudio(out, outputSampleRate)
    }

    /** Drops the near-silence at both ends of [samples], keeping a small breath. */
    private fun trimSilence(samples: ShortArray, sampleRate: Int): ShortArray {
        if (samples.isEmpty()) return samples
        val frameSamples = ((FRAME_MS * sampleRate) / 1000).toInt().coerceAtLeast(64)
        val frames = samples.size / frameSamples
        if (frames == 0) return samples

        var first = -1
        var last = -1
        for (f in 0 until frames) {
            val offset = f * frameSamples
            var sumSq = 0.0
            for (i in 0 until frameSamples) {
                val s = samples[offset + i].toDouble()
                sumSq += s * s
            }
            if (sumSq / frameSamples >= SILENCE_RMS * SILENCE_RMS) {
                if (first < 0) first = f
                last = f
            }
        }
        if (first < 0) return ShortArray(0) // (near) silence only: nothing to fit

        val pad = (TRIM_PAD_SEC * sampleRate).toInt()
        val from = maxOf(0, first * frameSamples - pad)
        val to = minOf(samples.size, (last + 1) * frameSamples + pad)
        return samples.copyOfRange(from, to)
    }
}
