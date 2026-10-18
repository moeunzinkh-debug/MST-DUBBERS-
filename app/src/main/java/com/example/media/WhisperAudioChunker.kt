package com.example.media

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.example.data.api.AudioTrackExtractor
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TurboScribe-style audio preparation for Groq Whisper.
 *
 * TurboScribe never sends one long file to Whisper: it detects speech, slices the audio at
 * silent scenes, and transcribes the speech only — which is why its segment timestamps land
 * on the dialogue instead of stretching across fight-scene pauses and intro music.
 *
 * This chunker reproduces that pipeline on-device:
 *  1. decode the video's audio track (preferring a track that declares the requested
 *     language, for dual-audio releases);
 *  2. downmix to mono and resample to 16 kHz (Whisper's native rate);
 *  3. run [SpeechBlockCollector] over the PCM and write every completed speech block to a
 *     small WAV file, ready to be uploaded one by one.
 *
 * Returns null whenever the audio cannot be decoded or no speech is found, so the caller can
 * fall back to uploading the whole file the old way.
 */
object WhisperAudioChunker {

    /** Whisper works on 16 kHz; chunks are uploaded as 16-bit mono PCM WAV. */
    const val TARGET_SAMPLE_RATE = 16000

    private const val DEQUEUE_TIMEOUT_US = 10_000L
    private const val STALL_TIMEOUT_MS = 20_000L

    /** One VAD-sliced WAV plus where it sits on the original media timeline. */
    data class Chunk(
        val wavFile: File,
        val startSec: Double,
        val regions: List<SpeechInterval>
    )

    /**
     * Slices [source]'s audio into speech-block WAV files. Returns null when the audio
     * cannot be decoded or contains no detectable speech (caller falls back to a single
     * whole-file upload).
     */
    fun chunkAudio(source: File, preferredLanguage: String?): List<Chunk>? {
        val chunks = try {
            decodeAndSlice(source, preferredLanguage)
        } catch (_: Throwable) {
            // Unsupported codec, unreadable container, Robolectric, ... — not fatal: the
            // caller still has the classic single-upload path.
            null
        }
        if (chunks.isNullOrEmpty()) {
            chunks.orEmpty().forEach { it.wavFile.delete() }
            return null
        }
        return chunks
    }

    private fun decodeAndSlice(source: File, preferredLanguage: String?): List<Chunk>? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source.absolutePath)
            val trackIndex = AudioTrackExtractor.selectAudioTrackIndex(extractor, preferredLanguage)
            if (trackIndex < 0) return null
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null

            var sampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                0
            }
            var channelCount = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                1
            }
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

            val collector = SpeechBlockCollector(TARGET_SAMPLE_RATE)
            val chunks = mutableListOf<Chunk>()
            var anchorSec = -1.0
            var resampler: PcmResampler? = null

            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(inputFormat, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                val mono = ShortArrayBuilder(48 * 1024)
                var inputDone = false
                var outputDone = false
                var lastProgressAt = System.currentTimeMillis()

                while (!outputDone) {
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                        if (inIndex >= 0) {
                            val inBuffer = codec.getInputBuffer(inIndex) ?: return null
                            val size = extractor.readSampleData(inBuffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
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
                                    if (anchorSec < 0.0) {
                                        // Some containers start the audio track later than
                                        // 00:00; the first decoded PTS is the timeline anchor.
                                        anchorSec = if (info.presentationTimeUs >= 0) {
                                            info.presentationTimeUs / 1_000_000.0
                                        } else {
                                            0.0
                                        }
                                    }
                                    outBuffer.position(info.offset)
                                    outBuffer.limit(info.offset + info.size)
                                    mono.clear()
                                    AudioClipDecoder.appendAsMono(outBuffer, pcmEncoding, channelCount, mono)
                                    if (mono.size > 0) {
                                        val currentRate = if (sampleRate > 0) sampleRate else TARGET_SAMPLE_RATE
                                        if (resampler == null || resampler.srcRate != currentRate) {
                                            resampler = PcmResampler(currentRate, TARGET_SAMPLE_RATE)
                                        }
                                        val pcm = mono.toShortArray()
                                        resampler.process(pcm, pcm.size) { out, count ->
                                            collector.append(out, count)
                                        }
                                        writeCompletedBlocks(collector.takeBlocks(), anchorSec, source, chunks)
                                    }
                                }
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                            }
                            lastProgressAt = System.currentTimeMillis()
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

                    if (System.currentTimeMillis() - lastProgressAt > STALL_TIMEOUT_MS) {
                        return null
                    }
                }
            } finally {
                try {
                    codec.stop()
                } catch (_: Exception) {
                }
                codec.release()
            }

            if (anchorSec < 0.0) return null // nothing was decoded at all
            writeCompletedBlocks(collector.finish(), anchorSec, source, chunks)
            return chunks
        } finally {
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun writeCompletedBlocks(
        blocks: List<SpeechBlockCollector.Block>,
        anchorSec: Double,
        source: File,
        out: MutableList<Chunk>
    ) {
        for (block in blocks) {
            val wavFile = writeWav(source, block)
            out += Chunk(
                wavFile = wavFile,
                startSec = anchorSec + block.startSec,
                regions = block.regions
            )
        }
    }

    private fun writeWav(source: File, block: SpeechBlockCollector.Block): File {
        val wavFile = try {
            File.createTempFile("mst_whisper_chunk_", ".wav", source.parentFile)
        } catch (_: Exception) {
            File.createTempFile("mst_whisper_chunk_", ".wav")
        }
        val pcmBytes = ByteArray(block.samples.size * 2)
        ByteBuffer.wrap(pcmBytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .put(block.samples)
        FileOutputStream(wavFile).use { stream ->
            stream.write(WavCodec.header(block.sampleRate, 1, pcmBytes.size.toLong()))
            stream.write(pcmBytes)
        }
        return wavFile
    }
}

/**
 * Streams mono PCM from one sample rate to another with linear interpolation, keeping
 * continuity across chunk boundaries.
 */
internal class PcmResampler(val srcRate: Int, private val dstRate: Int) {

    init {
        require(srcRate > 0 && dstRate > 0) { "sample rates must be positive" }
    }

    /** Position of the next output sample, in input samples, relative to the current chunk. */
    private var position = 0.0
    private var prevSample = 0.0

    fun process(input: ShortArray, length: Int, out: (ShortArray, Int) -> Unit) {
        if (length <= 0) return
        if (srcRate == dstRate) {
            out(input, length)
            return
        }
        val step = srcRate.toDouble() / dstRate
        val buffer = ShortArrayBuilder(1024)
        while (position < length - 1) {
            val value = when {
                position < 0.0 -> {
                    // Between the previous chunk's last sample and this chunk's first one.
                    val f = 1.0 + position
                    prevSample + (input[0] - prevSample) * f
                }
                else -> {
                    val i0 = position.toInt()
                    val f = position - i0
                    input[i0] + (input[i0 + 1] - input[i0]) * f
                }
            }
            buffer.add(value.coerceIn(-32768.0, 32767.0).toInt().toShort())
            position += step
        }
        position -= length
        prevSample = input[length - 1].toDouble()
        if (buffer.size > 0) {
            val samples = buffer.toShortArray()
            out(samples, samples.size)
        }
    }
}
