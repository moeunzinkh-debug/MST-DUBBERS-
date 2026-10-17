package com.example.media

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Audio Voice Activity Detection (VAD) & Speech Onset Detector.
 *
 * Scans the original video's audio track around a subtitle segment to detect
 * the exact moment (timestamp in seconds) when the actor/speaker actually
 * begins speaking dialogue.
 */
object AudioVadDetector {

    private const val FRAME_MS = 25L // 25ms per analysis window
    private const val DEQUEUE_TIMEOUT_US = 5_000L

    /**
     * Detects speech onset (in seconds) within [startSec] and [endSec] in [mediaFile].
     *
     * @param mediaFile the original video or audio file.
     * @param startSec beginning of subtitle slot (seconds).
     * @param endSec end of subtitle slot (seconds).
     * @param ttsDurationSec duration of the generated TTS dub audio (seconds).
     * @return the detected onset timestamp in seconds, or null if no clear speech onset found.
     */
    fun detectSpeechOnset(
        mediaFile: File,
        startSec: Double,
        endSec: Double,
        ttsDurationSec: Double = 0.0
    ): Double? {
        if (!mediaFile.exists() || endSec <= startSec) return null

        val pcm = decodeAudioSlice(mediaFile, startSec, endSec) ?: return null
        if (pcm.samples.isEmpty() || pcm.sampleRate <= 0) return null

        val sampleRate = pcm.sampleRate
        val frameSamples = ((FRAME_MS * sampleRate) / 1000).toInt().coerceAtLeast(100)
        val numFrames = pcm.samples.size / frameSamples
        if (numFrames < 4) return null

        // Compute RMS energy for each 25ms frame
        val frameEnergies = DoubleArray(numFrames)
        for (f in 0 until numFrames) {
            val offset = f * frameSamples
            var sumSq = 0.0
            for (i in 0 until frameSamples) {
                val s = pcm.samples[offset + i].toDouble()
                sumSq += s * s
            }
            frameEnergies[f] = sqrt(sumSq / frameSamples)
        }

        // Find baseline noise floor (approx 15th percentile)
        val sortedEnergies = frameEnergies.clone().apply { sort() }
        val noiseFloorIndex = (numFrames * 0.15).toInt().coerceIn(0, numFrames - 1)
        val noiseFloor = sortedEnergies[noiseFloorIndex]
        val peakEnergy = sortedEnergies.last()

        // If dynamic range is very flat (almost complete silence or loud constant drone), no clear onset
        if (peakEnergy < 200.0 || peakEnergy <= noiseFloor * 1.5) {
            return null
        }

        // Speech threshold: at least 2.4x noise floor, or minimum audible speech level
        val threshold = max(noiseFloor * 2.4, peakEnergy * 0.22).coerceAtLeast(250.0)

        // Find first point where 3 consecutive frames exceed threshold (to filter out clicks/taps)
        var speechFrame = -1
        for (f in 0 until numFrames - 2) {
            if (frameEnergies[f] >= threshold &&
                frameEnergies[f + 1] >= threshold * 0.85 &&
                frameEnergies[f + 2] >= threshold * 0.75
            ) {
                speechFrame = f
                break
            }
        }

        if (speechFrame < 0) return null

        val onsetWithinSlice = (speechFrame * frameSamples).toDouble() / sampleRate
        val detectedSec = startSec + onsetWithinSlice
        val rounded = Math.round(detectedSec * 10.0) / 10.0

        // Ensure detected onset gives enough room for speech before endSec
        if (ttsDurationSec > 0.0 && rounded + ttsDurationSec > endSec + 0.4) {
            // If onset is too late to fit the speech, snap so it ends cleanly at endSec
            val adjusted = (endSec - ttsDurationSec).coerceAtLeast(startSec)
            return Math.round(adjusted * 10.0) / 10.0
        }

        return rounded.coerceIn(startSec, endSec)
    }

    /**
     * Decodes a targeted slice of audio between [startSec] and [endSec] to mono 16-bit PCM.
     */
    private fun decodeAudioSlice(file: File, startSec: Double, endSec: Double): PcmAudio? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null

            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null

            val startUs = (startSec * 1_000_000).toLong().coerceAtLeast(0L)
            val endUs = ((endSec + 0.3) * 1_000_000).toLong()
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            var sampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 44100
            var channelCount = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 1

            val codec = MediaCodec.createDecoderByType(mime)
            val output = ShortArrayBuilder(32 * 1024)
            val info = MediaCodec.BufferInfo()

            try {
                codec.configure(inputFormat, null, null, 0)
                codec.start()

                var inputDone = false
                var outputDone = false
                var samplesRead = 0
                val maxSamples = (((endSec - startSec + 1.0) * sampleRate)).toInt().coerceAtLeast(10_000)

                val startTime = System.currentTimeMillis()
                while (!outputDone && System.currentTimeMillis() - startTime < 1_500L && samplesRead < maxSamples) {
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
                        }
                    }

                    val outIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                    when {
                        outIndex >= 0 -> {
                            if (info.size > 0) {
                                val outBuffer = codec.getOutputBuffer(outIndex)
                                if (outBuffer != null) {
                                    outBuffer.position(info.offset)
                                    outBuffer.limit(info.offset + info.size)
                                    val decoded = decodeBufferToMono(outBuffer, channelCount)
                                    output.addAll(decoded)
                                    samplesRead += decoded.size
                                }
                            }
                            codec.releaseOutputBuffer(outIndex, false)
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
                        }
                    }
                }
            } finally {
                try { codec.stop() } catch (_: Exception) {}
                codec.release()
            }

            return PcmAudio(output.toShortArray(), sampleRate)
        } catch (e: Exception) {
            return null
        } finally {
            extractor.release()
        }
    }

    private fun decodeBufferToMono(buffer: ByteBuffer, channelCount: Int): ShortArray {
        val channels = channelCount.coerceAtLeast(1)
        val shorts = buffer.slice().order(ByteOrder.nativeOrder()).asShortBuffer()
        val frames = shorts.remaining() / channels
        val mono = ShortArray(frames)
        for (frame in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) {
                sum += shorts.get(frame * channels + c)
            }
            mono[frame] = (sum / channels).toShort()
        }
        return mono
    }
}
