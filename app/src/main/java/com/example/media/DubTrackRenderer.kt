package com.example.media

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Builds the full-length AI voice track: every [DubClip] is time-stretched to its playback rate
 * and written at its exact start time; everything else is silence.
 *
 * Clips are placed by absolute sample position, so timing never drifts, even if a clip's real
 * duration differs from what was measured when it was generated.
 */
object DubTrackRenderer {

    const val OUTPUT_SAMPLE_RATE = 44_100

    /** Short fade applied where a clip is cut, to avoid an audible click. */
    private const val CUT_FADE_SEC = 0.012

    data class Result(
        val file: File,
        val durationSec: Double,
        val clipsWritten: Int,
        val clipsFailed: Int
    )

    /**
     * Renders [clips] (sorted, non-overlapping, from [DubTimeline.plan]) into a mono 16-bit WAV
     * file of exactly [durationSec] seconds.
     *
     * @param decode decodes one clip file; injectable for tests.
     * @param onClipDone progress callback, called after each clip.
     * @param checkCancelled called between clips; throw from it to abort.
     */
    fun render(
        clips: List<DubClip>,
        durationSec: Double,
        output: File,
        decode: (File) -> PcmAudio = AudioClipDecoder::decodeToMono,
        backgroundAudio: PcmAudio? = null,
        backgroundVolume: Float = 1.0f,
        duckingFactor: Float = 0.10f,
        dubVolume: Float = 1.0f,
        onClipDone: (done: Int, total: Int) -> Unit = { _, _ -> },
        checkCancelled: () -> Unit = {}
    ): Result {
        val sampleRate = OUTPUT_SAMPLE_RATE
        val totalSamples = (durationSec.coerceAtLeast(0.0) * sampleRate).roundToLong()
        var written = 0
        var failed = 0

        val bgSamples: ShortArray? = backgroundAudio?.let { bg ->
            if (bg.samples.isEmpty()) null
            else if (bg.sampleRate == sampleRate) bg.samples
            else timeStretch(bg, 1f, sampleRate)
        }

        output.parentFile?.mkdirs()
        WavWriter(
            file = output,
            sampleRate = sampleRate,
            bgSamples = bgSamples,
            backgroundVolume = backgroundVolume
        ).use { writer ->
            for ((index, clip) in clips.withIndex()) {
                checkCancelled()
                val startSample = (clip.startSec * sampleRate).roundToLong()
                if (startSample >= totalSamples) break
                if (startSample > writer.samplesWritten) {
                    writer.writeBackground(startSample - writer.samplesWritten)
                }

                val pcm = try {
                    timeStretch(decode(File(clip.audioPath)), clip.rate, sampleRate)
                } catch (e: Exception) {
                    if (e is InterruptedException) throw e
                    failed++
                    onClipDone(index + 1, clips.size)
                    continue
                }

                // Normally zero; only skips audio if a clip somehow starts before the previous ended.
                val skip = (writer.samplesWritten - startSample).coerceAtLeast(0L)
                val maxSamples = (clip.maxDurationSec * sampleRate).roundToLong() - skip
                val room = totalSamples - writer.samplesWritten
                val count = min(min(maxSamples, room), pcm.size - skip).coerceAtLeast(0L).toInt()
                if (count > 0) {
                    val from = skip.toInt()
                    val wasCut = count < pcm.size - from
                    if (wasCut) fadeOut(pcm, from + count, (CUT_FADE_SEC * sampleRate).toInt())
                    writer.writeMixed(pcm, from, count, dubVolume, duckingFactor)
                    written++
                }
                onClipDone(index + 1, clips.size)
            }
            if (totalSamples > writer.samplesWritten) {
                writer.writeBackground(totalSamples - writer.samplesWritten)
            }
        }
        return Result(output, totalSamples.toDouble() / sampleRate, written, failed)
    }

    /**
     * Changes the speed of [input] by [speed] without changing its pitch, and resamples it to
     * [outputSampleRate], using Media3's Sonic (the same algorithm ExoPlayer uses).
     */
    @OptIn(UnstableApi::class)
    fun timeStretch(input: PcmAudio, speed: Float, outputSampleRate: Int): ShortArray {
        val needsSpeedChange = abs(speed - 1f) > 0.001f
        if (!needsSpeedChange && input.sampleRate == outputSampleRate) return input.samples
        if (input.samples.isEmpty()) return input.samples

        val sonic = SonicAudioProcessor()
        sonic.setSpeed(speed.coerceIn(0.25f, 4f))
        sonic.setPitch(1f)
        sonic.setOutputSampleRateHz(outputSampleRate)
        sonic.configure(AudioProcessor.AudioFormat(input.sampleRate, 1, C.ENCODING_PCM_16BIT))
        sonic.flush(AudioProcessor.StreamMetadata.DEFAULT)

        val expected = (input.samples.size.toDouble() * outputSampleRate / input.sampleRate / speed).toInt()
        val out = ShortArrayBuilder(expected + 1024)
        val chunkSamples = 4096
        val inBuffer = ByteBuffer.allocateDirect(chunkSamples * 2).order(ByteOrder.nativeOrder())
        var position = 0
        while (position < input.samples.size) {
            val n = min(chunkSamples, input.samples.size - position)
            inBuffer.clear()
            for (i in 0 until n) inBuffer.putShort(input.samples[position + i])
            inBuffer.flip()
            sonic.queueInput(inBuffer)
            drainInto(sonic, out)
            position += n
        }
        sonic.queueEndOfStream()
        var guard = 0
        while (!sonic.isEnded && guard++ < 10_000) {
            if (!drainInto(sonic, out)) break
        }
        drainInto(sonic, out)
        return out.toShortArray()
    }

