package com.example.media

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import kotlin.math.roundToInt

/**
 * Audio Voice Activity Detection (VAD) & Speech Onset Detector.
 *
 * Scans the original video's audio track around a subtitle segment to detect the exact
 * moment (timestamp in seconds) when the actor/speaker actually begins speaking, so the
 * generated dub can be placed on that waveform onset instead of the subtitle start.
 *
 * The waveform analysis itself lives in [SpeechOnsetFinder]; this object only decodes the
 * slice. Two past bugs made the dub start *before* the actor:
 *  1. The decoded PCM was assumed to start exactly at the requested seek time, but
 *     [MediaExtractor.SEEK_TO_PREVIOUS_SYNC] lands on an earlier sync sample (and AAC adds
 *     decoder priming), so every onset was shifted early. The decoder's
 *     `presentationTimeUs` is now used as the time anchor, and samples before the search
 *     window are trimmed.
 *  2. The decode loop gave up after 1.5s of wall-clock time regardless of progress, so on
 *     slower devices it could return a truncated/empty slice. It now stops only after
 *     [STALL_TIMEOUT_MS] without any decoder progress.
 */
object AudioVadDetector {

    private const val DEQUEUE_TIMEOUT_US = 5_000L

    /** Give up when the decoder makes no progress for this long (see [AudioClipDecoder]). */
    private const val STALL_TIMEOUT_MS = 8_000L

    /**
     * The slice is decoded slightly before the subtitle start: a previous detection (or a
     * subtitle edited by hand) may have moved the start a little past the true onset, and
     * re-generating a voice must still be able to find the speech again.
     */
    private const val LEAD_IN_SEC = 0.35

    /** A decoded slice of mono PCM plus the timeline time (seconds) of its first sample. */
    private class Slice(val samples: ShortArray, val sampleRate: Int, val startSec: Double)

    /**
     * Detects speech onset (in seconds) within [startSec] and [endSec] in [mediaFile].
     *
     * Note there is deliberately no "fit the TTS clip inside the slot" adjustment here any
     * more: the previous version moved a detected onset back to `endSec - ttsDuration`
     * whenever the dub was longer than the slot — which is most lines — throwing the
     * detected onset away and starting the dub at the subtitle start, ahead of the actor.
     * Overrun is handled downstream instead: [DubTimeline] speeds the clip up (Auto-Fit)
     * and cuts it when the next line starts.
     *
     * @param mediaFile the original video or audio file.
     * @param startSec beginning of subtitle slot (seconds).
     * @param endSec end of subtitle slot (seconds).
     * @return the detected onset timestamp in seconds, or null if no clear speech onset found.
     */
    fun detectSpeechOnset(
        mediaFile: File,
        startSec: Double,
        endSec: Double
    ): Double? {
        if (!mediaFile.exists() || endSec <= startSec) return null

        val windowStart = (startSec - LEAD_IN_SEC).coerceAtLeast(0.0)
        val slice = decodeAudioSlice(mediaFile, windowStart, endSec) ?: return null

        val onset = SpeechOnsetFinder.findOnsetSec(slice.samples, slice.sampleRate, slice.startSec)
            ?: return null
        return onset.coerceIn(windowStart, endSec)
    }

    /**
     * Decodes the audio between [windowStartSec] and [endSec] to mono 16-bit PCM, anchored
     * to the decoder's presentation timestamps (not to the requested seek time).
     */
    private fun decodeAudioSlice(file: File, windowStartSec: Double, endSec: Double): Slice? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null

            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null

            val startUs = (windowStartSec * 1_000_000).toLong().coerceAtLeast(0L)
            val endUs = (endSec * 1_000_000).toLong()
            // Lands on a sync sample at or *before* startUs; the first output buffer's
            // presentationTimeUs below tells us where the PCM really starts.
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            var sampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 44100
            var channelCount = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 1
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

            val codec = MediaCodec.createDecoderByType(mime)
            val output = ShortArrayBuilder(32 * 1024)
            val info = MediaCodec.BufferInfo()
            var firstPtsSec: Double? = null

            try {
                codec.configure(inputFormat, null, null, 0)
                codec.start()

                var inputDone = false
                var outputDone = false
                val maxSamples = (((endSec - windowStartSec + 1.0) * sampleRate)).toInt().coerceAtLeast(10_000)
                var lastProgressAt = System.currentTimeMillis()

                while (!outputDone && output.size < maxSamples) {
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                        if (inIndex >= 0) {
                            val inBuffer = codec.getInputBuffer(inIndex) ?: break
                            val sampleTime = extractor.sampleTime
                            val size = extractor.readSampleData(inBuffer, 0)

                            if (size < 0 || sampleTime > endUs) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, size, sampleTime, 0)
                                extractor.advance()
                            }
                            lastProgressAt = System.currentTimeMillis()
                        }
                    }

                    val outIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                    when {
                        outIndex >= 0 -> {
                            if (info.size > 0) {
                                val outBuffer = codec.getOutputBuffer(outIndex)
                                if (outBuffer != null) {
                                    if (firstPtsSec == null) {
                                        firstPtsSec = if (info.presentationTimeUs >= 0) {
                                            info.presentationTimeUs / 1_000_000.0
                                        } else {
                                            windowStartSec
                                        }
                                    }
                                    outBuffer.position(info.offset)
                                    outBuffer.limit(info.offset + info.size)
                                    AudioClipDecoder.appendAsMono(outBuffer, pcmEncoding, channelCount, output)
                                }
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            lastProgressAt = System.currentTimeMillis()
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                            }
                        }
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val format = codec.outputFormat
                            if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            }
                            if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            }
                            if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                pcmEncoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            }
                        }
                    }

                    if (System.currentTimeMillis() - lastProgressAt > STALL_TIMEOUT_MS) break
                }
            } finally {
                try { codec.stop() } catch (_: Exception) {}
                codec.release()
            }

            val samples = output.toShortArray()
            if (samples.isEmpty() || sampleRate <= 0) return null

            // Trim whatever the sync-seek/priming decoded from before the search window, so
            // the previous line's speech can never be mistaken for this line's onset.
            val rawStartSec = firstPtsSec ?: windowStartSec
            val dropSamples = ((windowStartSec - rawStartSec) * sampleRate).roundToInt()
                .coerceIn(0, samples.size)
            return if (dropSamples > 0) {
                Slice(samples.copyOfRange(dropSamples, samples.size), sampleRate, windowStartSec)
            } else {
                Slice(samples, sampleRate, rawStartSec)
            }
        } catch (e: Exception) {
            return null
        } finally {
            extractor.release()
        }
    }
}