    @OptIn(UnstableApi::class)
    private fun drainInto(sonic: SonicAudioProcessor, out: ShortArrayBuilder): Boolean {
        val buffer = sonic.output
        if (!buffer.hasRemaining()) return false
        val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
        val chunk = ShortArray(shorts.remaining())
        shorts.get(chunk)
        out.addAll(chunk)
        buffer.position(buffer.limit())
        return true
    }

    /** Fades out the [fadeSamples] samples before [end] (exclusive), in place. */
    private fun fadeOut(samples: ShortArray, end: Int, fadeSamples: Int) {
        val n = min(fadeSamples, end)
        if (n <= 0) return
        for (i in 0 until n) {
            val index = end - n + i
            val gain = 1f - (i + 1).toFloat() / n
            samples[index] = (samples[index] * gain).toInt().toShort()
        }
    }
}

/** Streams mono 16-bit PCM samples into a WAV file; the header is fixed up on [close]. */
internal class WavWriter(
    private val file: File,
    private val sampleRate: Int,
    private val bgSamples: ShortArray? = null,
    private val backgroundVolume: Float = 1.0f
) : Closeable {
    private val stream = BufferedOutputStream(FileOutputStream(file), 64 * 1024)
    private val scratch = ByteArray(16 * 1024)
    var samplesWritten = 0L
        private set

    // Gain smoother for ducking: ~30ms exponential glide
    private var currentDuckingGain = 1.0f
    private val smoothingFactor = (1.0f / (0.030f * sampleRate)).coerceIn(0.0005f, 0.05f)

    init {
        stream.write(WavCodec.header(sampleRate, 1, 0))
    }

    fun writeBackground(count: Long) {
        val bg = bgSamples
        val bgVol = backgroundVolume
        val hasBg = bg != null && bgVol > 0f

        var remaining = count
        while (remaining > 0) {
            val n = min(remaining, (scratch.size / 2).toLong()).toInt()
            if (!hasBg) {
                scratch.fill(0, 0, n * 2)
            } else {
                for (k in 0 until n) {
                    val pos = samplesWritten + k
                    currentDuckingGain += (1.0f - currentDuckingGain) * smoothingFactor
                    val origSample = if (pos < bg.size) bg[pos.toInt()].toFloat() else 0f
                    val s = (origSample * bgVol * currentDuckingGain).coerceIn(
                        Short.MIN_VALUE.toFloat(),
                        Short.MAX_VALUE.toFloat()
                    ).toInt()
                    scratch[2 * k] = (s and 0xFF).toByte()
                    scratch[2 * k + 1] = ((s shr 8) and 0xFF).toByte()
                }
            }
            stream.write(scratch, 0, n * 2)
            samplesWritten += n
            remaining -= n
        }
    }

    fun writeMixed(
        clipPcm: ShortArray,
        offset: Int,
        count: Int,
        dubVolume: Float,
        duckingFactor: Float
    ) {
        val bg = bgSamples
        val bgVol = backgroundVolume
        val hasBg = bg != null && bgVol > 0f

        var i = 0
        while (i < count) {
            val n = min(count - i, scratch.size / 2)
            for (k in 0 until n) {
                val clipSample = clipPcm[offset + i + k].toFloat() * dubVolume
                val s = if (hasBg) {
                    val pos = samplesWritten + k
                    currentDuckingGain += (duckingFactor - currentDuckingGain) * smoothingFactor
                    val origSample = if (pos < bg.size) bg[pos.toInt()].toFloat() else 0f
                    val duckedBg = origSample * bgVol * currentDuckingGain
                    (clipSample + duckedBg).coerceIn(
                        Short.MIN_VALUE.toFloat(),
                        Short.MAX_VALUE.toFloat()
                    ).toInt()
                } else {
                    clipSample.coerceIn(
                        Short.MIN_VALUE.toFloat(),
                        Short.MAX_VALUE.toFloat()
                    ).toInt()
                }
                scratch[2 * k] = (s and 0xFF).toByte()
                scratch[2 * k + 1] = ((s shr 8) and 0xFF).toByte()
            }
            stream.write(scratch, 0, n * 2)
            samplesWritten += n
            i += n
        }
    }

    fun writeSilence(count: Long) = writeBackground(count)

    fun writeSamples(samples: ShortArray, offset: Int, count: Int) {
        writeMixed(samples, offset, count, dubVolume = 1.0f, duckingFactor = 1.0f)
    }

    override fun close() {
        stream.close()
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(WavCodec.header(sampleRate, 1, samplesWritten * 2))
        }
    }
}
